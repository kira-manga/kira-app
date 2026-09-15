package me.manga.kira.presentation.features.download.domain.clean

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import me.manga.kira.core.util.data_classes.HandelDataClasses.toChapterDownloadEntity
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.download.artifacts.ChapterDownloadArtifacts
import me.manga.kira.data.download.artifacts.QueuedArtifactAdmission
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.platform.download.StagedDownloadPage
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.platform.download.BackgroundScheduler
import me.manga.kira.platform.download.BackgroundTransport
import me.manga.kira.platform.download.BackgroundWorkSignal
import me.manga.kira.platform.download.BgDownloadLog
import me.manga.kira.platform.download.TransferListener
import me.manga.kira.platform.download.TransferRequest
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.notification.DownloadNotifier
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.inspectPageArchive
import me.manga.kira.platform.media.isPagePolicyRejection
import me.manga.kira.presentation.features.download.data.DownloadingState
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import okio.Path.Companion.toPath

/**
 * iOS [DownloadRepository] backed by a background `NSURLSession` (background-downloads M2–M5).
 *
 * Selected only when `DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED` is `true`; otherwise iOS
 * keeps the proven `CoroutineDownloadRepositoryImpl`. Page transfers run through [BackgroundTransport]
 * (a background session), so they continue while the app is suspended. This class owns only the
 * platform-neutral orchestration and reuses the M1 collaborators ([ChapterPageResolver],
 * [ChapterFinalizer]) plus the M3 durable-state pieces ([DownloadManifestStore], [BackgroundReconciler]).
 *
 * Flow per chapter: QUEUED → (resolve + persist manifest + enqueue a rolling window) → RUNNING →
 * (pages land on disk) → DOWNLOADED → (finalize, foreground only) → SUCCESS.
 *
 * All meaningful behavior is traced under the `KiraBgDownload` tag ([BgDownloadLog]) for the test build.
 */
@OptIn(ExperimentalForeignApi::class)
class BackgroundUrlSessionDownloadRepository(
    storage: BackgroundDownloadStorage,
    stages: ChapterDownloadStages,
    pageTransfer: BackgroundPageTransfer,
    host: BackgroundDownloadHost,
    // The live store supplies fresh retry headers and the user's compression opt-in.
    private val dataStoreHelper: DataStoreHelper,
    private val artifacts: ChapterDownloadArtifacts,
) : DownloadRepository,
    TransferListener {
    private val dao: ChapterDownloadDao = storage.downloads
    private val manifestStore: DownloadManifestStore = storage.manifests
    private val appFileSystem: AppFileSystem = storage.files
    private val chapterPageResolver: ChapterPageResolver = stages.resolver
    private val chapterFinalizer: ChapterFinalizer = stages.finalizer
    private val transport: BackgroundTransport = pageTransfer.transport
    private val mediaInspector: PageMediaInspector = pageTransfer.mediaInspector
    private val pageBytePolicy: PageBytePolicy = pageTransfer.pageBytePolicy
    private val applicationScope: CoroutineScope = host.applicationScope
    private val downloadNotifier: DownloadNotifier = host.downloadNotifier
    private val backgroundScheduler: BackgroundScheduler = host.scheduler
    private val workSignal: BackgroundWorkSignal = host.workSignal

    private val mutex = Mutex()
    private val attempts = mutableMapOf<Long, ChapterArtifactClaim>()
    private var parentAdmissionWaiter: Job? = null

    /** chapterIds with a finalize coroutine in flight — guards against double-finalize. (Guarded by [mutex].) */
    private val finalizing = mutableSetOf<Long>()

    /** chapterIds with a resolve coroutine in flight (B6: the network scrape runs OFF [mutex], so a second
     *  pump pass must not launch a duplicate resolve for the same chapter). (Guarded by [mutex].) */
    private val resolving = mutableSetOf<Long>()

    /** chapterIds with a resolve-AHEAD (prefetch) scrape in flight — at most one at a time
     *  ([ResolveAheadRules] serializes on this set). (Guarded by [mutex].) */
    private val prefetching = mutableSetOf<Long>()

    /** Set when a prefetch scrape fails (challenge / 403 / rate-limit / anything): prefetching stays
     *  paused for [PREFETCH_FAILURE_BACKOFF] so a struggling source is never hammered by lookahead.
     *  The REAL resolve when a chapter's turn comes is unaffected. (Guarded by [mutex].) */
    private var prefetchPausedAtMark: TimeSource.Monotonic.ValueTimeMark? = null

    /** Serializes the heavy CBZ encode across chapters (CPU-bound) — held OUTSIDE [mutex]. */
    private val finalizeSemaphore = Semaphore(1)

    /** Whether the app is foregrounded. Drives [canCompressNow] (foreground compression is gated on a
     *  settled, healthy device; background compression needs a real BG-task window). */
    private var appActive = false

    /** Monotonic mark of the last foreground entry (didBecomeActive). [canCompressNow] requires the app to
     *  have been foreground for [FOREGROUND_SETTLE] before allowing a foreground encode, so compression
     *  never collides with launch/reopen warm-up (the confirmed freeze). Null until the first foregrounding. */
    private var foregroundedAtMark: TimeSource.Monotonic.ValueTimeMark? = null

    /** User opt-in to compress even while iOS Low Power Mode is active (settings toggle; default false =
     *  respect the battery-saving intent). Mirrors [DataStoreHelper.allowCompressionInLowPowerFlow]; kept as a
     *  plain cached field for the synchronous [canCompressNow] read (same advisory cross-thread pattern as
     *  [appActive]) and refreshed by the compression-gate watcher in `init`. */
    private var allowLowPowerCompression = false

    // ---- per-chapter hot-path caches (all access under [mutex]) ----
    //
    // Page completion fires once per page (hundreds of times for a long webtoon chapter). Each call
    // used to re-read + JSON-parse the WHOLE manifest (`manifestStore.read`) AND list the WHOLE chapter
    // directory (`pagesOnDiskSet`) — O(pages) work × O(pages) callbacks = O(pages²), all under [mutex].
    // On a real device a 360-page chapter made that storm jank the whole app (the original lag report).
    // These three caches collapse the hot path to O(1):

    /** Chapter manifest, cached after first read/create. Only [DownloadManifest.pages] (index/url/headers)
     *  is read on the hot path and it is immutable post-creation, so the cache is never stale for
     *  progress/completion. Seeded by [reconcileChapterLocked] / [prepareLocked]; dropped by [clearChapterCaches]. */
    private val manifestCache = HashMap<Long, DownloadManifest>()

    /** Page indices known on disk, updated incrementally as pages land ([handlePageCompleteLocked]) and
     *  **re-grounded in real disk truth** by [reconcileChapterLocked] (which runs on every pump / window
     *  fill / relaunch) — so force-quit recovery is unaffected and a stale entry self-heals next reconcile. */
    private val onDiskCache = HashMap<Long, MutableSet<Int>>()

    /** Last percent handed to Room/notifier per chapter — lets [updateProgressLocked] skip an unchanged
     *  percent instead of re-writing Room + re-posting the notification on every single page. */
    private val lastPostedPercent = HashMap<Long, Int>()

    /** Chapters whose DOWNLOADED-transition bookkeeping (mark-readable + slot release) has already run —
     *  guards [markDownloadedAndMaybeFinalizeLocked] so a repeat page-complete / reconcile pass for an
     *  already-readable (CBZ-pending) chapter doesn't re-walk the dir + rewrite Room every tick. The CBZ
     *  retry for a deferred chapter comes from the pump's finalize sweep, not from re-running this.
     *  Dropped by [clearChapterCaches] (finalize attempt done / cancel / delete / fresh enqueue). */
    private val readableMarked = mutableSetOf<Long>()

    init {
        BgDownloadLog.log("engine.init", "engine" to "BackgroundUrlSession")
        transport.setListener(this)
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, null) { _ ->
            appActive = true
            foregroundedAtMark = TimeSource.Monotonic.markNow()
            BgDownloadLog.log("lifecycle.didBecomeActive")
            applicationScope.launch { runCatching { mutex.withLock { pumpLocked("didBecomeActive") } } }
        }
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) { _ ->
            appActive = false
            BgDownloadLog.log("lifecycle.didEnterBackground")
        }
        applicationScope.launch {
            BgDownloadLog.log("lifecycle.launch startupReconcile")
            runCatching {
                transport.ensureReady()
                mutex.withLock { pumpLocked("startup") }
            }.onFailure { BgDownloadLog.error(it, "startup.pumpFailed") }
        }
        // Mirror queue state into the work signal (read synchronously by the iOS host's BG-task layer)
        // and ask the OS for a background CPU window when work first becomes pending.
        applicationScope.launch {
            var lastPending = false
            // conflate(): Room re-emits the whole-table flow on every write, so a bulk "Download all"
            // fires a burst of ~3N emissions. We only need the LATEST snapshot to refresh the signal —
            // conflate collapses the burst to one recompute instead of N, cutting enqueue-time churn.
            dao.observeAllDownloads().conflate().collect { list ->
                // One-chapter-at-a-time Live Activity inputs, computed by the pure WorkSignalRules:
                // whole-queue percent = the LEAD chapter's progress (what the Live Activity actually
                // shows), NOT a sum/active.size average — that average REGRESSED as the divisor shrank
                // when a 100% chapter left the active set (B8).
                val s = WorkSignalRules.compute(list)
                workSignal.update(s.pending, s.progressPercent, s.chapterProgress, s.leadChapterId, s.hasTransferWork)
                BgDownloadLog.log(
                    "signal.update",
                    "pending" to s.pending,
                    "progress" to s.progressPercent,
                    "activeChapters" to s.activeCount,
                    "lead" to s.leadChapterId,
                )
                if (s.pending && !lastPending) {
                    BgDownloadLog.log("scheduler.requestProcessing", "reason" to "workBecamePending")
                    backgroundScheduler.scheduleProcessing()
                }
                lastPending = s.pending
            }
        }
        // Compression-gate watcher — the SINGLE owner of deferred-finalize re-drives (the host bridge no
        // longer edge-pumps on stress changes). Collects the two device-stress flags + the user's Low-Power
        // opt-in: caches the opt-in for the synchronous [canCompressNow] read, and when the effective
        // deferral CLEARS (thermal cooled, Low Power Mode turned off, OR the user opted in) with work still
        // pending, re-drives the finalize sweep so a chapter parked as "paused (Low Power Mode)" starts
        // compressing immediately instead of waiting for the next app background/foreground cycle.
        applicationScope.launch {
            var wasDeferred = false
            combine(
                workSignal.thermallyStressed,
                workSignal.lowPowerMode,
                dataStoreHelper.allowCompressionInLowPowerFlow,
            ) { thermal, lowPower, allowLpm ->
                allowLowPowerCompression = allowLpm
                CompressionGateRules.isDeferred(thermal, lowPower, allowLpm)
            }.collect { deferred ->
                if (wasDeferred && !deferred && workSignal.hasPendingWork) {
                    BgDownloadLog.log("compressionGate.clearedPump")
                    runCatching { reconcileInterruptedDownloads() }
                        .onFailure { BgDownloadLog.error(it, "compressionGate.pumpFailed") }
                }
                wasDeferred = deferred
            }
        }
    }

    // ---- DownloadRepository: observable stream ----

    override fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>> = dao.observeAllDownloads()

    // ---- DownloadRepository: mutating ops ----

    override suspend fun enqueueChapterDownload(
        chapter: SavedChapterEntity,
        title: String,
        mangaApi: String,
    ) = withContext(Dispatchers.Default) {
        mutex.withLock {
            val claim = artifacts.enqueue(chapter, chapter.toChapterDownloadEntity(apiName = mangaApi, title = title))
                ?: return@withLock
            attempts[chapter.id] = claim
            clearChapterCaches(chapter.id)
            artifacts.ownership.files(claim) { manifestStore.delete(chapter.mangaId, chapter.id) }
            transport.ensureReady()
            fillWindowLocked()
        }
    }

    override suspend fun deleteDownload(chapterId: Long) {
        val (row, claim) = mutex.withLock {
            val current = dao.getDownloadByChapter(chapterId) ?: return
            if (current.state == DownloadingState.SUCCESS) {
                // SUCCESS eviction is history-only, including a restored archive with no queue row.
                dao.deleteHistoryAttempt(chapterId, current.id)
                return
            }
            current to cancelLocked(chapterId)
        }
        if (claim != null && !artifacts.settle(claim)) return
        dao.deleteHistoryAttempt(chapterId, row.id)
        mutex.withLock { fillWindowLocked() }
    }

    override suspend fun onCancel(chapterId: Long) {
        val claim = mutex.withLock {
            cancelLocked(chapterId).also { fillWindowLocked() }
        }
        // Revoke is synchronous; cleanup waits outside the engine mutex for actual file users.
        if (claim != null) applicationScope.launch { artifacts.settle(claim) }
    }

    private suspend fun cancelLocked(chapterId: Long): ChapterArtifactClaim? {
        val claim = artifacts.cancel(chapterId, CANCELLED_BY_USER) ?: return null
        transport.cancelChapter(chapterId, claim.token)
        clearChapterCaches(chapterId)
        runCatching { downloadNotifier.clear(chapterId.toInt()) }
        return claim
    }

    override suspend fun cancelARunningChapter(chapterId: Long, mangaId: Long) = onCancel(chapterId)

    override suspend fun cancelAllDownloads() {
        val claims = mutex.withLock {
            dao.observeAllDownloads().first().filter { it.state in WorkSignalRules.ACTIVE_STATES }
                .mapNotNull { cancelLocked(it.chapterId) }
        }
        claims.forEach { claim -> applicationScope.launch { artifacts.settle(claim) } }
    }

    override suspend fun reconcileInterruptedDownloads() {
        mutex.withLock {
            BgDownloadLog.log("reconcile.requested")
            transport.ensureReady()
            pumpLocked("reconcileInterrupted")
        }
    }

    // ---- TransferListener (callbacks from the background session delegate queue) ----

    override fun onPageComplete(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        attemptToken: String,
        page: StagedDownloadPage,
    ) {
        // Enter finally before the first suspension, including an already-cancelled application scope.
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                mutex.withLock {
                    val claim = callbackClaimLocked(mangaId, chapterId, attemptToken) ?: return@withLock
                    artifacts.ownership.producing(claim) {
                        val manifest = cachedManifest(mangaId, chapterId) ?: return@producing
                        if (manifest.pages.none { it.index == pageIndex && !it.policyRejected }) return@producing
                        artifacts.ownership.files(claim) {
                            page.publish(appFileSystem.chapterDir(mangaId, chapterId), pageIndex)
                        } ?: return@producing
                        handlePageCompleteLocked(mangaId, chapterId, pageIndex)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                BgDownloadLog.error(failure, "page.complete.failed", "chapterId" to chapterId)
                recordPageFailure(mangaId, chapterId, pageIndex, attemptToken, failure.message)
            } finally {
                try {
                    page.discard() // Never deletes another attempt's live chapter directory.
                } catch (failure: Exception) {
                    BgDownloadLog.error(failure, "page.staging.retained", "chapterId" to chapterId)
                }
            }
        }
    }

    override fun onPageFailed(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        attemptToken: String,
        message: String?,
    ) {
        applicationScope.launch { recordPageFailure(mangaId, chapterId, pageIndex, attemptToken, message) }
    }

    /** Receiver-side publication failures use the same bounded retry path as native transfer failures. */
    private suspend fun recordPageFailure(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        attemptToken: String,
        message: String?,
    ) {
        try {
            mutex.withLock {
                callbackClaimLocked(mangaId, chapterId, attemptToken) ?: return@withLock
                handlePageFailedLocked(mangaId, chapterId, pageIndex, message)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            BgDownloadLog.error(failure, "page.failed.persistence", "chapterId" to chapterId)
        }
    }

    private suspend fun callbackClaimLocked(mangaId: Long, chapterId: Long, token: String): ChapterArtifactClaim? {
        val claim = artifacts.ownership.currentClaim(chapterId)?.takeIf {
            it.token == token && it.owner.mangaId == mangaId && it.operation == ChapterArtifactOperation.DOWNLOAD
        } ?: return null
        if (currentAttempt(claim) == null) return null
        attempts[chapterId] = claim
        return claim
    }

    private suspend fun claimLocked(entity: ChapterDownloadEntity, admitted: ChapterArtifactClaim? = null): ChapterArtifactClaim? {
        val retained = attempts[entity.chapterId]
        if (admitted == null && retained != null && retained.downloadId == entity.id && currentAttempt(retained) != null) return retained
        val claim = admitted ?: artifacts.claim(entity) ?: return null
        if (retained?.token != claim.token) clearChapterCaches(entity.chapterId)
        attempts[entity.chapterId] = claim
        artifacts.ownership.files(claim) {
            val legacy = manifestStore.read(entity.mangaId, entity.chapterId)
            if (legacy != null && legacy.attemptToken == null && legacy.api == entity.api) {
                // Upgrade owns the legacy on-disk roster, never tokenless URLSession callbacks.
                manifestStore.write(legacy.copy(attemptToken = claim.token))
            }
        }
        return claim
    }

    private suspend fun currentAttempt(claim: ChapterArtifactClaim): ChapterDownloadEntity? {
        val row = dao.getDownloadByChapter(claim.owner.chapterId) ?: return null
        if (row.id != claim.downloadId || row.mangaId != claim.owner.mangaId || row.url != claim.owner.chapterUrl) return null
        return row.takeIf { artifacts.ownership.publish(claim) { true } == true }
    }

    private fun launchOwned(
        claim: ChapterArtifactClaim,
        finished: suspend () -> Unit = {},
        action: suspend () -> Unit,
    ) {
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                artifacts.ownership.producing(claim, action)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                BgDownloadLog.error(failure, "artifact.producer.failed", "chapterId" to claim.owner.chapterId)
            } finally {
                withContext(NonCancellable) {
                    // Runs even when revocation won BEFORE producing admitted the action. Guards
                    // set by the caller must never be left stuck by a skipped/cancelled launch.
                    runCatching { finished() }
                        .onFailure { BgDownloadLog.error(it, "artifact.producer.finishFailed", "chapterId" to claim.owner.chapterId) }
                    runCatching {
                        if (artifacts.ownership.publish(claim) { true } != true) artifacts.settle(claim)
                    }.onFailure { BgDownloadLog.error(it, "artifact.settlement.retained", "chapterId" to claim.owner.chapterId) }
                }
            }
        }
    }

    private fun readManifest(mangaId: Long, chapterId: Long): DownloadManifest? =
        manifestStore.read(mangaId, chapterId)?.takeIf { it.attemptToken != null && it.attemptToken == attempts[chapterId]?.token }

    // ---- locked internals (callers hold [mutex]) ----

    private suspend fun pumpLocked(reason: String) {
        val all = dao.observeAllDownloads().first()
        BgDownloadLog.log(
            "pump.start",
            "reason" to reason,
            "queued" to all.count { it.state == DownloadingState.QUEUED },
            "running" to all.count { it.state == DownloadingState.RUNNING },
            "downloaded" to all.count { it.state == DownloadingState.DOWNLOADED },
        )
        // 1. Catch-up sweep: re-drive DOWNLOADED **or COMPRESSING** chapters (deferred completions, leftovers
        // from a prior session, B1 crash-recovery) when compression is admitted (canCompressNow — foreground
        // settled+healthy, or a background window; NOT during launch/reopen, which is the freeze). Each in its
        // own coroutine (launchFinalize) so the encode never holds the mutex. COMPRESSING is included for B1:
        // a kill mid-CBZ strands the row (the encode runs on applicationScope, not a cancellable BG-task Job);
        // the idempotent finalize re-runs (loose pages still on disk — markReadable kept it readable).
        val finalizePending = all.filter { it.state == DownloadingState.DOWNLOADED || it.state == DownloadingState.COMPRESSING }
        if (canCompressNow()) {
            finalizePending.forEach { launchFinalize(it.chapterId) }
        } else if (finalizePending.isNotEmpty()) {
            // Deferred while foreground only because the app hasn't settled yet (the launch pump runs at
            // didBecomeActive+0s, always inside FOREGROUND_SETTLE): re-pump once at the settle deadline,
            // else leftover DOWNLOADED chapters sit "Finalizing…" all session with nothing to re-drive them.
            scheduleSettleRetryLocked()
        }
        // 2. Reconcile RUNNING chapters from their manifest (resume / re-enqueue missing / detect done|fail).
        // Reuses the step-1 snapshot: everything that mutates RUNNING rows holds [mutex] (the off-mutex
        // finalize coroutines only touch DOWNLOADED/COMPRESSING→SUCCESS), so a second full-table read
        // here could never observe a different RUNNING set.
        all
            .filter { it.state == DownloadingState.RUNNING }
            .forEach { entity ->
                if (claimLocked(entity) == null) return@forEach
                val manifest = readManifest(entity.mangaId, entity.chapterId)
                if (manifest != null) {
                    reconcileChapterLocked(entity, manifest)
                } else {
                    BgDownloadLog.log("manifest.missing", "chapterId" to entity.chapterId, "fallback" to "reResolve")
                    prepareLocked(entity)
                }
            }
        // 3. Prepare QUEUED chapters up to the rolling window.
        fillWindowLocked()
        // 4. Top up the resolve-ahead window (manifests only; no-op when filled/paused/disabled).
        maybePrefetchLocked()
    }

    /**
     * Strict chapter-by-chapter **transfer**: start a QUEUED chapter only while fewer than
     * [CHAPTER_CONCURRENCY] (= 1) chapters are actively TRANSFERRING. The slot is held only by `RUNNING`
     * (scrape + page transfer) — NOT by `DOWNLOADED`/`COMPRESSING`. Once a chapter's pages are all on disk
     * it is marked readable and frees the slot immediately (see [markDownloadedAndMaybeFinalizeLocked]),
     * so the next chapter transfers while the previous one's CBZ is built as decoupled post-processing
     * (compression is CPU; transfers are out-of-process — overlapping them is free, and it stops a
     * CPU-gated CBZ from ever stalling the queue). Still never two chapters transferring at once.
     */
    private suspend fun fillWindowLocked() {
        parentAdmissionWaiter?.cancel()
        parentAdmissionWaiter = null
        // Only an in-flight TRANSFER occupies the slot. A DOWNLOADED (readable, CBZ-pending) or COMPRESSING
        // chapter is post-transfer work that must not block the next transfer. Use an indexed COUNT, not a
        // whole-table scan: bulk "Download all" calls this once per enqueue, and every enqueue after the
        // first sees the slot full and returns on the COUNT alone — no history-inclusive SELECT *, no
        // queued-list fetch (the prior O(N) full scans under the mutex were the bulk-enqueue lag).
        val winMark = TimeSource.Monotonic.markNow() // DLPERF: time inside the engine mutex (incl. resolve)
        val active = dao.countByState(DownloadingState.RUNNING)
        var slots = (CHAPTER_CONCURRENCY - active).coerceAtLeast(0)
        if (slots <= 0) {
            BgDownloadLog.log("window.fill", "active" to active, "freeSlots" to 0, "concurrency" to CHAPTER_CONCURRENCY)
            return
        }
        val queued = dao.getQueuedChapters()
        BgDownloadLog.log(
            "window.fill",
            "active" to active,
            "queued" to queued.size,
            "freeSlots" to slots,
            "concurrency" to CHAPTER_CONCURRENCY,
        )
        val remaining = queued.toMutableList()
        while (slots > 0 && remaining.isNotEmpty()) {
            val admission = artifacts.scanQueued(remaining)
            val attempt = admission.attempt
            if (attempt == null) {
                resumeAfterParentReopen(admission)
                break
            }
            remaining.removeAll { it.id == attempt.chapter.id }
            if (prepareLocked(attempt.chapter, attempt.claim)) slots--
        }
        BgDownloadLog.dlperf("window.ms", "queued" to queued.size, "ms" to winMark.elapsedNow().inWholeMilliseconds)
    }

    /** One cancellable waiter, outside the engine mutex and every producer/file-use scope. */
    private fun resumeAfterParentReopen(admission: QueuedArtifactAdmission) {
        if (admission.parentReopens.isEmpty()) return
        val waiter = applicationScope.launch(start = CoroutineStart.LAZY) {
            try {
                admission.awaitParentReopen()
                val running = coroutineContext[Job]
                mutex.withLock {
                    if (parentAdmissionWaiter !== running) return@withLock
                    parentAdmissionWaiter = null
                    fillWindowLocked()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                BgDownloadLog.error(failure, "admission.resume.failed")
            }
        }
        parentAdmissionWaiter = waiter
        waiter.start()
    }

    private suspend fun prepareLocked(entity: ChapterDownloadEntity, admitted: ChapterArtifactClaim? = null): Boolean {
        val claim = claimLocked(entity, admitted) ?: return false
        if (entity.state == DownloadingState.QUEUED) {
            if (artifacts.ownership.publish(claim) { dao.claimQueuedAsRunning(entity.chapterId) } != 1) {
                BgDownloadLog.log("prepare.claim.raced", "chapterId" to entity.chapterId)
                return false // raced cancel
            }
            BgDownloadLog.log("state.transition", "chapterId" to entity.chapterId, "from" to "QUEUED", "to" to "RUNNING")
        }
        // Resume without re-scraping when a manifest already exists (fast path, runs under [mutex]).
        val existing = readManifest(entity.mangaId, entity.chapterId)
        if (existing != null) {
            BgDownloadLog.log("manifest.read", "chapterId" to entity.chapterId, "pages" to existing.pages.size)
            reconcileChapterLocked(entity, existing)
            return true
        }
        // A resolve-AHEAD scrape for this chapter is already in flight: its completion handles the
        // now-RUNNING row itself (persists the manifest + reconciles → transfers enqueue), and its
        // failure routes through failResolveLocked. Launching a second, real resolve here would just
        // double-scrape the source.
        if (entity.chapterId in prefetching) {
            BgDownloadLog.log("prepare.awaitingPrefetch", "chapterId" to entity.chapterId)
            return true
        }
        // No manifest → the page/link resolution (network scrape) must NOT hold the engine mutex (B6: the
        // 200ms+ scrape under the lock serialized every other operation — page callbacks, new enqueues,
        // lifecycle pumps). The row is already claimed RUNNING, so it holds its concurrency slot and a
        // QUEUED scan won't re-pick it; hand the scrape to a coroutine that re-acquires [mutex] only for
        // the quick manifest write + reconcile. Mirrors launchFinalize's off-mutex heavy-work pattern.
        if (!resolving.add(entity.chapterId)) {
            BgDownloadLog.log("prepare.resolve.alreadyInFlight", "chapterId" to entity.chapterId)
            return true
        }
        launchResolve(entity, claim)
        return true
    }

    /**
     * B6: resolve a chapter's pages (network scrape) OFF [mutex], then re-acquire the lock only for the
     * fast manifest write + reconcile. The caller has already claimed the row RUNNING under the lock and
     * added it to [resolving]; this releases that guard in `finally`.
     */
    private fun launchResolve(entity: ChapterDownloadEntity, claim: ChapterArtifactClaim) {
        launchOwned(claim, finished = {
            mutex.withLock { resolving.remove(entity.chapterId) }
        }) {
            BgDownloadLog.log("prepare.resolve.start", "chapterId" to entity.chapterId)
            val resolveMark = TimeSource.Monotonic.markNow() // DLPERF: page/link resolution (network scrape, off-mutex)
            val resolved =
                try {
                    chapterPageResolver.resolve(entity).also {
                        BgDownloadLog.dlperf(
                            "resolve.ms",
                            "chapterId" to entity.chapterId,
                            "pages" to it.imageUrls.size,
                            "ms" to resolveMark.elapsedNow().inWholeMilliseconds,
                        )
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    BgDownloadLog.error(t, "prepare.resolve.failed", "chapterId" to entity.chapterId)
                    // Stamp a Cloudflare sentinel for a WebView-solvable challenge so the Details VM can
                    // auto-route to the solver and re-enqueue (downloads parity with the reading path).
                    val isChallenge = HeaderRefreshRules.isCloudflareChallengeFailure(t.message)
                    val failMsg = if (isChallenge) CLOUDFLARE_CHALLENGE else (t.message ?: "Resolve failed")
                    if (isChallenge) {
                        BgDownloadLog.log("prepare.resolve.cloudflare", "chapterId" to entity.chapterId)
                    }
                    mutex.withLock { if (currentAttempt(claim) != null) failResolveLocked(entity.chapterId, failMsg) }
                    return@launchOwned
                }
            if (resolved.imageUrls.isEmpty()) {
                BgDownloadLog.warn("prepare.resolve.empty", "chapterId" to entity.chapterId)
                mutex.withLock { if (currentAttempt(claim) != null) failResolveLocked(entity.chapterId, "No images for chapter") }
                return@launchOwned
            }
            mutex.withLock {
                // The row may have been cancelled/deleted during the (slow) network resolve — only
                // persist + enqueue if it is STILL RUNNING (cancel/delete set a non-RUNNING state or
                // remove the row under this same lock, so the two can never interleave mid-write).
                val current = currentAttempt(claim)
                if (current == null || current.state != DownloadingState.RUNNING) {
                    BgDownloadLog.log("prepare.resolve.discarded", "chapterId" to entity.chapterId, "state" to current?.state)
                    return@withLock
                }
                // Prefer a manifest another pass may have written while we resolved (idempotent resume).
                val manifest =
                    readManifest(entity.mangaId, entity.chapterId)
                        ?: buildManifest(entity, resolved).also {
                            if (!persistManifestLocked(it)) return@withLock
                            BgDownloadLog.log("manifest.created", "chapterId" to entity.chapterId, "pages" to it.pages.size)
                        }
                reconcileChapterLocked(current, manifest)
            }
        }
    }

    private fun buildManifest(
        entity: ChapterDownloadEntity,
        resolved: ResolvedChapter,
    ): DownloadManifest =
        DownloadManifest(
            mangaId = entity.mangaId,
            chapterId = entity.chapterId,
            api = entity.api,
            attemptToken = checkNotNull(attempts[entity.chapterId]).token,
            pages =
                resolved.pages.mapIndexed { index, page ->
                    ManifestPage(index = index, url = page.url, headers = page.headers)
                },
        )

    // ---- limited resolve-ahead (owner-approved 2026-07-02) ----

    /**
     * Top up the resolve-ahead window: prefetch the manifest of ONE not-yet-manifested chapter
     * among the next [RESOLVE_AHEAD_WINDOW] queued (processing order), so the brief background wake
     * at chapter completion only needs manifest-read + task-enqueue — not a network scrape — to
     * keep a multi-chapter batch moving (the pre-iOS-26 batch-continuation gap). Selection rules
     * are the pure [ResolveAheadRules] (window cap, one-scrape-at-a-time, no duplicate work); this
     * adds the failure backoff ([prefetchPausedAtMark]) so a struggling source is left alone.
     * Called at reconcile/pump end — never from the enqueue hot path. Transfers stay strictly
     * one-chapter-at-a-time; a prefetched QUEUED chapter gets a manifest, never live transfers.
     */
    private suspend fun maybePrefetchLocked() {
        if (RESOLVE_AHEAD_WINDOW <= 0 || prefetching.isNotEmpty()) return
        prefetchPausedAtMark?.let { paused ->
            if (paused.elapsedNow() < PREFETCH_FAILURE_BACKOFF) return
            prefetchPausedAtMark = null
        }
        val queued = dao.getQueuedChapters()
        if (queued.isEmpty()) return
        val byId = queued.associateBy { it.chapterId }
        val targetId =
            ResolveAheadRules.selectNextPrefetch(
                queuedInProcessingOrder = queued.map { it.chapterId },
                window = RESOLVE_AHEAD_WINDOW,
                resolving = resolving,
                prefetching = prefetching,
                hasManifest = { id -> byId.getValue(id).let { readManifest(it.mangaId, it.chapterId) != null } },
            ) ?: return
        val entity = byId.getValue(targetId)
        val claim = claimLocked(entity) ?: return
        prefetching.add(targetId)
        launchPrefetchResolve(entity, claim)
    }

    /**
     * Resolve a QUEUED chapter's pages ahead of its turn and persist the manifest — OFF [mutex],
     * like [launchResolve]. The row is NOT claimed and NO transfers are enqueued; the chapter stays
     * QUEUED and starts through the normal window fill (which then hits the manifest fast path).
     * If the chapter's turn arrives mid-scrape ([prepareLocked] sees it in [prefetching] and
     * defers), the completion acts as the real resolve: success → persist + reconcile (transfers
     * enqueue); failure → [failResolveLocked]. A failure for a still-QUEUED chapter leaves the row
     * untouched (its real attempt will classify/fail it through the normal path) and pauses
     * prefetching. Header staleness is a non-issue: reconcile/retry overlay FRESH site headers (B3)
     * at enqueue time regardless of when the manifest was written.
     */
    private fun launchPrefetchResolve(entity: ChapterDownloadEntity, claim: ChapterArtifactClaim) {
        launchOwned(claim, finished = {
            mutex.withLock {
                prefetching.remove(entity.chapterId)
                // Chain the next top-up (no-op when admission is closed/paused/disabled).
                runCatching { maybePrefetchLocked() }
            }
        }) {
            // Space chained top-ups; the network scrape must remain outside the engine mutex.
            delay(PREFETCH_SPACING_MS)
            BgDownloadLog.log("prefetch.resolve.start", "chapterId" to entity.chapterId)
            val resolved = resolvePrefetch(entity, claim) ?: return@launchOwned
            mutex.withLock { if (currentAttempt(claim) != null) completePrefetchLocked(entity, resolved) }
        }
    }

    private suspend fun resolvePrefetch(entity: ChapterDownloadEntity, claim: ChapterArtifactClaim): ResolvedChapter? =
        runCatchingCancellable { chapterPageResolver.resolve(entity) }.getOrElse { failure ->
            handlePrefetchFailure(entity, failure, claim)
            null
        }

    private suspend fun handlePrefetchFailure(
        entity: ChapterDownloadEntity,
        failure: Throwable,
        claim: ChapterArtifactClaim,
    ) {
        val isChallenge = HeaderRefreshRules.isCloudflareChallengeFailure(failure.message)
        BgDownloadLog.warn(
            "prefetch.resolve.failed",
            "chapterId" to entity.chapterId,
            "challenge" to isChallenge,
        )
        mutex.withLock {
            pausePrefetchLocked()
            // Turn arrived mid-scrape: this is now the real resolve, so surface its failure.
            if (currentAttempt(claim)?.state == DownloadingState.RUNNING) {
                val message =
                    if (isChallenge) CLOUDFLARE_CHALLENGE else (failure.message ?: "Resolve failed")
                failResolveLocked(entity.chapterId, message)
            }
        }
    }

    private suspend fun completePrefetchLocked(
        entity: ChapterDownloadEntity,
        resolved: ResolvedChapter,
    ) {
        val current = dao.getDownloadByChapter(entity.chapterId)
        when {
            current == null || current.id != entity.id ->
                BgDownloadLog.log("prefetch.discarded", "chapterId" to entity.chapterId, "reason" to "rowGone")
            resolved.imageUrls.isEmpty() -> failEmptyPrefetchLocked(current)
            current.state == DownloadingState.QUEUED -> persistQueuedPrefetchLocked(entity, resolved)
            current.state == DownloadingState.RUNNING -> promotePrefetchLocked(entity, current, resolved)
            else ->
                BgDownloadLog.log("prefetch.discarded", "chapterId" to entity.chapterId, "state" to logState(current.state))
        }
    }

    private suspend fun failEmptyPrefetchLocked(current: ChapterDownloadEntity) {
        BgDownloadLog.warn("prefetch.resolve.empty", "chapterId" to current.chapterId)
        pausePrefetchLocked()
        if (current.state == DownloadingState.RUNNING) {
            failResolveLocked(current.chapterId, "No images for chapter")
        }
    }

    private suspend fun persistQueuedPrefetchLocked(
        entity: ChapterDownloadEntity,
        resolved: ResolvedChapter,
    ) {
        if (readManifest(entity.mangaId, entity.chapterId) == null) {
            val manifest = buildManifest(entity, resolved)
            if (!persistManifestLocked(manifest)) return
            BgDownloadLog.log(
                "prefetch.manifest.written",
                "chapterId" to entity.chapterId,
                "pages" to manifest.pages.size,
            )
        }
    }

    private suspend fun promotePrefetchLocked(
        entity: ChapterDownloadEntity,
        current: ChapterDownloadEntity,
        resolved: ResolvedChapter,
    ) {
        // Turn arrived mid-scrape: only a persisted manifest may reach reconcile/transfer enqueue.
        BgDownloadLog.log("prefetch.promotedToResolve", "chapterId" to entity.chapterId)
        val existing = readManifest(entity.mangaId, entity.chapterId)
        val manifest = existing ?: buildManifest(entity, resolved)
        if (existing == null && !persistManifestLocked(manifest)) return
        reconcileChapterLocked(current, manifest)
    }

    private fun pausePrefetchLocked() {
        prefetchPausedAtMark = TimeSource.Monotonic.markNow()
        BgDownloadLog.warn("prefetch.paused", "forMs" to PREFETCH_FAILURE_BACKOFF.inWholeMilliseconds)
    }

    private suspend fun persistManifestLocked(manifest: DownloadManifest): Boolean {
        return try {
            val claim = attempts[manifest.chapterId]?.takeIf { it.token == manifest.attemptToken }
                ?: return false
            artifacts.ownership.files(claim) { manifestStore.write(manifest) } != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            pausePrefetchLocked()
            failResolveLocked(manifest.chapterId, "Download manifest could not be saved")
            false
        }
    }

    private suspend fun reconcileChapterLocked(
        entity: ChapterDownloadEntity,
        manifest: DownloadManifest,
    ) {
        val claim = attempts[entity.chapterId]?.takeIf { it.token == manifest.attemptToken } ?: return
        if (currentAttempt(claim)?.id != entity.id) return
        manifestCache[entity.chapterId] = manifest
        val onDisk = artifacts.ownership.files(claim) { pagesOnDiskSet(entity.mangaId, entity.chapterId) } ?: return
        // Re-ground the hot-path caches in real disk truth. Reconcile runs on every pump / window fill /
        // relaunch, so this is what keeps the incrementally-maintained onDiskCache honest across
        // force-quit, OS-killed transfers, and resume — the per-page path only ever ADDS to it.
        onDiskCache[entity.chapterId] = onDisk.toMutableSet()
        val inFlight = transport.inFlightPages(entity.chapterId, claim.token)
        val plan = BackgroundReconciler.plan(manifest, onDisk, inFlight, MAX_ATTEMPTS)
        BgDownloadLog.log(
            "reconcile.plan",
            "chapterId" to entity.chapterId,
            "roomState" to logState(entity.state),
            "manifestPages" to manifest.pages.size,
            "onDisk" to onDisk.size,
            "inFlight" to inFlight.size,
            "toEnqueue" to plan.toEnqueue.size,
            "complete" to plan.isComplete,
            "failedPage" to plan.failedPageIndex,
        )
        updateProgressLocked(entity, manifest, onDisk.size)
        when {
            plan.failedPageIndex != null -> {
                val rejected = manifest.pages.any { it.index == plan.failedPageIndex && it.policyRejected }
                failChapterLocked(
                    entity,
                    if (rejected) {
                        "__page_policy_rejected__:ENCODED_OR_NATIVE_POLICY"
                    } else {
                        "Page ${plan.failedPageIndex} failed after $MAX_ATTEMPTS attempts"
                    },
                )
            }
            plan.isComplete ->
                markDownloadedAndMaybeFinalizeLocked(entity.chapterId)
            plan.toEnqueue.isNotEmpty() -> {
                val byIndex = manifest.pages.associateBy { it.index }
                // B3: overlay FRESH cookies/UA from the live store onto the frozen manifest headers (one
                // read per reconcile, not per page) so a WebView re-solve is honored on the next request
                // instead of replaying the cookie baked in at resolve time.
                val live = freshSiteHeaders(manifest.api)
                val requests =
                    plan.toEnqueue.mapNotNull { idx ->
                        val mp = byIndex[idx] ?: return@mapNotNull null
                        val headers = HeaderRefreshRules.overlayFreshHeaders(frozen = mp.headers, fresh = live)
                        TransferRequest(entity.mangaId, entity.chapterId, idx, mp.url, headers, claim.token)
                    }
                BgDownloadLog.log("reconcile.enqueue", "chapterId" to entity.chapterId, "pages" to plan.toEnqueue.size)
                artifacts.ownership.publish(claim) { transport.enqueue(requests) }
            }
            else -> BgDownloadLog.log("reconcile.waitInFlight", "chapterId" to entity.chapterId, "inFlight" to inFlight.size)
        }
        // A chapter is actively transferring and we have CPU right now — the moment resolve-ahead
        // pays for: top up the next queued chapters' manifests (no-op when filled/paused/disabled).
        maybePrefetchLocked()
    }

    private suspend fun handlePageCompleteLocked(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
    ) {
        val entity = dao.getDownloadByChapter(chapterId) ?: return
        if (entity.state == DownloadingState.RUNNING || entity.state == DownloadingState.DOWNLOADED) {
            recordPageCompleteLocked(entity, mangaId, pageIndex)
        } else {
            BgDownloadLog.log("page.complete.ignored", "chapterId" to chapterId, "state" to logState(entity.state))
        }
    }

    private suspend fun recordPageCompleteLocked(
        entity: ChapterDownloadEntity,
        mangaId: Long,
        pageIndex: Int,
    ) {
        val chapterId = entity.chapterId
        val manifest =
            cachedManifest(mangaId, chapterId) ?: run {
                BgDownloadLog.log("manifest.missing", "chapterId" to chapterId, "fallback" to "pump")
                pumpLocked("pageCompleteNoManifest")
                return
            }
        if (entity.mangaId != mangaId || manifest.pages.none { it.index == pageIndex && !it.policyRejected }) {
            return
        }
        // The transport already published the file; update the O(1), idempotent hot-path cache.
        val onDisk = cachedOnDisk(mangaId, chapterId)?.apply { add(pageIndex) } ?: return
        updateProgressLocked(entity, manifest, onDisk.size)
        BgDownloadLog.log(
            "page.complete",
            "chapterId" to chapterId,
            "onDisk" to onDisk.size,
            "total" to manifest.pages.size,
        )
        if (manifest.pages.all { it.index in onDisk }) {
            markDownloadedAndMaybeFinalizeLocked(chapterId)
            fillWindowLocked()
        }
    }

    private suspend fun handlePageFailedLocked(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        message: String?,
    ) {
        val entity = runningFailedPageEntity(mangaId, chapterId, pageIndex) ?: return
        val claim = attempts[chapterId] ?: return
        val attempts =
            try {
                artifacts.ownership.files(claim) { manifestStore.incrementAttempt(
                    mangaId,
                    chapterId,
                    pageIndex,
                    policyRejected = isPagePolicyRejection(message),
                    attemptToken = claim.token,
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not keep retrying on a refusal that could not be persisted. Room's FAILED state
                // stops the chapter; the existing manifest/originals remain for explicit user recovery.
                failChapterLocked(entity, message ?: "Download retry state could not be saved")
                fillWindowLocked()
                null
            } ?: return
        manifestCache.remove(chapterId)
        BgDownloadLog.log(
            "retry.attemptIncremented",
            "chapterId" to chapterId,
            "pageIndex" to pageIndex,
            "attempt" to attempts,
            "max" to MAX_ATTEMPTS,
        )
        when (val decision = TransferRetryRules.decide(attempts, MAX_ATTEMPTS, message)) {
            is TransferRetryRules.Decision.FailChapter -> {
                BgDownloadLog.warn(
                    "retry.exhausted",
                    "chapterId" to chapterId,
                    "pageIndex" to pageIndex,
                    "attempt" to attempts,
                    "challenge" to decision.isChallenge,
                )
                // Transfer-stage challenge (an expired cf_clearance 403ing the image CDN mid-batch)
                // stamps the same Cloudflare sentinel as a resolve-stage challenge, so the Details VM
                // auto-routes to the WebView solver instead of surfacing a dead-end "HTTP 403".
                val failMsg =
                    if (decision.isChallenge) {
                        CLOUDFLARE_CHALLENGE
                    } else {
                        message ?: "Page $pageIndex failed after $MAX_ATTEMPTS attempts"
                    }
                failChapterLocked(entity, failMsg)
                fillWindowLocked()
            }
            // Bounded exponential backoff retry of just this page (outside the lock, after a delay).
            is TransferRetryRules.Decision.Retry -> scheduleRetry(mangaId, chapterId, pageIndex, attempts, decision.delayMs, claim)
        }
    }

    private suspend fun runningFailedPageEntity(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
    ): ChapterDownloadEntity? {
        val entity = dao.getDownloadByChapter(chapterId) ?: return null
        return if (entity.state != DownloadingState.RUNNING) {
            BgDownloadLog.log("page.failed.ignored", "chapterId" to chapterId, "state" to logState(entity.state))
            null
        } else {
            val manifest = cachedManifest(mangaId, chapterId)
            entity.takeIf {
                manifest != null && it.mangaId == mangaId && manifest.pages.any { page -> page.index == pageIndex }
            }
        }
    }

    private fun scheduleRetry(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        attempts: Int,
        delayMs: Long,
        claim: ChapterArtifactClaim,
    ) {
        BgDownloadLog.log(
            "retry.scheduled",
            "chapterId" to chapterId,
            "pageIndex" to pageIndex,
            "attempt" to attempts,
            "delayMs" to delayMs,
        )
        launchOwned(claim) {
            delay(delayMs)
            runCatching { mutex.withLock { retryPageLocked(mangaId, chapterId, pageIndex, claim) } }
        }
    }

    private suspend fun retryPageLocked(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        claim: ChapterArtifactClaim,
    ) {
        val entity = currentAttempt(claim) ?: return
        if (canRetryPageLocked(entity, mangaId, pageIndex)) {
            val manifest = readManifest(mangaId, chapterId) ?: return
            retryManifestPageLocked(entity, manifest, mangaId, pageIndex)
        }
    }

    private suspend fun canRetryPageLocked(
        entity: ChapterDownloadEntity,
        mangaId: Long,
        pageIndex: Int,
    ): Boolean {
        val chapterId = entity.chapterId
        return when {
            entity.state != DownloadingState.RUNNING -> {
                BgDownloadLog.log(
                    "retry.skip.notRunning",
                    "chapterId" to chapterId,
                    "pageIndex" to pageIndex,
                    "state" to logState(entity.state),
                )
                false
            }
            pageOnDisk(mangaId, chapterId, pageIndex) -> {
                BgDownloadLog.log("retry.skip.onDisk", "chapterId" to chapterId, "pageIndex" to pageIndex)
                false
            }
            pageIndex in transport.inFlightPages(chapterId, checkNotNull(attempts[chapterId]).token) -> {
                BgDownloadLog.log("retry.skip.inFlight", "chapterId" to chapterId, "pageIndex" to pageIndex)
                false
            }
            else -> true
        }
    }

    private suspend fun retryManifestPageLocked(
        entity: ChapterDownloadEntity,
        manifest: DownloadManifest,
        mangaId: Long,
        pageIndex: Int,
    ) {
        val claim = attempts[entity.chapterId]?.takeIf { it.token == manifest.attemptToken } ?: return
        if (currentAttempt(claim)?.id != entity.id) return
        val mp = manifest.pages.firstOrNull { it.index == pageIndex } ?: return
        if (mp.policyRejected) {
            failChapterLocked(entity, "__page_policy_rejected__:ENCODED_OR_NATIVE_POLICY")
            fillWindowLocked()
        } else {
            // Retry overlays fresh cookies/UA only after the durable refusal check.
            val live = freshSiteHeaders(manifest.api)
            val headers = HeaderRefreshRules.overlayFreshHeaders(frozen = mp.headers, fresh = live)
            BgDownloadLog.log("retry.enqueue", "chapterId" to entity.chapterId, "pageIndex" to pageIndex)
            artifacts.ownership.publish(claim) {
                transport.enqueue(listOf(TransferRequest(mangaId, entity.chapterId, pageIndex, mp.url, headers, claim.token)))
            }
        }
    }

    private suspend fun markDownloadedAndMaybeFinalizeLocked(chapterId: Long) {
        // First clause (2026-07 audit, same family as ChapterFinalizer's abandon gate): a cancel
        // that landed before this transfer-complete callback owns the row — flipping it to
        // DOWNLOADED would resurrect the cancelled chapter. Short-circuits BEFORE the once-guard so
        // a cancelled chapter is never marked (a later retry may legitimately mark it).
        // Second clause: run the DOWNLOADED-transition work (mark-readable + slot release +
        // finalize kick) ONCE per chapter. Repeat page-complete / reconcile passes for an
        // already-readable (CBZ-pending) chapter previously re-walked the dir + rewrote Room every
        // tick (log spam + redundant I/O). A deferred chapter's CBZ is retried by the pump's
        // finalize sweep, not by re-running this.
        val entity =
            dao
                .getDownloadByChapter(chapterId)
                ?.takeUnless { it.state == DownloadingState.FAILED || chapterId in readableMarked }
                ?: return
        // Check a full CURRENT manifest roster before any readable/state write. Never let a
        // filtered subset (or names alone) become a smaller but apparently complete chapter.
        val claim = attempts[chapterId] ?: return
        if (currentAttempt(claim)?.id != entity.id) return
        val loosePaths = artifacts.ownership.files(claim) { onDiskPagePaths(entity.mangaId, chapterId) } ?: return
        if (loosePaths.isEmpty()) {
            failChapterLocked(entity, "Incomplete or invalid downloaded page roster")
        } else {
            val cbzPending =
                runCatchingCancellable {
                    chapterFinalizer.markReadable(entity, loosePaths, claim)
                }.getOrElse { failure ->
                    failChapterLocked(entity, failure.message ?: "Downloaded pages could not be validated")
                    return
                }
            completeReadableTransitionLocked(chapterId, entity, loosePaths, cbzPending)
        }
    }

    private suspend fun completeReadableTransitionLocked(
        chapterId: Long,
        entity: ChapterDownloadEntity,
        loosePaths: List<String>,
        cbzPending: Boolean,
    ) {
        // Validation/bookkeeping can suspend: cancellation or deletion may now own the row.
        val claim = attempts[chapterId] ?: return
        val current = currentAttempt(claim) ?: return
        if (current.id != entity.id) return
        if (current.state != DownloadingState.RUNNING && current.state != DownloadingState.DOWNLOADED) return
        readableMarked.add(chapterId)
        val wasRunning = entity.state == DownloadingState.RUNNING
        artifacts.ownership.publish(claim) { dao.updateStateChId(chapterId, DownloadingState.DOWNLOADED) }
        if (wasRunning) {
            BgDownloadLog.log("state.transition", "chapterId" to chapterId, "from" to "RUNNING", "to" to "DOWNLOADED")
        }

        // All pages are on disk. Make the chapter READABLE from its loose pages RIGHT NOW (cheap DB writes,
        // no CPU window needed — sets isDownloaded + localImagePaths so the reader opens it offline) and
        // RELEASE the queue slot. The queue advances on "pages transferred", never on "CBZ built": a
        // CPU-gated CBZ must never hold the queue hostage when iOS grants no background window.
        BgDownloadLog.log(
            "downloaded.readable",
            "chapterId" to chapterId,
            "pages" to loosePaths.size,
            "cbzPending" to cbzPending,
        )

        // SILENT "finalizing"/"paused" update (readable, still being packaged) — the alerting "complete"
        // fires at finalize.success (see launchFinalize), so "complete" still means the durable CBZ is ready.
        // Posted once (the readableMarked guard above ensures this whole block runs once per chapter). When the
        // CBZ is deferred SPECIFICALLY by Low Power Mode (user opt-out), post the settled "paused (Low Power
        // Mode)" notice instead of "Finalizing…" — that defer can last the whole session, so it must never look
        // like an endless load. (A thermal defer is transient — the device cools — and keeps "Finalizing…".)
        val lowPowerDeferred =
            cbzPending &&
                !canCompressNow() &&
                CompressionGateRules.isLowPowerDeferred(
                    thermallyStressed = workSignal.thermallyStressed.value,
                    lowPowerMode = workSignal.lowPowerMode.value,
                    allowLowPowerCompression = allowLowPowerCompression,
                )
        if (lowPowerDeferred) {
            BgDownloadLog.log("notif.finalizeDeferred.posted", "chapterId" to chapterId, "reason" to "lowPowerMode")
            runCatching { downloadNotifier.onFinalizeDeferred(chapterId.toInt(), notifTitle(entity)) }
        } else {
            BgDownloadLog.log("notif.finalizing.posted", "chapterId" to chapterId)
            runCatching { downloadNotifier.onFinalizing(chapterId.toInt(), notifTitle(entity)) }
        }

        // Free the slot NOW (DOWNLOADED no longer occupies it) so the next QUEUED chapter starts transferring
        // — independent of whether this chapter's CBZ can be built yet.
        fillWindowLocked()

        // CBZ archiving is decoupled post-processing (off the mutex, doesn't hold the slot). This is the
        // JUST-COMPLETED path → foreground compression is allowed (canFinalizeOnCompletion), so DOWNLOADED
        // stays transient and the UI never shows a stuck "finishing" row. (The launch-freeze risk is the
        // pre-existing-chapter SWEEP in pumpLocked, which stays background-only.)
        when {
            // CBZ off → the loose pages ARE the final artifact; reach terminal SUCCESS cheaply (no Skia).
            !cbzPending -> launchFinalize(chapterId)
            // CBZ on + compression admitted now (foreground settled+healthy, or a background window) → archive.
            canCompressNow() -> launchFinalize(chapterId)
            // No execution window at all (suspended, no BG-task) → leave it readable + pending; the
            // background sweep / next completion / manual Yami Compressor finishes it. Slot already free.
            // If the deferral is only the foreground settle window, arm the one-shot settle retry so a
            // chapter finishing seconds after a reopen doesn't stay "Finalizing…" for the whole session.
            else -> {
                BgDownloadLog.log(
                    "finalize.deferred",
                    "chapterId" to chapterId,
                    "reason" to "noExecutionWindow",
                    "readable" to true,
                )
                scheduleSettleRetryLocked()
            }
        }
    }

    /**
     * Admission control for the heavy CBZ encode — one gate for BOTH the just-completed path
     * ([markDownloadedAndMaybeFinalizeLocked]) and the catch-up sweep ([pumpLocked]). Pure decision in
     * [CompressionGateRules.canCompress]; this only feeds it the live inputs.
     *
     * - **Foreground** (`appActive`): allowed ONLY once the app is *settled* ([appSettled] — foreground for
     *   [FOREGROUND_SETTLE], so the encode never collides with launch/reopen warm-up: Compose first frame,
     *   Coil/Room init, a churning heap → K/N stop-the-world GC = the confirmed freeze) AND the device is not
     *   deferring: thermally serious/critical ALWAYS defers, and Low Power Mode defers UNLESS the user opted
     *   in ([allowLowPowerCompression]).
     * - **Background**: allowed only inside a real OS-granted window ([BackgroundWorkSignal.backgroundProcessingActive]);
     *   there's no UI to jank, so it runs full speed.
     *
     * When this returns false the chapter stays `DOWNLOADED` (readable from loose pages), NEVER a stuck
     * `COMPRESSING` (the row only flips to COMPRESSING inside `finalize()`, which runs only when this passed).
     * A deferred chapter compresses when the gate clears (the compression-gate watcher in `init` re-drives on
     * Low-Power-off / opt-in / thermal-cooled), on the next completion, the next background window, or a manual
     * Yami Compressor run. (`appActive` is false during background execution — didBecomeActive only fires
     * foreground — so a continued task that crosses into the background compresses there.)
     */
    private fun canCompressNow(): Boolean =
        CompressionGateRules.canCompress(
            appActive = appActive,
            appSettled = appSettled(),
            thermallyStressed = workSignal.thermallyStressed.value,
            lowPowerMode = workSignal.lowPowerMode.value,
            allowLowPowerCompression = allowLowPowerCompression,
            backgroundWindowActive = workSignal.backgroundProcessingActive,
        )

    /** True once the app has been foreground for at least [FOREGROUND_SETTLE] (launch/reopen warm-up done). */
    private fun appSettled(): Boolean = foregroundedAtMark?.let { it.elapsedNow() >= FOREGROUND_SETTLE } ?: false

    /** One settle-retry pump armed at a time. (Guarded by [mutex].) */
    private var settleRetryScheduled = false

    /**
     * One-shot re-pump at the [FOREGROUND_SETTLE] deadline, armed when finalize work was deferred while
     * the app is foreground but not yet settled. Without it a chapter that finishes (or a leftover
     * DOWNLOADED row found by the launch pump) inside the settle window stays readable-but-"Finalizing…"
     * for the whole session — no later event re-drives the sweep while the user stays foreground.
     * Deliberately NOT armed for the stress deferral (thermal/Low Power Mode has no deadline — that
     * re-kick is owned by the compression-gate watcher in `init`, which re-drives finalize when the
     * deferral clears) or in background (the BG-task window path owns that), so this can never poll in a loop.
     */
    private fun scheduleSettleRetryLocked() {
        if (settleRetryScheduled || !appActive) return
        val remaining = foregroundedAtMark?.let { FOREGROUND_SETTLE - it.elapsedNow() } ?: return
        if (remaining.isNegative()) return // already settled → the deferral was stress/background, not settle
        settleRetryScheduled = true
        BgDownloadLog.log("finalize.settleRetry.armed", "inMs" to remaining.inWholeMilliseconds)
        applicationScope.launch {
            delay(remaining + SETTLE_RETRY_SLACK)
            runCatching {
                mutex.withLock {
                    settleRetryScheduled = false
                    pumpLocked("settleRetry")
                }
            }
        }
    }

    /**
     * Finalize (CBZ + bookkeeping) in its OWN coroutine — **never holding [mutex]** — so the heavy Skia
     * encode can't stall page-completion processing or pumps. (A CBZ that froze mid-encode while the app
     * suspended previously held the lock for the entire background window, blocking everything.)
     * [finalizing] guards against a double-finalize of the same chapter; [finalizeSemaphore] serializes
     * encodes across chapters. On success it posts the alerting **completion** notification — the durable
     * CBZ being ready is the user-facing "complete", NOT the earlier transfer-complete (which only posted
     * the silent "finalizing" update). A deferred / failed / expired finalize therefore never shows
     * "complete"; the chapter stays DOWNLOADED and re-finalizes on the next window/foreground.
     */
    private suspend fun launchFinalize(chapterId: Long) {
        // Called under the engine mutex: capture once, before semaphore waits or native work.
        if (chapterId in finalizing) return
        val entity = dao.getDownloadByChapter(chapterId) ?: return
        if (!FinalizeRules.canStartFinalize(entity.state)) return
        val claim = claimLocked(entity) ?: return
        finalizing.add(chapterId)
        launchOwned(claim, finished = {
            mutex.withLock {
                finalizing.remove(chapterId)
                clearChapterCaches(chapterId)
                fillWindowLocked()
            }
        }) {
            try {
                val (paths, artifact) = artifacts.ownership.files(claim) {
                    val paths = onDiskPagePaths(entity.mangaId, chapterId, claim.token)
                    paths to FinalizeRules.selectArtifact(
                        loosePagesPresent = paths.isNotEmpty(),
                        existingCbzPath = {
                            // Only this manifest-bound interrupted encode may adopt a canonical CBZ.
                            if (entity.state == DownloadingState.COMPRESSING &&
                                manifestStore.read(entity.mangaId, chapterId)?.attemptToken == claim.token
                            ) existingCbzPath(entity.mangaId, chapterId) else null
                        },
                    )
                } ?: return@launchOwned
                when (artifact) {
                    FinalizeRules.Artifact.Missing -> {
                        mutex.withLock { failChapterLocked(entity, "No pages on disk to finalize") }
                        return@launchOwned
                    }
                    is FinalizeRules.Artifact.AdoptCbz ->
                        chapterFinalizer.adoptExistingArchive(entity, artifact.cbzPath, claim)
                    FinalizeRules.Artifact.LoosePages -> {
                        val finalizeMark = TimeSource.Monotonic.markNow()
                        finalizeSemaphore.withPermit { chapterFinalizer.finalize(entity, paths, claim) }
                        BgDownloadLog.dlperf("finalize.ms", "chapterId" to chapterId, "ms" to finalizeMark.elapsedNow().inWholeMilliseconds)
                    }
                }
                // Completion remains alerting only after the original ledger actually committed.
                val row = dao.getDownloadByChapter(chapterId)
                if (row?.id == claim.downloadId && row.state == DownloadingState.SUCCESS) {
                    runCatching { downloadNotifier.onComplete(chapterId.toInt(), notifTitle(entity)) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                BgDownloadLog.error(failure, "finalize.failed", "chapterId" to chapterId)
                val current = currentAttempt(claim)
                if (current != null) {
                    val present = runCatchingCancellable {
                        artifacts.ownership.files(claim) {
                            onDiskPagePaths(entity.mangaId, chapterId, claim.token).isNotEmpty() ||
                                existingCbzPath(entity.mangaId, chapterId)?.let { path ->
                                    inspectPageArchive(appFileSystem.fileSystem(), path.toPath(), mediaInspector, pageBytePolicy) > 0
                                } == true
                        } == true
                    }.getOrDefault(false)
                    when (FinalizeRules.classifyFinalizeFailure(current.state, present)) {
                        FinalizeRules.FailureAction.FAIL -> mutex.withLock {
                            failChapterLocked(entity, failure.message ?: "Finalize failed")
                        }
                        // A verified complete roster remains readable and retains custody for retry.
                        FinalizeRules.FailureAction.KEEP_READABLE ->
                            BgDownloadLog.warn("finalize.failed.keepReadable", "chapterId" to chapterId)
                        else -> Unit
                    }
                }
            }
            // launchOwned releases the guards and settles after the actual producer unwinds.
        }
    }

    /**
     * Terminal handling for a failed page/link RESOLVE. The old path was a bare `dao.updateFailure`,
     * which left two gaps:
     *  - **queue stall**: the failed chapter had claimed the single transfer slot as RUNNING; without a
     *    [fillWindowLocked] the next QUEUED chapter never started until an unrelated pump (an app
     *    background/foreground cycle) — a resolve failure on chapter 1 of a batch stranded the rest.
     *  - **no terminal signal**: no FAILED notification was posted and the chapter's caches leaked
     *    (the legacy engine posts a terminal notification for every job outcome via NotifierRules).
     * Re-checks the row is still RUNNING first: a cancel/delete that landed during the (slow, off-mutex)
     * resolve has already written its own terminal state + cleared the notification — overwriting it
     * here would clobber the cancel sentinel and post a spurious "failed" banner.
     */
    private suspend fun failResolveLocked(
        chapterId: Long,
        message: String,
    ) {
        val current = dao.getDownloadByChapter(chapterId)
        if (current == null || current.state != DownloadingState.RUNNING) {
            BgDownloadLog.log("prepare.resolve.failDiscarded", "chapterId" to chapterId, "state" to logState(current?.state))
            return
        }
        failChapterLocked(current, message)
        fillWindowLocked()
    }

    private suspend fun failChapterLocked(entity: ChapterDownloadEntity, message: String) {
        val claim = attempts[entity.chapterId] ?: return
        if (currentAttempt(claim)?.id != entity.id) return
        if (!artifacts.fail(claim, message)) return
        artifacts.ownership.revoke(claim)
        transport.cancelChapter(entity.chapterId, claim.token)
        clearChapterCaches(entity.chapterId)
        runCatching { downloadNotifier.onFailed(entity.chapterId.toInt(), notifTitle(entity)) }
        applicationScope.launch { artifacts.settle(claim) }
    }

    private suspend fun updateProgressLocked(
        entity: ChapterDownloadEntity,
        manifest: DownloadManifest,
        onDiskCount: Int,
    ) {
        val total = manifest.pages.size
        if (total <= 0) return
        val percent = ((onDiskCount * 100) / total).coerceIn(0, 100)
        // Throttle to percent-change. Page completion fires per-page (hundreds of times), and reconcile
        // re-posts on every pump; without this guard a 360-page chapter wrote Room + posted a system
        // notification 360+ times (plus dozens of identical 100% reposts at the tail). The progress bar
        // (1% granularity) and the notification are unaffected — they only ever moved per-percent anyway.
        if (lastPostedPercent[entity.chapterId] == percent) return
        lastPostedPercent[entity.chapterId] = percent
        dao.updateProgressForArtifact(entity.chapterId, entity.id, checkNotNull(attempts[entity.chapterId]).token, percent)
        BgDownloadLog.log(
            "notif.progress.posted",
            "chapterId" to entity.chapterId,
            "current" to onDiskCount,
            "total" to total,
            "percent" to percent,
        )
        runCatching { downloadNotifier.onProgress(entity.chapterId.toInt(), notifTitle(entity), onDiskCount, total) }
    }

    // ---- hot-path caches ----

    /** Manifest for [chapterId] from the in-memory cache, falling back to disk (and populating) on a miss. */
    private fun cachedManifest(
        mangaId: Long,
        chapterId: Long,
    ): DownloadManifest? =
        manifestCache[chapterId]
            ?: readManifest(mangaId, chapterId)?.also { manifestCache[chapterId] = it }

    /** Mutable on-disk page-index set for [chapterId], seeded from the real directory on first access. */
    private suspend fun cachedOnDisk(
        mangaId: Long,
        chapterId: Long,
    ): MutableSet<Int>? {
        onDiskCache[chapterId]?.let { return it }
        val claim = attempts[chapterId] ?: return null
        val pages = artifacts.ownership.files(claim) { pagesOnDiskSet(mangaId, chapterId).toMutableSet() } ?: return null
        onDiskCache[chapterId] = pages
        return pages
    }

    /** Drop a chapter's hot-path caches — call when it leaves the active set (success/fail/cancel/re-enqueue). */
    private fun clearChapterCaches(chapterId: Long) {
        manifestCache.remove(chapterId)
        onDiskCache.remove(chapterId)
        lastPostedPercent.remove(chapterId)
        readableMarked.remove(chapterId)
    }

    // ---- on-disk helpers ----

    private fun pagesOnDiskSet(
        mangaId: Long,
        chapterId: Long,
    ): Set<Int> {
        val manifest = cachedManifest(mangaId, chapterId) ?: return emptySet()
        return inspectPageRoster(
            appFileSystem.fileSystem(),
            appFileSystem.chapterDir(mangaId, chapterId),
            manifest,
            mediaInspector,
            pageBytePolicy,
        ).indices
    }

    private fun onDiskPagePaths(
        mangaId: Long,
        chapterId: Long,
        attemptToken: String? = attempts[chapterId]?.token,
    ): List<String> {
        // Finalize also calls this off the engine mutex: use the atomic durable snapshot, not the
        // mutable hot-path cache, and require the then-current manifest's complete roster.
        val manifest = manifestStore.read(mangaId, chapterId)
            ?.takeIf { attemptToken != null && it.attemptToken == attemptToken } ?: return emptyList()
        return inspectPageRoster(
            appFileSystem.fileSystem(),
            appFileSystem.chapterDir(mangaId, chapterId),
            manifest,
            mediaInspector,
            pageBytePolicy,
        ).completePaths
            .orEmpty()
    }

    private suspend fun pageOnDisk(
        mangaId: Long,
        chapterId: Long,
        index: Int,
    ): Boolean {
        val claim = attempts[chapterId] ?: return false
        return artifacts.ownership.files(claim) { index in pagesOnDiskSet(mangaId, chapterId) } == true
    }

    /** The finalized `.cbz` path as a string if it exists, else null (B2-durable adopt-recovery). */
    private fun existingCbzPath(
        mangaId: Long,
        chapterId: Long,
    ): String? =
        (appFileSystem.chapterDir(mangaId, chapterId) / "chapter_$chapterId.cbz")
            .takeIf { appFileSystem.fileSystem().exists(it) }
            ?.toString()

    // Page-name parsing (`image_<n>.<ext>` → n) moved to the pure commonMain [PageFileNames]
    // (test hardening — the parsed index decides finalize page order + reconcile membership).

    private suspend fun freshSiteHeaders(api: String?): Map<String, String> {
        val a = api?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching { dataStoreHelper.getHeadersForApi(a) }.getOrNull().orEmpty()
    }

    private fun notifTitle(entity: ChapterDownloadEntity): String {
        val base = entity.mangaTitle?.takeIf { it.isNotBlank() } ?: "Download"
        return "$base - Ch ${entity.number}"
    }

    // Logging-only tokens; the platform boundary never renders arbitrary downstream enum objects.
    private fun logState(state: DownloadingState?): String =
        when (state) {
            DownloadingState.QUEUED -> "QUEUED"
            DownloadingState.RUNNING -> "RUNNING"
            DownloadingState.DOWNLOADED -> "DOWNLOADED"
            DownloadingState.COMPRESSING -> "COMPRESSING"
            DownloadingState.SUCCESS -> "SUCCESS"
            DownloadingState.FAILED -> "FAILED"
            null -> "ABSENT"
        }

    // Retry-vs-fail + backoff policy moved to the pure commonMain [TransferRetryRules] (test
    // hardening — the decision also stamps the Cloudflare sentinel on a challenge-class exhaustion).

    private companion object {
        const val CANCELLED_BY_USER = "__cancelled_by_user__"

        /** Mirrors [me.manga.kira.domain.model.downloads.DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL].
         *  Written into `errorMsg` when a resolve fails on a Cloudflare/anti-bot challenge so the Details
         *  VM can auto-route to the WebView solver. Kept as a local literal (no :domain dep), in sync with
         *  the domain const exactly like [CANCELLED_BY_USER]. */
        const val CLOUDFLARE_CHALLENGE = "__cloudflare_challenge__"

        /** Chapters allowed in the active lifecycle (RUNNING/DOWNLOADED/COMPRESSING) at once. Strict 1 =
         *  finish transfer + finalize + CBZ for one chapter before the next starts. Page-level concurrency
         *  inside a chapter is separate (the background session's HTTPMaximumConnectionsPerHost). */
        const val CHAPTER_CONCURRENCY = 1
        const val MAX_ATTEMPTS = 3

        /** Resolve-ahead window: manifests are prefetched for at most this many queued chapters
         *  (processing order) beyond the transferring one. 0 disables resolve-ahead entirely
         *  (instant rollback). Deliberately small — lookahead must never fan out a whole batch
         *  (scrapes stay serialized + spaced + pause-on-failure regardless of the window size).
         *  Owner-tuned 3 → 6 (2026-07-02) for longer background batch continuation. */
        const val RESOLVE_AHEAD_WINDOW = 6

        /** Minimum gap before each prefetch scrape — chained top-ups stay sequential AND spaced. */
        const val PREFETCH_SPACING_MS = 500L

        /** How long prefetching stays paused after ANY prefetch failure (Cloudflare/403/429/…):
         *  a struggling source is left alone; real (in-turn) resolves are unaffected. */
        val PREFETCH_FAILURE_BACKOFF = 10.minutes

        /** Foreground compression is admitted only after the app has been active this long — long enough to
         *  clear the launch/reopen warm-up (Compose first frame, Coil/Room init) that made the encode freeze. */
        val FOREGROUND_SETTLE = 6.seconds

        /** Settle-retry pump fires this much past the [FOREGROUND_SETTLE] deadline so [appSettled] is
         *  unambiguously true when it re-checks (monotonic-clock slack). */
        val SETTLE_RETRY_SLACK = 500.milliseconds
        // ACTIVE_STATES moved to the pure commonMain [WorkSignalRules] (B8 test hardening) —
        // single source for the signal computation, the enqueue dedup, and the cancel-all snapshot.
    }
}
