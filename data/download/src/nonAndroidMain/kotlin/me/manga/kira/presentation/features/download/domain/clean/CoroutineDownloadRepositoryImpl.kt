package me.manga.kira.presentation.features.download.domain.clean

import me.manga.kira.data.download.selection.DownloadCatalogAdmission
import me.manga.kira.data.download.selection.DownloadCatalogNotReady
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.notification.DownloadNotifier
import me.manga.kira.platform.background.BackgroundExecutionGuard
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.core.util.data_classes.HandelDataClasses.toChapterDownloadEntity
import me.manga.kira.core.util.data_classes.HandelDataClasses.toChapterEntity
import me.manga.kira.data.download.artifacts.ChapterDownloadArtifacts
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.platform.media.publishPageSnapshot
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.presentation.features.download.data.DownloadingState

/**
 * Phase 14.x — shared real implementation of [DownloadRepository] for iOS + Desktop.
 *
 * Lives in `nonAndroidMain` so the iOS and Desktop targets share a single coroutine-queue-based
 * download pipeline. The Android target keeps its WorkManager-backed `DownloadRepositoryImpl` (see
 * `androidMain`); WorkManager and Android's image-encoding stack (`Bitmap`, `BitmapFactory`, the
 * AOM AVIF decoder) have no JVM/Native equivalents wired in this module, so this implementation:
 *
 *  - **Queues jobs in Room** so they survive process death. The constructor seeds the in-process
 *    channel with every persisted `QUEUED` row on startup (the user's hard constraint: "queued
 *    jobs MUST survive in DB and resume on next launch").
 *  - **Downloads pages via Ktor + okio** straight to the platform's `AppFileSystem.chapterDir` —
 *    one file per page, named `image_<index>.<ext>`. This matches the Android pre-CBZ layout, so
 *    the reader paths set on `SavedChapterEntity.localImagePaths` are interchangeable across
 *    targets.
 *  - **Optionally archives to CBZ** when the `useCbzFormat` preference is on (default true),
 *    mirroring native Android's download-then-compress flow. After all pages download, the engine
 *    flips the row to `COMPRESSING`, calls `CbzWriter.createCbzWithSplitting`, and sets
 *    `localImagePaths` to the single `.cbz` path; the writer deletes the loose source pages on
 *    success. iOS now ships a real STORE-method ZIP writer (`IosCbzWriter`, stores page bytes
 *    verbatim — lossless), so this path is safe on both nonAndroid targets. On Desktop the writer
 *    re-encodes pages as PNG via `ImageIO`. If archiving fails, the engine falls back to the loose
 *    per-page layout. When the preference is off, the loose per-page files are kept as-is. Either
 *    layout is consumed transparently by the reader's `localImagePaths` flow.
 *  - **Cancellation is cooperative.** Every in-flight job checks the Room state before processing
 *    each page; an outside `onCancel` flips the state to `FAILED` and the worker breaks out of
 *    the page loop. The active `Job` is also cancelled where applicable.
 *
 * Observable streams delegate to the existing DAO queries unchanged.
 *
 * Phase 9.x.downloadrepository.componentprune (Task #398): dropped 4 `override` impls
 * (`queuedCount`, `observeAllDownloadsPaged`, `observeDownloadsByStatePaged`,
 * `clearFailedAndQueued`) — interface methods retired in the same slice; see
 * `DownloadRepository.kt` audit header. `androidx.paging.PagingData` and
 * `kotlinx.coroutines.flow.map` imports dropped — only the retired impls used them. The
 * `DownloadingState` import remains LIVE — referenced in `processJob` for state transitions.
 *
 * Phase 9.x.downloadrepository.componentprune.cascade.interface (Task #440 slice A,
 * 2026-05-28): dropped 6 `override` impls (`observeRunningChapter`, `isDownloading`,
 * `queuedChapterIds`, `networkStatus`, `enqueueChaptersDownload`, `cancelAllDownloads`) —
 * interface methods retired in the same slice; see `DownloadRepository.kt` audit-trail
 * postscript. Also dropped the `running: MutableStateFlow<Boolean>` private state field (its
 * sole reader was `isDownloading`) and its 3 assignment sites (the two in `workerLoop` at the
 * running/idle transitions, plus the `running.value = false` in the dropped
 * `cancelAllDownloads`); the field had no remaining effect once the reader was retired.
 * `MutableStateFlow` and `ConnectivityObserver.Status` imports dropped accordingly.
 *
 * Phase 9.x.downloadrepository.componentprune.cascade.ctordep (Task #440 slice B,
 * 2026-05-28): dropped the `connectivityObserver: ConnectivityObserver` ctor parameter held
 * coupled-dead in slice A (its sole caller was `networkStatus`, retired in slice A). Matching
 * Koin ctor-arg drops in `PlatformModule.ios.kt` + `PlatformModule.desktop.kt` land in the same
 * commit alongside the Android-side `DownloadRepositoryImpl` ctor + `PlatformModule.android.kt`
 * ctor-arg drops. `ConnectivityObserver` import dropped — no remaining usage.
 * `@Suppress("UNUSED_PARAMETER")` removed.
 */
class CoroutineDownloadRepositoryImpl(
    private val dao: ChapterDownloadDao,
    private val appFileSystem: AppFileSystem,
    pageTransfer: PageDownloadTransfer,
    host: CoroutineDownloadHost,
    stages: ChapterDownloadStages,
    private val artifacts: ChapterDownloadArtifacts,
    private val operations: DownloadOperationExclusion,
    private val catalog: DownloadCatalogAdmission,
) : DownloadRepository {
    private val httpClient: HttpClient = pageTransfer.httpClient
    private val mediaInspector: PageMediaInspector = pageTransfer.mediaInspector
    private val pageBytePolicy: PageBytePolicy = pageTransfer.pageBytePolicy
    private val applicationScope: CoroutineScope = host.applicationScope
    private val downloadNotifier: DownloadNotifier = host.downloadNotifier
    private val backgroundGuard: BackgroundExecutionGuard = host.backgroundGuard
    private val chapterPageResolver: ChapterPageResolver = stages.resolver
    private val chapterFinalizer: ChapterFinalizer = stages.finalizer

    init {
        requireUncachedPageClient(httpClient)
    }

    private val log = Logger.withTag(TAG)

    /**
     * Queue signal. We never read entities off the channel — they live in Room. The channel just
     * tells the worker "wake up and pull the next QUEUED row from the DAO". A capacity of
     * [Channel.UNLIMITED] guarantees we never lose a wake-up under bursty enqueues.
     */
    private val wakeups = Channel<Unit>(Channel.UNLIMITED)

    /** Single mutex guarding the in-flight job reference so cancellation is race-free. */
    private val activeJobMutex = Mutex()
    private var activeJob: Job? = null
    private var activeChapterId: Long? = null
    private var activeClaim: ChapterArtifactClaim? = null

    init {
        // Start the single worker loop. processJob() catches every non-cancellation throwable and
        // persists it as a FAILED row, so the loop itself never crashes — it just keeps draining
        // the queue. CancellationException is rethrown to honour structured concurrency.
        applicationScope.launch(Dispatchers.Default) {
            workerLoop()
        }
        // One startup signal uses the same prepared/admitted drain as explicit actions. No DAO
        // capture here and no second startup coroutine/preparation racing that drain.
        wakeups.trySend(Unit)
    }

    // ---- DownloadRepository: observable streams (delegate to DAO) ----

    override fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>> = dao.observeAllDownloads()

    // ---- DownloadRepository: mutating ops ----

    override suspend fun enqueueChapterDownload(
        chapter: SavedChapterEntity,
        title: String,
        mangaApi: String,
    ): Unit = operations.withOperation {
        if (artifacts.enqueue(chapter, chapter.toChapterDownloadEntity(apiName = mangaApi, title = title)) != null) {
            wakeups.trySend(Unit)
        }
    }

    override suspend fun retryChapterDownload(expected: ChapterDownloadEntity): Boolean = operations.withOperation {
        if (artifacts.retry(expected) == null) return@withOperation false
        wakeups.trySend(Unit)
        true
    }

    override suspend fun deleteDownload(chapterId: Long): Unit = operations.withOperation {
        val row = dao.getDownloadByChapter(chapterId) ?: return@withOperation
        try {
            check(artifacts.deleteAttempt(row) { claim ->
                val job = activeJobMutex.withLock { activeJob.takeIf { activeClaim?.token == claim.token } }
                job?.cancelAndJoin()
            }) { "Download cleanup could not be settled" }
        } finally {
            wakeups.trySend(Unit)
        }
    }

    override suspend fun onCancel(chapterId: Long): Unit = operations.withOperation {
        val claim = artifacts.cancel(chapterId, CANCELLED_BY_USER) ?: return@withOperation
        val job = activeJobMutex.withLock {
            activeJob.takeIf { activeClaim?.token == claim.token }
        }
        try {
            job?.cancelAndJoin()
            check(artifacts.settleCancelled(claim)) { "Download cleanup could not be settled" }
        } finally {
            wakeups.trySend(Unit)
        }
    }

    override suspend fun cancelARunningChapter(chapterId: Long, mangaId: Long) {
        onCancel(chapterId)
    }

    override suspend fun cancelAllDownloads(): Unit = operations.withOperation {
        val active = dao.observeAllDownloads().first().filter { DownloadRecovery.isActiveDownloadState(it.state) }
        val claims = active.mapNotNull { artifacts.cancel(it.chapterId, CANCELLED_BY_USER) }
        val job = activeJobMutex.withLock { activeJob.takeIf { activeClaim?.token in claims.map { it.token } } }
        job?.cancelAndJoin()
        var settled = true
        claims.forEach { if (!artifacts.settleCancelled(it)) settled = false }
        check(settled) { "Download cleanup could not be settled" }
    }

    // Restart-freeze fix (2026-06-02). Reset rows orphaned in RUNNING / COMPRESSING by a previous
    // process back to QUEUED, then wake the in-process worker loop so it re-pulls them via
    // getNextQueuedChapter (the init-block recovery only handles rows already QUEUED). There is no
    // WorkManager equivalent on iOS/Desktop — the worker parks on `wakeups` and re-queries the DAO,
    // so once the orphaned rows are QUEUED again a single wake-up drains them.
    override suspend fun reconcileInterruptedDownloads(): Unit = operations.withOperation {
        // Exclude the row the in-process worker may have just picked up and flipped to RUNNING (the
        // init-block QUEUED recovery can start draining at construction, moments before this runs);
        // resetting it would abort a live download and re-download it from page 0. Orphans from a
        // previous (dead) process can never match activeChapterId, so they are still reset.
        val excludeId = DownloadRecovery.reconcileExcludeChapterId(activeJobMutex.withLock { activeChapterId })
        dao.reEnqueueInterrupted(excludeChapterId = excludeId)
        wakeups.trySend(Unit)
    }

    // ---- Worker loop ----

    private suspend fun workerLoop() {
        while (currentCoroutineContext().isActive) {
            wakeups.receive()
            try {
                // Once per external wake-up, outside the chapter operation and engine mutex.
                catalog.prepareLocal()
                while (currentCoroutineContext().isActive && processNextQueued()) { /* Drain admitted chapters. */ }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: DownloadCatalogNotReady) {
                // No chapter was captured. Do not mark FAILED or manufacture another wake-up.
            } catch (failure: Throwable) {
                log.e(failure) { "Worker drain failed; parking until next wake-up" }
            }
        }
    }

    /** One capture and producer, rather than the backlog, holds selection exclusion. */
    private suspend fun processNextQueued(): Boolean = catalog.withAdmittedOperation { operation ->
        val admitted = artifacts.awaitNextQueued { dao.getQueuedChaptersForWorker() }
            ?: return@withAdmittedOperation false
        val next = admitted.chapter
        val claim = admitted.claim
        val childOperation = operation.retain()
        val job = try {
            applicationScope.launch(Dispatchers.Default + childOperation, start = CoroutineStart.LAZY) {
                try {
                    // Hold an iOS background-task assertion for the chapter so it can keep
                    // going briefly if the app is backgrounded (no-op on Desktop).
                    artifacts.ownership.producing(claim) {
                        backgroundGuard.runGuarded("dl-${next.chapterId}") { processJob(next, claim) }
                    }
                } catch (ce: CancellationException) {
                    log.w { "Job for chapter ${next.chapterId} cancelled" }
                    runCatching { artifacts.fail(claim, CANCELLED_BY_USER) }
                    throw ce
                } catch (t: Throwable) {
                    log.e(t) { "Job for chapter ${next.chapterId} failed: ${t.message}" }
                    runCatching { artifacts.fail(claim, t.message) }
                }
            }
        } catch (failure: Throwable) {
            childOperation.release()
            throw failure
        }
        // Also runs for a lazy child whose cancelled parent prevents its body from starting.
        job.invokeOnCompletion { childOperation.release() }
        var settled = false
        try {
            activeJobMutex.withLock {
                activeJob = job
                activeChapterId = next.chapterId
                activeClaim = claim
            }
            job.start()
            job.join()
            artifacts.settle(claim)
            settled = true
            runCatching {
                val key = next.chapterId.toInt()
                val finished = dao.getDownloadByChapter(next.chapterId)
                when (NotifierRules.onJobFinished(finished?.state, finished?.errorMsg, CANCELLED_BY_USER)) {
                    NotifierRules.TerminalNotification.COMPLETE -> downloadNotifier.onComplete(key, notifTitle(next))
                    NotifierRules.TerminalNotification.FAILED -> downloadNotifier.onFailed(key, notifTitle(next))
                    NotifierRules.TerminalNotification.CLEAR -> downloadNotifier.clear(key)
                    NotifierRules.TerminalNotification.NONE -> { /* not terminal (still running / re-queued) — leave progress */ }
                }
            }
        } finally {
            // Cancelling this waiter is not a stopped file producer. Retain the parent operation
            // until the child actually exits and original-token settlement has been attempted.
            withContext(NonCancellable) {
                job.cancelAndJoin()
                try {
                    if (!settled) artifacts.settle(claim)
                } finally {
                    activeJobMutex.withLock {
                        if (activeClaim?.token == claim.token) {
                            activeJob = null
                            activeChapterId = null
                            activeClaim = null
                        }
                    }
                }
            }
        }
        true
    }

    private suspend fun processJob(entity: ChapterDownloadEntity, claim: ChapterArtifactClaim) {
        log.i { "Processing chapter ${entity.chapterId} (manga ${entity.mangaId})" }
        // Conditional QUEUED -> RUNNING: claim the row only while it is still QUEUED. A cancel that
        // raced in between getNextQueuedChapter() and here (flipping the row to FAILED) updates 0 rows,
        // so we abort instead of unconditionally overwriting the cancel and downloading to completion.
        if (artifacts.ownership.publish(claim) { dao.claimQueuedAsRunning(entity.chapterId) } != 1) {
            log.w { "Chapter ${entity.chapterId} no longer QUEUED; skipping (likely cancelled)" }
            return
        }

        // M1 (clean seam): catalog page-URL + header resolution lives in ChapterPageResolver so
        // the iOS background engine reuses the same verified path.
        // A resolve failure is classified here (not left to the worker loop's generic catch) so a
        // WebView-solvable Cloudflare/anti-bot challenge stamps the sentinel — the Details VM then
        // auto-routes to the solver and re-enqueues, exactly like the iOS background engine and the
        // reading path. Non-challenge failures keep the raw message; the worker loop's terminal
        // notification (NotifierRules → onFailed) fires identically either way.
        val resolved =
            try {
                chapterPageResolver.resolve(entity)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val isChallenge = HeaderRefreshRules.isCloudflareChallengeFailure(t.message)
                log.e(t) { "Resolve failed for chapter ${entity.chapterId} (challenge=$isChallenge): ${t.message}" }
                artifacts.fail(claim, if (isChallenge) CLOUDFLARE_CHALLENGE else (t.message ?: "Resolve failed"))
                return
            }
        val imageUrls = resolved.imageUrls
        if (imageUrls.isEmpty()) {
            artifacts.fail(claim, "No images for chapter")
            return
        }

        val savedChapter = entity.toChapterEntity()
        val outDir = appFileSystem.chapterDir(entity.mangaId, entity.chapterId)
        artifacts.ownership.files(claim) { appFileSystem.fileSystem().createDirectories(outDir) }

        val downloadedPaths = mutableListOf<String>()
        for ((index, page) in resolved.pages.withIndex()) {
            val url = page.url
            currentCoroutineContext().ensureActive()
            // Cooperative cancel: if an outside caller flipped this chapter to FAILED, stop.
            val state = dao.getDownloadByChapter(entity.chapterId)?.takeIf { it.id == claim.downloadId }?.state
            if (state != DownloadingState.RUNNING) {
                log.w { "Chapter ${entity.chapterId} no longer RUNNING (state=$state); aborting" }
                // Partial pages stay on disk; the caller-driven cleanup path (`onCancel` ->
                // `cancelARunningChapter` -> `deleteChapterFiles`) removes them. A bare `onCancel`
                // without a follow-up file delete intentionally leaves files for inspection.
                return
            }

            try {
                val path = downloadOnePage(url, savedChapter, index, page.headers, claim)
                downloadedPaths += path
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                log.e(t) { "Failed page $index of chapter ${entity.chapterId}: ${t.message}" }
                // Mark failed and bail. We keep the partially-downloaded pages on disk — the next
                // retry will overwrite them and the cleanup path runs via deleteChapterFiles().
                artifacts.fail(claim, t.message ?: "Page $index failed")
                return
            }

            val percent = (((index + 1).toFloat() / imageUrls.size.toFloat()) * 100).toInt()
            dao.updateProgressForArtifact(entity.chapterId, entity.id, claim.token, percent)
            // Silent per-page progress notification (iOS only; Desktop binds a no-op).
            runCatching {
                downloadNotifier.onProgress(entity.chapterId.toInt(), notifTitle(entity), index + 1, imageUrls.size)
            }
        }

        // M1 (clean seam): CBZ archiving + size capture + library/notification bookkeeping + the
        // terminal SUCCESS write now live in ChapterFinalizer (idempotent + reusable by the iOS
        // background engine, which finalizes once the background URLSession reports all pages done).
        chapterFinalizer.finalize(entity, downloadedPaths, claim)
    }

    /** iOS download-notification title for a chapter ("<manga> - Ch <n>"). */
    private fun notifTitle(entity: ChapterDownloadEntity): String {
        val base = entity.mangaTitle?.takeIf { it.isNotBlank() } ?: "Download"
        return "$base - Ch ${entity.number}"
    }

    private suspend fun downloadOnePage(
        imageUrl: String,
        chapter: SavedChapterEntity,
        imageIndex: Int,
        pageHeaders: Map<String, String>,
        claim: ChapterArtifactClaim,
    ): String =
        withContext(Dispatchers.Default) {
            val dir = appFileSystem.chapterDir(chapter.mangaId, chapter.id)
            downloadValidatedPage(
                httpClient,
                PageDownloadRequest(imageUrl, pageHeaders, dir, imageIndex),
                appFileSystem.fileSystem(),
                mediaInspector,
                pageBytePolicy,
                publish = { temporary, metadata ->
                    artifacts.ownership.files(claim) {
                        publishPageSnapshot(appFileSystem.fileSystem(), temporary, imageIndex, metadata)
                    } ?: throw CancellationException("Download attempt retired")
                },
            ).toString()
        }

    private companion object {
        const val TAG = "CoroutineDownloadRepository"

        // Locale-independent sentinel for a user-cancelled download. Persisted into errorMsg and
        // mapped to the localized "cancelled by user" string at render time in :ui, so a localized
        // device never shows English here (and the label tracks the current app locale). Must match
        // DownloadedChapter.CANCELLED_BY_USER_SENTINEL in :domain (which :ui compares against).
        const val CANCELLED_BY_USER = "__cancelled_by_user__"

        // Mirrors DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL in :domain (and the iOS background
        // engine's local copy): written into errorMsg when a resolve fails on a Cloudflare/anti-bot
        // challenge so the Details VM auto-routes to the WebView solver. Kept as a local literal
        // (no :domain dep), in lockstep exactly like CANCELLED_BY_USER.
        const val CLOUDFLARE_CHALLENGE = "__cloudflare_challenge__"
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster255.staleKdocSweep.cascade, Task #712, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster255 leaf 2/2 CLOSER — :shared/nonAndroidMain/presentation/features/download/domain/
 * clean/ legacy-tier 2-actual structural-divergence fan CLOSES, sibling 425. Cumulative
 * §253-postscript count = 155 leaves with this commit. Closes the LEGACY-TIER
 * DownloadRepository 2-actual structural-divergence fan (cluster255 = :shared); see sibling
 * 424 (DownloadRepositoryImpl.kt, Android leaf, this cluster) for the cluster254-stale-
 * prediction acknowledgement + the structural-divergence rationale.
 *
 * File-shape note: 350-line file — `CoroutineDownloadRepositoryImpl` concrete class (NOT
 * actual — implements commonMain `DownloadRepository` INTERFACE, not expect-class) with 6
 * ctor-args (dao + libraryRepository + sourcesRepository + httpClient + applicationScope +
 * appFileSystem) + 1 Logger backing field + 4 fun overrides (observeAllDownloads +
 * enqueueChapterDownload + deleteDownload + onCancel + cancelARunningChapter) + 5 private
 * helpers (workerLoop + processJob + resolveRepo + collectImageUrls + downloadOnePage +
 * detectImageExtension + deleteChapterFiles) + 3 private state fields (wakeups Channel +
 * activeJobMutex + activeJob + activeChapterId) + init block (worker-loop launch + recovery-
 * launch) + companion (TAG + CANCELLED_BY_USER + IMAGE_EXTENSIONS) + 50-line class-level
 * KDoc prose containing 4 historical entries (Phase 14.x port-of-record + Phase
 * 9.x.downloadrepository.componentprune Task #398 + Phase
 * 9.x.downloadrepository.componentprune.cascade.interface Task #440 slice A + Phase
 * 9.x.downloadrepository.componentprune.cascade.ctordep Task #440 slice B).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE + FULFILLED-CONTRACT — concrete impl of commonMain
 *     `DownloadRepository` interface. Sibling-fulfilled by `DownloadRepositoryImpl`
 *     (sibling 424, this cluster, Android leaf — WorkManager-backed). Wired via :shared
 *     PlatformModule.ios.kt + PlatformModule.desktop.kt Koin bindings (single binding-shape
 *     shared across both iOS + Desktop via the nonAndroidMain source set's single concrete
 *     class).
 *
 *   • NONANDROIDMAIN-SOURCE-SET-FIRST-APPEARANCE — the nonAndroidMain source set
 *     compiles for both :shared/iosMain + :shared/desktopMain via a KMP custom hierarchy
 *     template binding. The default KMP hierarchy template has no iOS+Desktop common
 *     parent, so the nonAndroidMain source set is project-custom-defined. This is the
 *     first appearance of this source set in the §253 wave — sibling 424 (Android-leaf)
 *     and this file (iOS+Desktop-leaf via nonAndroidMain) together form a structurally-
 *     novel 2-actual fan that the cluster254-CLOSER prediction missed because the
 *     scouting heuristic enumerated 3-actual fans (android+ios+desktop) only. PRESERVE
 *     — load-bearing for any future audit wishing to identify the project's KMP hierarchy
 *     template (build.gradle.kts custom kotlin { applyDefaultHierarchyTemplate() + custom
 *     intermediate-target wiring}).
 *
 *   • KDOC-DESIGN-RATIONALE-LOAD-BEARING — 50-line KDoc prose documents:
 *     (a) the Phase 14.x port-of-record + rationale-for-shared-iOS-Desktop-impl ("Lives in
 *     nonAndroidMain so the iOS and Desktop targets share a single coroutine-queue-based
 *     download pipeline. The Android target keeps its WorkManager-backed
 *     DownloadRepositoryImpl (see androidMain); WorkManager and Android's image-encoding
 *     stack (Bitmap, BitmapFactory, the AOM AVIF decoder) have no JVM/Native equivalents
 *     wired in this module");
 *     (b) the 4-bullet design-decision chain (Room-queued-jobs-survive-process-death +
 *     ktor+okio-direct-to-platform-AppFileSystem-chapterDir + skip-CBZ-archive-creation +
 *     cooperative-cancellation-via-Room-state-flip);
 *     (c) the 4-bullet design-rationale-justification chain ("user's hard constraint:
 *     queued jobs MUST survive in DB and resume on next launch" + "one file per page,
 *     named image_<index>.<ext>. This matches the Android pre-CBZ layout, so the reader
 *     paths set on SavedChapterEntity.localImagePaths are interchangeable across targets"
 *     + "iOS's CbzWriter actual is unimplemented (no native ZIP writer in Foundation) and
 *     Desktop's CbzWriter actual exists but re-encodes pages as PNG via ImageIO, which is
 *     lossy for the manga-page use case" + "Every in-flight job checks the Room state
 *     before processing each page; an outside onCancel flips the state to FAILED and the
 *     worker breaks out of the page loop");
 *     (d) Phase 9.x.downloadrepository.componentprune Task #398 history (4-override-drop
 *     + 2-import-drop, mirrors Android-sibling-424's history);
 *     (e) Phase 9.x.downloadrepository.componentprune.cascade.interface Task #440 slice A
 *     history (6-override-drop + running:MutableStateFlow<Boolean>-field-drop + 3
 *     assignment-site-drops + 2-import-drop);
 *     (f) Phase 9.x.downloadrepository.componentprune.cascade.ctordep Task #440 slice B
 *     history (connectivityObserver:ConnectivityObserver ctor-arg-drop + Koin ctor-arg-
 *     drops in PlatformModule.ios.kt + PlatformModule.desktop.kt + cross-platform
 *     coordination with Android-side ctor-arg-drop in sibling 424's
 *     PlatformModule.android.kt). PRESERVE — design-intent doc + 4-historical-audit-entry
 *     chain; load-bearing for both the existing CbzWriter-Desktop-lossy-PNG-rationale
 *     audit AND any future task wishing to enable CBZ archive creation on Desktop ("A
 *     future task can enable CBZ on Desktop once the encoder pivot question is settled").
 *
 *   • COROUTINE-CHANNEL-WORKER-LOOP-LIVE — Channel<Unit>(Channel.UNLIMITED) wake-up signal
 *     + Mutex-guarded activeJob/activeChapterId state + applicationScope-launched worker
 *     loop with park-on-receive + inner-loop-drain semantics (newly-enqueued rows during
 *     a long download still picked up without another wake-up). Recovery-launch on init
 *     re-queues all DAO-persisted QUEUED rows from previous process. processJob calls
 *     repo.fetchChapterDataF + walks State.Loading/Success/Error + downloads pages via
 *     ktor httpClient.get with repo.defaultHeaders + writes to AppFileSystem.chapterDir
 *     via okio sink. Cooperative cancel via per-page Room-state-check (FAILED) +
 *     applicationScope.Job cancelAndJoin. LIVE — load-bearing for the iOS+Desktop Phase
 *     14 download-pipeline contract.
 *
 *   • POSTURE-MIRROR-WITH-ANDROID-SIBLING — both Android sibling 424 + this file share
 *     the same observable surface (5 fun overrides) + Room-persistence-as-source-of-truth
 *     pattern (queue rows survive process death via dao.insert + dao.getNextQueuedChapter)
 *     + Cooperative-cancel-via-DB-state-flip (Android: WorkManager
 *     .cancelUniqueWork + chapterDownloadService.deleteChapterFiles + dao.updateFailure;
 *     this file: Mutex-held activeJob.cancelAndJoin + deleteChapterFiles + dao
 *     .updateFailure). Diverge on scheduling primitive (Android WorkManager + worker-
 *     class push-API vs. this file's coroutine-channel-based in-process worker loop) and
 *     image-encoding stack (Android Bitmap+BitmapFactory+AOM AVIF decoder vs. this file's
 *     skip-CBZ-fall-back-to-per-page-file layout). NEUTRAL — both diverging paths are
 *     load-bearing for their respective platforms.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 40+ imports across 6 namespaces: kermit.Logger +
 *     ktor (HttpClient + body + get + headers + HttpResponse + isSuccess) + 15
 *     kotlinx.coroutines (CancellationException + CompletableDeferred + CoroutineScope +
 *     Dispatchers + Job + cancelAndJoin + channels.Channel + currentCoroutineContext +
 *     ensureActive + flow.Flow + flow.first + isActive + launch + sync.Mutex + sync
 *     .withLock + withContext) + 12 me.manga.kira.* (AppFileSystem + chapterDir +
 *     HandelDataClasses.toChapterDownloadEntity + HandelDataClasses.toChapterEntity +
 *     State + ChapterDownloadDao + ChapterDownloadEntity + SavedChapterEntity +
 *     DownloadingState + LibraryRepository + SourcesRepository + BaseMangaRepository) +
 *     okio (buffer + use). LIVE — pure JVM/Native cross-platform SPI; no platform-specific
 *     imports (compiles for both :iosMain and :desktopMain via nonAndroidMain source set
 *     hierarchy).
 *
 *   • CLUSTER255 CLOSER REGISTER — 2-leaf :shared (legacy-tier) 2-actual structural-
 *     divergence fan-out for the commonMain `DownloadRepository` interface CLOSES.
 *     Cluster254-CLOSER-stale-prediction-acknowledgement: this cluster255 target was not
 *     listed in cluster254's CLOSER prediction because the scouting heuristic missed the
 *     :shared/(androidMain,nonAndroidMain)/presentation/features/download/domain/clean/
 *     2-actual structural-divergence shape. Per audit-trail-preservation convention,
 *     cluster254's stale prediction is NOT amended; this cluster255 OPENER/CLOSER pair
 *     documents the correction at the head of sibling 424's postscript + here.
 *
 *   • CLUSTER256 PIVOT PREDICTION — strongest candidate by relatedness-to-cluster255:
 *     :shared/nonAndroidMain/.../core/image/HighQualitySkiaImageDecoder.kt (Coil
 *     Decoder.Factory subclass for iOS+Desktop, registered via ImageDecoderRegistry
 *     .{ios,desktop}.kt at the Factory()-registration point; no Android counterpart since
 *     Android Coil uses BitmapFactory directly). This is a NONANDROIDMAIN-SOLO-LEAF (NOT a
 *     2-actual fan) — Android's image-decoder path is structurally different (Coil
 *     ServiceLoader-based decoder discovery vs. nonAndroidMain explicit Factory()
 *     registration) so the Android side has no analogous file. Cluster256 would close the
 *     nonAndroidMain source-set coverage in the §253 wave. Cluster257+ would scout the
 *     remaining androidMain-only utility solo-leaves (CbzManager.kt + OptimizedCbzManager
 *     .kt) which are NOT fan-shaped + the :shared platform-actual subtree §253 sweep
 *     would reach SATURATION at that point.
 */
