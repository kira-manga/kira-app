package me.manga.kira.presentation.features.download.domain.clean

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import me.manga.kira.presentation.features.download.data.DownloadingState
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

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
    private val dao: ChapterDownloadDao,
    private val chapterPageResolver: ChapterPageResolver,
    private val chapterFinalizer: ChapterFinalizer,
    private val manifestStore: DownloadManifestStore,
    private val appFileSystem: AppFileSystem,
    private val transport: BackgroundTransport,
    private val applicationScope: CoroutineScope,
    private val downloadNotifier: DownloadNotifier,
    // B3: the live per-source header store (cf_clearance/Cookie/User-Agent), the same one a WebView
    // re-solve writes to via saveHeadersForApi. Used to refresh the frozen manifest headers at
    // reconcile/retry so an expired cookie baked in at resolve time is not replayed (→ 403).
    private val dataStoreHelper: DataStoreHelper,
    // M4: requests OS background CPU windows (BGProcessingTask / BGContinuedProcessingTask via the
    // host) and a snapshot the host reads synchronously to drive BG-task submission + progress UI.
    private val backgroundScheduler: BackgroundScheduler,
    private val workSignal: BackgroundWorkSignal,
) : DownloadRepository, TransferListener {

    private val mutex = Mutex()

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
            }.onFailure { BgDownloadLog.error(it, "startup.pumpFailed", "msg" to it.message) }
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
                BgDownloadLog.log("signal.update", "pending" to s.pending, "progress" to s.progressPercent, "activeChapters" to s.activeCount, "lead" to s.leadChapterId)
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
                        .onFailure { BgDownloadLog.error(it, "compressionGate.pumpFailed", "msg" to it.message) }
                }
                wasDeferred = deferred
            }
        }
    }

    // ---- DownloadRepository: observable stream ----

    override fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>> = dao.observeAllDownloads()

    // ---- DownloadRepository: mutating ops ----

    override suspend fun enqueueChapterDownload(chapter: SavedChapterEntity, title: String, mangaApi: String) = withContext(Dispatchers.Default) {
        // Run the enqueue OFF the caller's thread. The details-screen Download button invokes this from
        // viewModelScope (MAIN); the body does a ~350–700 ms network page-link RESOLVE (scrape) plus
        // DB/manifest/task-enqueue work, which previously ran ON MAIN and froze the UI on tap (confirmed
        // via DLPERF logs: enqueue.mutexHeldMs ~700 ms on the main thread). Android never lagged because
        // its engine defers this to a WorkManager Worker. Dispatchers.Default is the iOS IO-equivalent
        // (Dispatchers.IO is unavailable on Kotlin/Native; matches the project's IoDispatcher choice).
        val dlperfMark = TimeSource.Monotonic.markNow() // DLPERF: total time the enqueue holds the engine mutex
        mutex.withLock {
            val existing = dao.getDownloadByChapter(chapter.id)?.state
            BgDownloadLog.log("enqueue.request", "chapterId" to chapter.id, "mangaId" to chapter.mangaId, "api" to mangaApi, "existingState" to existing)
            if (existing in WorkSignalRules.ACTIVE_STATES) {
                BgDownloadLog.log("enqueue.skip.alreadyActive", "chapterId" to chapter.id, "state" to existing)
                return@withLock
            }
            // Retry of a FAILED|SUCCESS row: drop any stale manifest so attempt counts reset (pages already
            // on disk are still skipped by the reconciler — an efficient resume). A brand-new chapter
            // (existing == null) has no prior row, hence no manifest/caches — skip the FS stat + map ops so
            // a bulk "Download all" of fresh chapters doesn't do N wasted syscalls under the mutex.
            if (existing != null) {
                manifestStore.delete(chapter.mangaId, chapter.id)
                clearChapterCaches(chapter.id)
            }
            val id = dao.insert(chapter.toChapterDownloadEntity(apiName = mangaApi, title = title))
            BgDownloadLog.log("enqueue.inserted", "chapterId" to chapter.id, "rowId" to id, "state" to "QUEUED")
            if (id >= 0L) {
                transport.ensureReady()
                fillWindowLocked()
            }
        }
        BgDownloadLog.dlperf("enqueue.mutexHeldMs", "chapterId" to chapter.id, "ms" to dlperfMark.elapsedNow().inWholeMilliseconds)
    }

    override suspend fun deleteDownload(chapterId: Long) {
        mutex.withLock {
            BgDownloadLog.log("cancel.delete", "chapterId" to chapterId)
            transport.cancelChapter(chapterId)
            val mangaId = dao.getDownloadByChapter(chapterId)?.mangaId
            dao.deleteByChapterId(chapterId)
            if (mangaId != null) manifestStore.delete(mangaId, chapterId)
            clearChapterCaches(chapterId)
            // B5: drop any lingering progress / "Finalizing…" notification — after delete nothing else
            // will (transport callbacks short-circuit on the gone row), so it would sit in the shade forever.
            runCatching { downloadNotifier.clear(chapterId.toInt()) }
            // The deleted row may have held the single transfer slot — start the next QUEUED chapter now
            // (without this the queue stalled until an unrelated pump; cancelARunningChapter already kicks).
            fillWindowLocked()
        }
    }

    override suspend fun onCancel(chapterId: Long) {
        mutex.withLock {
            BgDownloadLog.log("cancel.onCancel", "chapterId" to chapterId, "transition" to "->FAILED(cancelled)")
            transport.cancelChapter(chapterId)
            val row = dao.getDownloadByChapter(chapterId)
            applyCancelCleanupLocked(row)
            dao.updateFailure(chapterId, CANCELLED_BY_USER)
            if (row != null) manifestStore.delete(row.mangaId, chapterId)
            clearChapterCaches(chapterId)
            runCatching { downloadNotifier.clear(chapterId.toInt()) } // B5: clear stale progress/finalizing entry
            // The cancelled row may have held the single transfer slot — start the next QUEUED chapter now
            // (without this the queue stalled until an unrelated pump; cancelARunningChapter already kicks).
            fillWindowLocked()
        }
    }

    /**
     * Shared cancel-time cleanup for one row ([FinalizeRules.cancelCleanup] decides), used by
     * [onCancel] and [cancelAllDownloads]:
     *  - QUEUED/RUNNING → delete THIS cycle's partially-downloaded page files (+ manifest via
     *    [deletePartialPageFiles]) — they used to linger as orphans (mobile hardening 2026-07-04);
     *    any published `.cbz` from a previous completed download is deliberately kept.
     *  - DOWNLOADED/COMPRESSING → revert the readable bookkeeping NOW (2026-07-04 device smoke);
     *    files are deleted here too unless an in-flight encode owns them (the post-encode cleanup
     *    finishes then).
     * ([cancelARunningChapter] keeps its historical whole-dir flow for RUNNING plus the same
     * finalize-window handling.) Best-effort throughout: cleanup must never abort the cancel.
     */
    private suspend fun applyCancelCleanupLocked(row: ChapterDownloadEntity?) {
        row ?: return
        when (FinalizeRules.cancelCleanup(row.state, encodeInFlight = row.chapterId in finalizing)) {
            FinalizeRules.CancelCleanup.NONE -> Unit
            FinalizeRules.CancelCleanup.DELETE_PARTIAL_PAGES -> {
                BgDownloadLog.log("cancel.deletePartialPages", "chapterId" to row.chapterId, "state" to row.state)
                deletePartialPageFiles(row.mangaId, row.chapterId)
            }
            FinalizeRules.CancelCleanup.REVERT_ONLY -> {
                BgDownloadLog.log("cancel.revertReadable", "chapterId" to row.chapterId, "files" to "encodeOwns")
                runCatching { chapterFinalizer.revertReadable(row.chapterId) }
            }
            FinalizeRules.CancelCleanup.REVERT_AND_DELETE_FILES -> {
                BgDownloadLog.log("cancel.revertReadable", "chapterId" to row.chapterId, "files" to "deleted")
                runCatching { chapterFinalizer.revertReadable(row.chapterId) }
                deleteChapterFiles(row.mangaId, row.chapterId)
            }
        }
    }

    override suspend fun cancelARunningChapter(chapterId: Long, mangaId: Long) {
        mutex.withLock {
            BgDownloadLog.log("cancel.running", "chapterId" to chapterId, "mangaId" to mangaId, "transition" to "->FAILED(cancelled)")
            transport.cancelChapter(chapterId)
            revertReadableIfCancelledInFinalizeWindowLocked(chapterId)
            if (chapterId in finalizing) {
                // An off-mutex finalize encode is reading this chapter's files RIGHT NOW. Deleting
                // them under it makes IosCbzWriter warn-skip the vanished sources — a page-short
                // archive could publish and be marked SUCCESS (a silently partial chapter). Leave the
                // files to the encode; launchFinalize's post-encode cleanup deletes them (and the
                // manifest) the moment the encode is done with them — the row flips FAILED below, so
                // the abandon gate stops the terminal writes either way (2026-07-04 device smoke).
                BgDownloadLog.log("cancel.running.filesKept", "chapterId" to chapterId, "reason" to "finalizeInFlight")
            } else {
                deleteChapterFiles(mangaId, chapterId) // removes pages + manifest (whole chapter dir)
            }
            clearChapterCaches(chapterId)
            dao.updateFailure(chapterId, CANCELLED_BY_USER)
            runCatching { downloadNotifier.clear(chapterId.toInt()) } // B5: clear stale progress/finalizing entry
            fillWindowLocked()
        }
    }

    /**
     * 2026-07-04 device smoke: a cancel that lands during the finalize window (row DOWNLOADED /
     * COMPRESSING) hits a chapter that was ALREADY marked readable at transfer-complete
     * ([ChapterFinalizer.markReadable]) — flipping the queue row FAILED is not enough, the chapter
     * stayed "Downloaded" and opened as complete. Revert that bookkeeping here, gated on
     * [FinalizeRules.cancelMustRevertReadable] so a cancel of a QUEUED/RUNNING retry never clears
     * a previous successful download's bookkeeping. Best-effort: a failed revert must not abort
     * the rest of the cancel.
     */
    private suspend fun revertReadableIfCancelledInFinalizeWindowLocked(chapterId: Long) {
        val state = runCatching { dao.getDownloadByChapter(chapterId)?.state }.getOrNull()
        if (!FinalizeRules.cancelMustRevertReadable(state)) return
        BgDownloadLog.log("cancel.revertReadable", "chapterId" to chapterId, "state" to state)
        runCatching { chapterFinalizer.revertReadable(chapterId) }
            .onFailure { BgDownloadLog.error(it, "cancel.revertReadable.failed", "chapterId" to chapterId) }
    }

    override suspend fun cancelAllDownloads() {
        mutex.withLock {
            BgDownloadLog.log("cancel.all")
            // Snapshot active rows BEFORE marking them failed, so we can clear each one's lingering
            // progress / "Finalizing…" notification (B5) and run the finalize-window cleanup below.
            // Cancel-all is a rare user action, so the one full read here is fine.
            val activeRows =
                dao.observeAllDownloads()
                    .first()
                    .filter { it.state in WorkSignalRules.ACTIVE_STATES }
            transport.cancelAll()
            // Per-row cancel-time cleanup (shared with onCancel — see applyCancelCleanupLocked):
            // finalize-window rows revert their readable bookkeeping NOW, synchronously with the
            // FAILED flip below (2026-07-04 device smoke round 2: this path used to leave the
            // revert to the post-encode cleanup, so the chapter showed "Downloaded" for the whole
            // remaining encode after the user pressed Cancel), and QUEUED/RUNNING rows drop their
            // partially-downloaded pages (mobile hardening item 1 — they used to linger as
            // orphans). Files under an in-flight encode are left to the post-encode cleanup.
            // (This replaces the earlier keep-readable cancel-all posture for DOWNLOADED rows: a
            // row FAILED("cancelled") while the chapter still read as Downloaded was a
            // contradiction the owner rejected on device.)
            activeRows.forEach { row -> applyCancelCleanupLocked(row) }
            dao.markAllRunningOrQueuedAsFailed()
            manifestCache.clear()
            onDiskCache.clear()
            lastPostedPercent.clear()
            readableMarked.clear()
            activeRows.forEach { runCatching { downloadNotifier.clear(it.chapterId.toInt()) } }
        }
    }

    override suspend fun reconcileInterruptedDownloads() {
        mutex.withLock {
            BgDownloadLog.log("reconcile.requested")
            transport.ensureReady()
            pumpLocked("reconcileInterrupted")
        }
    }

    // ---- TransferListener (callbacks from the background session delegate queue) ----

    override fun onPageComplete(mangaId: Long, chapterId: Long, pageIndex: Int) {
        BgDownloadLog.log("transport.cb.pageComplete", "chapterId" to chapterId, "mangaId" to mangaId, "pageIndex" to pageIndex)
        applicationScope.launch { runCatching { mutex.withLock { handlePageCompleteLocked(mangaId, chapterId, pageIndex) } } }
    }

    override fun onPageFailed(mangaId: Long, chapterId: Long, pageIndex: Int, message: String?) {
        BgDownloadLog.log("transport.cb.pageFailed", "chapterId" to chapterId, "mangaId" to mangaId, "pageIndex" to pageIndex, "msg" to message)
        applicationScope.launch { runCatching { mutex.withLock { handlePageFailedLocked(mangaId, chapterId, pageIndex, message) } } }
    }

    // ---- locked internals (callers hold [mutex]) ----

    private suspend fun pumpLocked(reason: String) {
        val all = dao.observeAllDownloads().first()
        BgDownloadLog.log(
            "pump.start", "reason" to reason,
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
        all.filter { it.state == DownloadingState.RUNNING }
            .forEach { entity ->
                val manifest = manifestStore.read(entity.mangaId, entity.chapterId)
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
        BgDownloadLog.log("window.fill", "active" to active, "queued" to queued.size, "freeSlots" to slots, "concurrency" to CHAPTER_CONCURRENCY)
        for (q in queued) {
            if (slots <= 0) break
            prepareLocked(q)
            slots--
        }
        BgDownloadLog.dlperf("window.ms", "queued" to queued.size, "ms" to winMark.elapsedNow().inWholeMilliseconds)
    }

    private suspend fun prepareLocked(entity: ChapterDownloadEntity) {
        if (entity.state == DownloadingState.QUEUED) {
            if (dao.claimQueuedAsRunning(entity.chapterId) == 0) {
                BgDownloadLog.log("prepare.claim.raced", "chapterId" to entity.chapterId)
                return // raced cancel
            }
            BgDownloadLog.log("state.transition", "chapterId" to entity.chapterId, "from" to "QUEUED", "to" to "RUNNING")
        }
        // Resume without re-scraping when a manifest already exists (fast path, runs under [mutex]).
        val existing = manifestStore.read(entity.mangaId, entity.chapterId)
        if (existing != null) {
            BgDownloadLog.log("manifest.read", "chapterId" to entity.chapterId, "pages" to existing.pages.size)
            reconcileChapterLocked(entity, existing)
            return
        }
        // A resolve-AHEAD scrape for this chapter is already in flight: its completion handles the
        // now-RUNNING row itself (persists the manifest + reconciles → transfers enqueue), and its
        // failure routes through failResolveLocked. Launching a second, real resolve here would just
        // double-scrape the source.
        if (entity.chapterId in prefetching) {
            BgDownloadLog.log("prepare.awaitingPrefetch", "chapterId" to entity.chapterId)
            return
        }
        // No manifest → the page/link resolution (network scrape) must NOT hold the engine mutex (B6: the
        // 200ms+ scrape under the lock serialized every other operation — page callbacks, new enqueues,
        // lifecycle pumps). The row is already claimed RUNNING, so it holds its concurrency slot and a
        // QUEUED scan won't re-pick it; hand the scrape to a coroutine that re-acquires [mutex] only for
        // the quick manifest write + reconcile. Mirrors launchFinalize's off-mutex heavy-work pattern.
        if (!resolving.add(entity.chapterId)) {
            BgDownloadLog.log("prepare.resolve.alreadyInFlight", "chapterId" to entity.chapterId)
            return
        }
        launchResolve(entity)
    }

    /**
     * B6: resolve a chapter's pages (network scrape) OFF [mutex], then re-acquire the lock only for the
     * fast manifest write + reconcile. The caller has already claimed the row RUNNING under the lock and
     * added it to [resolving]; this releases that guard in `finally`.
     */
    private fun launchResolve(entity: ChapterDownloadEntity) {
        applicationScope.launch {
            try {
                BgDownloadLog.log("prepare.resolve.start", "chapterId" to entity.chapterId, "url" to entity.url)
                val resolveMark = TimeSource.Monotonic.markNow() // DLPERF: page/link resolution (network scrape, off-mutex)
                val resolved = try {
                    chapterPageResolver.resolve(entity).also {
                        BgDownloadLog.dlperf("resolve.ms", "chapterId" to entity.chapterId, "pages" to it.imageUrls.size, "ms" to resolveMark.elapsedNow().inWholeMilliseconds)
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    BgDownloadLog.error(t, "prepare.resolve.failed", "chapterId" to entity.chapterId, "msg" to t.message)
                    // Stamp a Cloudflare sentinel for a WebView-solvable challenge so the Details VM can
                    // auto-route to the solver and re-enqueue (downloads parity with the reading path).
                    val isChallenge = HeaderRefreshRules.isCloudflareChallengeFailure(t.message)
                    val failMsg = if (isChallenge) CLOUDFLARE_CHALLENGE else (t.message ?: "Resolve failed")
                    if (isChallenge) BgDownloadLog.log("prepare.resolve.cloudflare", "chapterId" to entity.chapterId)
                    mutex.withLock { failResolveLocked(entity.chapterId, failMsg) }
                    return@launch
                }
                if (resolved.imageUrls.isEmpty()) {
                    BgDownloadLog.warn("prepare.resolve.empty", "chapterId" to entity.chapterId)
                    mutex.withLock { failResolveLocked(entity.chapterId, "No images for chapter") }
                    return@launch
                }
                mutex.withLock {
                    // The row may have been cancelled/deleted during the (slow) network resolve — only
                    // persist + enqueue if it is STILL RUNNING (cancel/delete set a non-RUNNING state or
                    // remove the row under this same lock, so the two can never interleave mid-write).
                    val current = dao.getDownloadByChapter(entity.chapterId)
                    if (current == null || current.state != DownloadingState.RUNNING) {
                        BgDownloadLog.log("prepare.resolve.discarded", "chapterId" to entity.chapterId, "state" to current?.state)
                        return@withLock
                    }
                    // Prefer a manifest another pass may have written while we resolved (idempotent resume).
                    val manifest = manifestStore.read(entity.mangaId, entity.chapterId)
                        ?: buildManifest(entity, resolved).also {
                            manifestStore.write(it)
                            BgDownloadLog.log("manifest.created", "chapterId" to entity.chapterId, "pages" to it.pages.size)
                        }
                    reconcileChapterLocked(current, manifest)
                }
            } finally {
                mutex.withLock { resolving.remove(entity.chapterId) }
            }
        }
    }

    private fun buildManifest(entity: ChapterDownloadEntity, resolved: ResolvedChapter): DownloadManifest =
        DownloadManifest(
            mangaId = entity.mangaId,
            chapterId = entity.chapterId,
            api = entity.api,
            pages = resolved.pages.mapIndexed { index, page ->
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
        val targetId = ResolveAheadRules.selectNextPrefetch(
            queuedInProcessingOrder = queued.map { it.chapterId },
            window = RESOLVE_AHEAD_WINDOW,
            resolving = resolving,
            prefetching = prefetching,
            hasManifest = { id -> byId.getValue(id).let { manifestStore.exists(it.mangaId, it.chapterId) } },
        ) ?: return
        val entity = byId.getValue(targetId)
        prefetching.add(targetId)
        launchPrefetchResolve(entity)
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
    private fun launchPrefetchResolve(entity: ChapterDownloadEntity) {
        applicationScope.launch {
            try {
                // Gentle spacing: chained top-ups (finally → maybePrefetchLocked) scrape sequentially
                // with at least this gap, so lookahead can never burst a source.
                delay(PREFETCH_SPACING_MS)
                BgDownloadLog.log("prefetch.resolve.start", "chapterId" to entity.chapterId, "url" to entity.url)
                val resolved = try {
                    chapterPageResolver.resolve(entity)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    val isChallenge = HeaderRefreshRules.isCloudflareChallengeFailure(t.message)
                    BgDownloadLog.warn("prefetch.resolve.failed", "chapterId" to entity.chapterId, "challenge" to isChallenge, "msg" to t.message)
                    mutex.withLock {
                        pausePrefetchLocked()
                        // Turn arrived mid-scrape → we ARE the resolve; surface the failure normally.
                        if (dao.getDownloadByChapter(entity.chapterId)?.state == DownloadingState.RUNNING) {
                            failResolveLocked(entity.chapterId, if (isChallenge) CLOUDFLARE_CHALLENGE else (t.message ?: "Resolve failed"))
                        }
                    }
                    return@launch
                }
                mutex.withLock {
                    val current = dao.getDownloadByChapter(entity.chapterId)
                    when {
                        current == null ->
                            BgDownloadLog.log("prefetch.discarded", "chapterId" to entity.chapterId, "reason" to "rowGone")
                        resolved.imageUrls.isEmpty() -> {
                            BgDownloadLog.warn("prefetch.resolve.empty", "chapterId" to entity.chapterId)
                            pausePrefetchLocked()
                            if (current.state == DownloadingState.RUNNING) failResolveLocked(entity.chapterId, "No images for chapter")
                        }
                        current.state == DownloadingState.QUEUED -> {
                            if (!manifestStore.exists(entity.mangaId, entity.chapterId)) {
                                val manifest = buildManifest(entity, resolved)
                                manifestStore.write(manifest)
                                BgDownloadLog.log("prefetch.manifest.written", "chapterId" to entity.chapterId, "pages" to manifest.pages.size)
                            }
                        }
                        current.state == DownloadingState.RUNNING -> {
                            // Turn arrived mid-scrape — act as the resolve: persist + reconcile (enqueues transfers).
                            BgDownloadLog.log("prefetch.promotedToResolve", "chapterId" to entity.chapterId)
                            val manifest = manifestStore.read(entity.mangaId, entity.chapterId)
                                ?: buildManifest(entity, resolved).also { manifestStore.write(it) }
                            reconcileChapterLocked(current, manifest)
                        }
                        else ->
                            BgDownloadLog.log("prefetch.discarded", "chapterId" to entity.chapterId, "state" to current.state)
                    }
                }
            } finally {
                mutex.withLock {
                    prefetching.remove(entity.chapterId)
                    // Chain the next top-up (no-op when the window is filled, paused, or disabled).
                    runCatching { maybePrefetchLocked() }
                }
            }
        }
    }

    private fun pausePrefetchLocked() {
        prefetchPausedAtMark = TimeSource.Monotonic.markNow()
        BgDownloadLog.warn("prefetch.paused", "forMs" to PREFETCH_FAILURE_BACKOFF.inWholeMilliseconds)
    }

    private suspend fun reconcileChapterLocked(entity: ChapterDownloadEntity, manifest: DownloadManifest) {
        val onDisk = pagesOnDiskSet(entity.mangaId, entity.chapterId)
        // Re-ground the hot-path caches in real disk truth. Reconcile runs on every pump / window fill /
        // relaunch, so this is what keeps the incrementally-maintained onDiskCache honest across
        // force-quit, OS-killed transfers, and resume — the per-page path only ever ADDS to it.
        manifestCache[entity.chapterId] = manifest
        onDiskCache[entity.chapterId] = onDisk.toMutableSet()
        val inFlight = transport.inFlightPages(entity.chapterId)
        val plan = BackgroundReconciler.plan(manifest, onDisk, inFlight, MAX_ATTEMPTS)
        BgDownloadLog.log(
            "reconcile.plan",
            "chapterId" to entity.chapterId,
            "roomState" to entity.state,
            "manifestPages" to manifest.pages.size,
            "onDisk" to onDisk.size,
            "inFlight" to inFlight.size,
            "toEnqueue" to plan.toEnqueue.size,
            "complete" to plan.isComplete,
            "failedPage" to plan.failedPageIndex,
        )
        updateProgressLocked(entity, manifest, onDisk.size)
        when {
            plan.failedPageIndex != null ->
                failChapterLocked(entity, "Page ${plan.failedPageIndex} failed after $MAX_ATTEMPTS attempts")
            plan.isComplete ->
                markDownloadedAndMaybeFinalizeLocked(entity.chapterId)
            plan.toEnqueue.isNotEmpty() -> {
                val byIndex = manifest.pages.associateBy { it.index }
                // B3: overlay FRESH cookies/UA from the live store onto the frozen manifest headers (one
                // read per reconcile, not per page) so a WebView re-solve is honored on the next request
                // instead of replaying the cookie baked in at resolve time.
                val live = freshSiteHeaders(manifest.api)
                val requests = plan.toEnqueue.mapNotNull { idx ->
                    val mp = byIndex[idx] ?: return@mapNotNull null
                    val headers = HeaderRefreshRules.overlayFreshHeaders(frozen = mp.headers, fresh = live)
                    TransferRequest(entity.mangaId, entity.chapterId, idx, mp.url, headers)
                }
                BgDownloadLog.log("reconcile.enqueue", "chapterId" to entity.chapterId, "pages" to plan.toEnqueue)
                transport.enqueue(requests)
            }
            else -> BgDownloadLog.log("reconcile.waitInFlight", "chapterId" to entity.chapterId, "inFlight" to inFlight.size)
        }
        // A chapter is actively transferring and we have CPU right now — the moment resolve-ahead
        // pays for: top up the next queued chapters' manifests (no-op when filled/paused/disabled).
        maybePrefetchLocked()
    }

    private suspend fun handlePageCompleteLocked(mangaId: Long, chapterId: Long, pageIndex: Int) {
        val entity = dao.getDownloadByChapter(chapterId) ?: return
        if (entity.state != DownloadingState.RUNNING && entity.state != DownloadingState.DOWNLOADED) {
            BgDownloadLog.log("page.complete.ignored", "chapterId" to chapterId, "state" to entity.state)
            return
        }
        val manifest = cachedManifest(mangaId, chapterId) ?: run {
            BgDownloadLog.log("manifest.missing", "chapterId" to chapterId, "fallback" to "pump")
            pumpLocked("pageCompleteNoManifest")
            return
        }
        // O(1) hot path: the transport already moved this page's file to disk before this callback, so
        // recording the index in the in-memory set is exact (and idempotent on a re-enqueue race). No
        // per-page manifest re-parse or full directory listing — that was the on-device download lag.
        val onDisk = cachedOnDisk(mangaId, chapterId).apply { add(pageIndex) }
        updateProgressLocked(entity, manifest, onDisk.size)
        BgDownloadLog.log("page.complete", "chapterId" to chapterId, "onDisk" to onDisk.size, "total" to manifest.pages.size)
        if (manifest.pages.all { it.index in onDisk }) {
            markDownloadedAndMaybeFinalizeLocked(chapterId)
            fillWindowLocked()
        }
    }

    private suspend fun handlePageFailedLocked(mangaId: Long, chapterId: Long, pageIndex: Int, message: String?) {
        val entity = dao.getDownloadByChapter(chapterId) ?: return
        if (entity.state != DownloadingState.RUNNING) {
            BgDownloadLog.log("page.failed.ignored", "chapterId" to chapterId, "state" to entity.state)
            return
        }
        val attempts = manifestStore.incrementAttempt(mangaId, chapterId, pageIndex)
        BgDownloadLog.log("retry.attemptIncremented", "chapterId" to chapterId, "pageIndex" to pageIndex, "attempt" to attempts, "max" to MAX_ATTEMPTS)
        when (val decision = TransferRetryRules.decide(attempts, MAX_ATTEMPTS, message)) {
            is TransferRetryRules.Decision.FailChapter -> {
                BgDownloadLog.warn("retry.exhausted", "chapterId" to chapterId, "pageIndex" to pageIndex, "attempt" to attempts, "challenge" to decision.isChallenge)
                // Transfer-stage challenge (an expired cf_clearance 403ing the image CDN mid-batch)
                // stamps the same Cloudflare sentinel as a resolve-stage challenge, so the Details VM
                // auto-routes to the WebView solver instead of surfacing a dead-end "HTTP 403".
                val failMsg = if (decision.isChallenge) {
                    CLOUDFLARE_CHALLENGE
                } else {
                    message ?: "Page $pageIndex failed after $MAX_ATTEMPTS attempts"
                }
                failChapterLocked(entity, failMsg)
                fillWindowLocked()
            }
            // Bounded exponential backoff retry of just this page (outside the lock, after a delay).
            is TransferRetryRules.Decision.Retry -> scheduleRetry(mangaId, chapterId, pageIndex, attempts, decision.delayMs)
        }
    }

    private fun scheduleRetry(mangaId: Long, chapterId: Long, pageIndex: Int, attempts: Int, delayMs: Long) {
        BgDownloadLog.log("retry.scheduled", "chapterId" to chapterId, "pageIndex" to pageIndex, "attempt" to attempts, "delayMs" to delayMs)
        applicationScope.launch {
            delay(delayMs)
            runCatching { mutex.withLock { retryPageLocked(mangaId, chapterId, pageIndex) } }
        }
    }

    private suspend fun retryPageLocked(mangaId: Long, chapterId: Long, pageIndex: Int) {
        val entity = dao.getDownloadByChapter(chapterId) ?: return
        if (entity.state != DownloadingState.RUNNING) {
            BgDownloadLog.log("retry.skip.notRunning", "chapterId" to chapterId, "pageIndex" to pageIndex, "state" to entity.state)
            return
        }
        if (pageOnDisk(mangaId, chapterId, pageIndex)) {
            BgDownloadLog.log("retry.skip.onDisk", "chapterId" to chapterId, "pageIndex" to pageIndex)
            return
        }
        if (pageIndex in transport.inFlightPages(chapterId)) {
            BgDownloadLog.log("retry.skip.inFlight", "chapterId" to chapterId, "pageIndex" to pageIndex)
            return
        }
        val manifest = manifestStore.read(mangaId, chapterId) ?: return
        val mp = manifest.pages.firstOrNull { it.index == pageIndex } ?: return
        // B3: refresh cookies/UA on the per-page retry too (a 403 from a stale cookie is the usual reason
        // this page is being retried).
        val live = freshSiteHeaders(manifest.api)
        val headers = HeaderRefreshRules.overlayFreshHeaders(frozen = mp.headers, fresh = live)
        BgDownloadLog.log("retry.enqueue", "chapterId" to chapterId, "pageIndex" to pageIndex)
        transport.enqueue(listOf(TransferRequest(mangaId, chapterId, pageIndex, mp.url, headers)))
    }

    private suspend fun markDownloadedAndMaybeFinalizeLocked(chapterId: Long) {
        val entity = dao.getDownloadByChapter(chapterId) ?: return
        // First clause (2026-07 audit, same family as ChapterFinalizer's abandon gate): a cancel
        // that landed before this transfer-complete callback owns the row — flipping it to
        // DOWNLOADED would resurrect the cancelled chapter. Short-circuits BEFORE the once-guard so
        // a cancelled chapter is never marked (a later retry may legitimately mark it).
        // Second clause: run the DOWNLOADED-transition work (mark-readable + slot release +
        // finalize kick) ONCE per chapter. Repeat page-complete / reconcile passes for an
        // already-readable (CBZ-pending) chapter previously re-walked the dir + rewrote Room every
        // tick (log spam + redundant I/O). A deferred chapter's CBZ is retried by the pump's
        // finalize sweep, not by re-running this.
        if (entity.state == DownloadingState.FAILED || !readableMarked.add(chapterId)) return
        val wasRunning = entity.state == DownloadingState.RUNNING
        dao.updateStateChId(chapterId, DownloadingState.DOWNLOADED)
        if (wasRunning) BgDownloadLog.log("state.transition", "chapterId" to chapterId, "from" to "RUNNING", "to" to "DOWNLOADED")

        // All pages are on disk. Make the chapter READABLE from its loose pages RIGHT NOW (cheap DB writes,
        // no CPU window needed — sets isDownloaded + localImagePaths so the reader opens it offline) and
        // RELEASE the queue slot. The queue advances on "pages transferred", never on "CBZ built": a
        // CPU-gated CBZ must never hold the queue hostage when iOS grants no background window.
        val loosePaths = onDiskPagePaths(entity.mangaId, chapterId)
        val cbzPending = runCatching { chapterFinalizer.markReadable(entity, loosePaths) }
            .getOrElse { t -> BgDownloadLog.error(t, "markReadable.failed", "chapterId" to chapterId, "msg" to t.message); true }
        BgDownloadLog.log("downloaded.readable", "chapterId" to chapterId, "pages" to loosePaths.size, "cbzPending" to cbzPending)

        // SILENT "finalizing"/"paused" update (readable, still being packaged) — the alerting "complete"
        // fires at finalize.success (see launchFinalize), so "complete" still means the durable CBZ is ready.
        // Posted once (the readableMarked guard above ensures this whole block runs once per chapter). When the
        // CBZ is deferred SPECIFICALLY by Low Power Mode (user opt-out), post the settled "paused (Low Power
        // Mode)" notice instead of "Finalizing…" — that defer can last the whole session, so it must never look
        // like an endless load. (A thermal defer is transient — the device cools — and keeps "Finalizing…".)
        val lowPowerDeferred = cbzPending && !canCompressNow() && CompressionGateRules.isLowPowerDeferred(
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
                BgDownloadLog.log("finalize.deferred", "chapterId" to chapterId, "reason" to "noExecutionWindow", "readable" to true)
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
    private fun launchFinalize(chapterId: Long) {
        applicationScope.launch {
            val entity = mutex.withLock {
                if (chapterId in finalizing) return@launch
                val e = dao.getDownloadByChapter(chapterId) ?: return@launch
                // Accept DOWNLOADED (normal) and COMPRESSING (B1 crash recovery: a row left in COMPRESSING
                // by a kill mid-encode is re-driven here; finalize() is idempotent). The [finalizing] set
                // still prevents a genuinely in-flight encode from being double-started within a session.
                if (!FinalizeRules.canStartFinalize(e.state)) return@launch
                finalizing.add(chapterId)
                e
            }
            try {
                val paths = onDiskPagePaths(entity.mangaId, chapterId)
                // B2-durable: a kill after the `.cbz` was published (IosCbzWriter renames BEFORE it
                // deletes the loose pages) but before this terminal SUCCESS leaves the loose pages
                // gone + the row stuck COMPRESSING. The archive IS the finished artifact — adopt it
                // instead of failing on "no loose pages" (which would mark a readable chapter FAILED).
                val artifact = FinalizeRules.selectArtifact(
                    loosePagesPresent = paths.isNotEmpty(),
                    existingCbzPath = { existingCbzPath(entity.mangaId, chapterId) },
                )
                when (artifact) {
                    FinalizeRules.Artifact.Missing -> {
                        mutex.withLock { failChapterLocked(entity, "No pages on disk to finalize") }
                        return@launch
                    }
                    is FinalizeRules.Artifact.AdoptCbz -> {
                        BgDownloadLog.log("finalize.adoptExistingCbz", "chapterId" to chapterId)
                        chapterFinalizer.adoptExistingArchive(entity, artifact.cbzPath)
                    }
                    FinalizeRules.Artifact.LoosePages -> {
                        BgDownloadLog.log("finalize.start", "chapterId" to chapterId, "pages" to paths.size)
                        val finalizeMark = TimeSource.Monotonic.markNow() // DLPERF: CBZ encode (off-mutex)
                        finalizeSemaphore.withPermit {
                            chapterFinalizer.finalize(entity, paths) // COMPRESSING → SUCCESS (heavy CBZ; no mutex held)
                        }
                        BgDownloadLog.dlperf("finalize.ms", "chapterId" to chapterId, "pages" to paths.size, "ms" to finalizeMark.elapsedNow().inWholeMilliseconds)
                    }
                }
                manifestStore.delete(entity.mangaId, chapterId)
                BgDownloadLog.log("manifest.deleted", "chapterId" to chapterId, "reason" to "finalizeSuccess")
                BgDownloadLog.log("finalize.success", "chapterId" to chapterId, "transition" to "->SUCCESS")
                // The durable CBZ is now ready → THIS is the user-facing "complete" (alerting banner +
                // sound). It fires here, not at transfer-complete, so "complete" always means readable.
                // Guarded on the row still reading SUCCESS: a delete/cancel that landed mid-encode owns
                // the notification outcome (it already cleared the entry) — don't banner a gone row.
                val finalState = runCatching { dao.getDownloadByChapter(chapterId)?.state }.getOrNull()
                when {
                    finalState == DownloadingState.SUCCESS -> {
                        BgDownloadLog.log("notif.complete.posted", "chapterId" to chapterId)
                        runCatching { downloadNotifier.onComplete(chapterId.toInt(), notifTitle(entity)) }
                    }
                    FinalizeRules.shouldAbandonFinalize(finalState) -> {
                        // A cancel (FAILED) or delete (row gone) landed while the encode ran
                        // (2026-07-04 device smoke). The cancel path deliberately left the files to
                        // the in-flight encode (deleting under IosCbzWriter risks a silently
                        // page-short archive) — finish the cancel NOW that the encode is done with
                        // them: remove the artifact (loose pages and/or published .cbz — whole
                        // chapter dir) and re-assert the readable-bookkeeping revert, so a cancelled
                        // chapter can never remain Downloaded or openable.
                        BgDownloadLog.log("finalize.cancelledCleanup", "chapterId" to chapterId, "state" to finalState)
                        deleteChapterFiles(entity.mangaId, chapterId)
                        runCatching { chapterFinalizer.revertReadable(chapterId) }
                    }
                    else -> {
                        BgDownloadLog.log("notif.complete.skipped", "chapterId" to chapterId, "state" to finalState)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                BgDownloadLog.error(t, "finalize.failed", "chapterId" to chapterId, "msg" to t.message)
                // A CBZ/finalize failure must NOT fail a chapter whose pages are already on disk and
                // readable — the user can read it; the archive retries on the next window/foreground pump.
                // Only fail when the artifact is genuinely gone (no pages AND no .cbz). (CBZ encode errors
                // are already caught inside finalize() → it falls back to loose pages + SUCCESS, so a throw
                // here is an unexpected IO/DB error, not a normal compression miss.)
                val cur = runCatching { dao.getDownloadByChapter(chapterId) }.getOrNull()
                val artifactPresent = runCatching {
                    onDiskPagePaths(entity.mangaId, chapterId).isNotEmpty() || cbzExists(entity.mangaId, chapterId)
                }.getOrDefault(false)
                when (FinalizeRules.classifyFinalizeFailure(currentState = cur?.state, artifactPresent = artifactPresent)) {
                    FinalizeRules.FailureAction.IGNORE_POST_SUCCESS ->
                        BgDownloadLog.log("finalize.postSuccessError.ignored", "chapterId" to chapterId)
                    // The row moved on mid-encode (cancel → FAILED, delete → gone, retry → QUEUED/
                    // RUNNING) — this stale attempt must not clobber the newer lifecycle's state or
                    // post a banner over its notification handling. For the CANCEL/DELETE half of
                    // the stale set ONLY (FAILED/gone — shouldAbandonFinalize; never QUEUED/RUNNING,
                    // where a fresh retry owns the files), also finish the cancel's deferred file
                    // cleanup + bookkeeping revert (2026-07-04 device smoke) — the cancel path left
                    // the files to this encode.
                    FinalizeRules.FailureAction.IGNORE_STALE_ROW -> {
                        BgDownloadLog.log("finalize.staleRowError.ignored", "chapterId" to chapterId, "state" to cur?.state)
                        if (FinalizeRules.shouldAbandonFinalize(cur?.state)) {
                            deleteChapterFiles(entity.mangaId, chapterId)
                            runCatching { chapterFinalizer.revertReadable(chapterId) }
                        }
                    }
                    FinalizeRules.FailureAction.KEEP_READABLE ->
                        BgDownloadLog.warn("finalize.failed.keepReadable", "chapterId" to chapterId, "msg" to (t.message ?: ""))
                    FinalizeRules.FailureAction.FAIL ->
                        runCatching { mutex.withLock { failChapterLocked(entity, t.message ?: "Finalize failed") } }
                }
            } finally {
                // Reached only after a finalize attempt (success → SUCCESS, or a thrown encode → FAILED via
                // the catch). Either way the chapter is terminal and has freed the single active slot, so:
                // drop its hot-path caches and immediately start the next QUEUED chapter (strict
                // chapter-by-chapter) — in this same window/foreground, without waiting for a reconcile
                // tick or an app reopen.
                mutex.withLock {
                    finalizing.remove(chapterId)
                    clearChapterCaches(chapterId)
                    fillWindowLocked()
                }
            }
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
    private suspend fun failResolveLocked(chapterId: Long, message: String) {
        val current = dao.getDownloadByChapter(chapterId)
        if (current == null || current.state != DownloadingState.RUNNING) {
            BgDownloadLog.log("prepare.resolve.failDiscarded", "chapterId" to chapterId, "state" to current?.state)
            return
        }
        failChapterLocked(current, message)
        fillWindowLocked()
    }

    private suspend fun failChapterLocked(entity: ChapterDownloadEntity, message: String) {
        BgDownloadLog.warn("state.transition", "chapterId" to entity.chapterId, "to" to "FAILED", "reason" to message)
        transport.cancelChapter(entity.chapterId)
        clearChapterCaches(entity.chapterId)
        dao.updateFailure(entity.chapterId, message)
        BgDownloadLog.log("notif.failed.posted", "chapterId" to entity.chapterId)
        runCatching { downloadNotifier.onFailed(entity.chapterId.toInt(), notifTitle(entity)) }
    }

    private suspend fun updateProgressLocked(entity: ChapterDownloadEntity, manifest: DownloadManifest, onDiskCount: Int) {
        val total = manifest.pages.size
        if (total <= 0) return
        val percent = ((onDiskCount * 100) / total).coerceIn(0, 100)
        // Throttle to percent-change. Page completion fires per-page (hundreds of times), and reconcile
        // re-posts on every pump; without this guard a 360-page chapter wrote Room + posted a system
        // notification 360+ times (plus dozens of identical 100% reposts at the tail). The progress bar
        // (1% granularity) and the notification are unaffected — they only ever moved per-percent anyway.
        if (lastPostedPercent[entity.chapterId] == percent) return
        lastPostedPercent[entity.chapterId] = percent
        dao.updateProgress(entity.chapterId, percent)
        BgDownloadLog.log("notif.progress.posted", "chapterId" to entity.chapterId, "current" to onDiskCount, "total" to total, "percent" to percent)
        runCatching { downloadNotifier.onProgress(entity.chapterId.toInt(), notifTitle(entity), onDiskCount, total) }
    }

    // ---- hot-path caches ----

    /** Manifest for [chapterId] from the in-memory cache, falling back to disk (and populating) on a miss. */
    private fun cachedManifest(mangaId: Long, chapterId: Long): DownloadManifest? =
        manifestCache[chapterId] ?: manifestStore.read(mangaId, chapterId)?.also { manifestCache[chapterId] = it }

    /** Mutable on-disk page-index set for [chapterId], seeded from the real directory on first access. */
    private fun cachedOnDisk(mangaId: Long, chapterId: Long): MutableSet<Int> =
        onDiskCache.getOrPut(chapterId) { pagesOnDiskSet(mangaId, chapterId).toMutableSet() }

    /** Drop a chapter's hot-path caches — call when it leaves the active set (success/fail/cancel/re-enqueue). */
    private fun clearChapterCaches(chapterId: Long) {
        manifestCache.remove(chapterId)
        onDiskCache.remove(chapterId)
        lastPostedPercent.remove(chapterId)
        readableMarked.remove(chapterId)
    }

    // ---- on-disk helpers ----

    private fun pagesOnDiskSet(mangaId: Long, chapterId: Long): Set<Int> {
        val dir = appFileSystem.chapterDir(mangaId, chapterId)
        val fs = appFileSystem.fileSystem()
        if (!fs.exists(dir)) return emptySet()
        return runCatching { fs.list(dir) }.getOrDefault(emptyList())
            .mapNotNull { PageFileNames.pageIndexFromName(it.name) }
            .toSet()
    }

    private fun onDiskPagePaths(mangaId: Long, chapterId: Long): List<String> {
        val dir = appFileSystem.chapterDir(mangaId, chapterId)
        val fs = appFileSystem.fileSystem()
        if (!fs.exists(dir)) return emptyList()
        return runCatching { fs.list(dir) }.getOrDefault(emptyList())
            .mapNotNull { p -> PageFileNames.pageIndexFromName(p.name)?.let { it to p } }
            .sortedBy { it.first }
            .map { it.second.toString() }
    }

    private fun pageOnDisk(mangaId: Long, chapterId: Long, index: Int): Boolean =
        index in pagesOnDiskSet(mangaId, chapterId)

    /** True if the finalized `chapter_<id>.cbz` archive exists (matches [IosCbzWriter]'s naming). */
    private fun cbzExists(mangaId: Long, chapterId: Long): Boolean =
        appFileSystem.fileSystem().exists(appFileSystem.chapterDir(mangaId, chapterId) / "chapter_$chapterId.cbz")

    /** The finalized `.cbz` path as a string if it exists, else null (B2-durable adopt-recovery). */
    private fun existingCbzPath(mangaId: Long, chapterId: Long): String? =
        (appFileSystem.chapterDir(mangaId, chapterId) / "chapter_$chapterId.cbz")
            .takeIf { appFileSystem.fileSystem().exists(it) }?.toString()

    // Page-name parsing (`image_<n>.<ext>` → n) moved to the pure commonMain [PageFileNames]
    // (test hardening — the parsed index decides finalize page order + reconcile membership).

    private fun deleteChapterFiles(mangaId: Long, chapterId: Long) {
        val dir = appFileSystem.chapterDir(mangaId, chapterId)
        runCatching {
            if (appFileSystem.fileSystem().exists(dir)) appFileSystem.fileSystem().deleteRecursively(dir)
        }.onFailure { BgDownloadLog.warn("files.deleteFailed", "chapterId" to chapterId, "dir" to dir.toString()) }
    }

    /**
     * Surgical twin of [deleteChapterFiles] for a MID-TRANSFER cancel (mobile hardening
     * 2026-07-04): removes only THIS cycle's partially-downloaded page files (the `image_<N>`
     * names [PageFileNames] recognizes) plus the manifest — a published `.cbz` from a previous
     * completed download of the same chapter is deliberately left intact, because its library
     * bookkeeping was never touched by the cancelled cycle and deleting it would strand a
     * chapter that still reads as Downloaded. Per-file best-effort; never throws.
     */
    private fun deletePartialPageFiles(
        mangaId: Long,
        chapterId: Long,
    ) {
        val dir = appFileSystem.chapterDir(mangaId, chapterId)
        val fs = appFileSystem.fileSystem()
        runCatching {
            if (!fs.exists(dir)) return
            fs.list(dir)
                .filter { PageFileNames.pageIndexFromName(it.name) != null }
                .forEach { page ->
                    runCatching { fs.delete(page) }.onFailure {
                        BgDownloadLog.warn(
                            "files.partialDeleteFailed",
                            "chapterId" to chapterId,
                            "file" to page.name,
                        )
                    }
                }
        }.onFailure {
            BgDownloadLog.warn("files.partialDeleteFailed", "chapterId" to chapterId, "dir" to dir.toString())
        }
        manifestStore.delete(mangaId, chapterId)
    }

    // Challenge classification (isCloudflareChallengeFailure + CHALLENGE_STATUS_CODES) moved to the
    // pure commonMain [HeaderRefreshRules] (B3 test hardening) — a match stamps [CLOUDFLARE_CHALLENGE].

    /**
     * B3: FRESH per-source site headers (cf_clearance/Cookie/User-Agent) read from the live store — the
     * same store a WebView re-solve writes to via `saveHeadersForApi`. Overlaid onto a manifest page's
     * FROZEN headers at enqueue time (reconcile/retry) so an expired cookie baked in at resolve time is
     * never replayed (the 403-before-recovery bug). One read per reconcile (api is chapter-wide). Returns
     * empty when nothing is live, in which case callers keep the frozen base unchanged. This is the
     * iOS-engine analogue of the legacy path's live `repo.defaultHeaders` / `headerStore.headersFor(api)`.
     */
    private suspend fun freshSiteHeaders(api: String?): Map<String, String> {
        val a = api?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return runCatching { dataStoreHelper.getHeadersForApi(a) }.getOrNull().orEmpty()
    }

    private fun notifTitle(entity: ChapterDownloadEntity): String {
        val base = entity.mangaTitle?.takeIf { it.isNotBlank() } ?: "Download"
        return "$base - Ch ${entity.number}"
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
