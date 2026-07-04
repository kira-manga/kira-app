package me.manga.kira.presentation.features.download.domain.clean

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.io.readByteArray
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.notification.DownloadNotifier
import me.manga.kira.platform.background.BackgroundExecutionGuard
import me.manga.kira.core.util.data_classes.HandelDataClasses.toChapterDownloadEntity
import me.manga.kira.core.util.data_classes.HandelDataClasses.toChapterEntity
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.sources_repositry.BaseMangaRepository
import me.manga.kira.sources_repositry.ar.mangamelloplus.MangamelloPlusRepository
import okio.buffer
import okio.use

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
    private val httpClient: HttpClient,
    private val applicationScope: CoroutineScope,
    private val appFileSystem: AppFileSystem,
    // iOS download-progress notifications (silent per-page progress + banner/sound on done);
    // Desktop binds DownloadNotifier.NoOp. Android is unaffected (WorkManager handles its own).
    private val downloadNotifier: DownloadNotifier,
    // iOS background-execution grace period for an in-flight chapter; Desktop binds PassThrough.
    private val backgroundGuard: BackgroundExecutionGuard,
    // M1 (clean seam): page-URL/header resolution and the terminal CBZ/bookkeeping/SUCCESS step are
    // extracted into these shared collaborators so the queue engine stays focused on scheduling and
    // page transfer. The iOS background-URLSession engine reuses the very same two collaborators.
    private val chapterPageResolver: ChapterPageResolver,
    private val chapterFinalizer: ChapterFinalizer,
) : DownloadRepository {

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

    init {
        // Start the single worker loop. processJob() catches every non-cancellation throwable and
        // persists it as a FAILED row, so the loop itself never crashes — it just keeps draining
        // the queue. CancellationException is rethrown to honour structured concurrency.
        applicationScope.launch(Dispatchers.Default) {
            workerLoop()
        }
        // Recover any QUEUED rows left over from a previous process. Same wake-up path as a fresh
        // enqueue — the worker will pull them via getNextQueuedChapter(). Guarded so a transient
        // DB read failure here cannot escape to applicationScope (an unhandled root-coroutine
        // throwable terminates the app on Kotlin/Native); the worker still drains on later wake-ups.
        applicationScope.launch {
            runCatching {
                val pending = dao.getAllQueuedChapterIds().first()
                if (pending.isNotEmpty()) {
                    log.i { "Recovered ${pending.size} queued chapter(s) on startup" }
                    wakeups.trySend(Unit)
                }
            }.onFailure { log.e(it) { "Startup queue recovery failed: ${it.message}" } }
        }
    }

    // ---- DownloadRepository: observable streams (delegate to DAO) ----

    override fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>> = dao.observeAllDownloads()

    // ---- DownloadRepository: mutating ops ----

    override suspend fun enqueueChapterDownload(
        chapter: SavedChapterEntity,
        title: String,
        mangaApi: String,
    ) {
        // Dedup against an already-active row. The DAO inserts with OnConflictStrategy.REPLACE on
        // the unique chapterId index, so an unconditional insert of a chapter that is currently
        // QUEUED / RUNNING / COMPRESSING rewrites its row to QUEUED/progress=0, which the worker's
        // cooperative state check treats as a mid-chapter cancel and restarts from page 0. No-op in
        // that case; only (re-)enqueue for absent rows or terminal SUCCESS / FAILED retries.
        val existing = dao.getDownloadByChapter(chapter.id)?.state
        if (DownloadRecovery.isActiveDownloadState(existing)) {
            return
        }
        val id = dao.insert(chapter.toChapterDownloadEntity(apiName = mangaApi, title = title))
        if (id >= 0L) wakeups.trySend(Unit)
    }

    override suspend fun deleteDownload(chapterId: Long) {
        dao.deleteByChapterId(chapterId)
    }

    override suspend fun onCancel(chapterId: Long) {
        dao.updateFailure(chapterId, CANCELLED_BY_USER)
    }

    override suspend fun cancelARunningChapter(chapterId: Long, mangaId: Long) {
        val toCancel: Job?
        activeJobMutex.withLock {
            toCancel = if (activeChapterId == chapterId) activeJob else null
            if (activeChapterId == chapterId) {
                activeJob = null
                activeChapterId = null
            }
        }
        toCancel?.cancelAndJoin()
        deleteChapterFiles(mangaId, chapterId)
        onCancel(chapterId)
        // Kick the queue so the next queued chapter (if any) picks up.
        wakeups.trySend(Unit)
    }

    // Re-added (DOWNLOAD "cancel-all marks rows failed" backlog item, 2026-06-01). The
    // coroutine-queue equivalent of native's WorkManager-backed cancelAllDownloads(): cancel
    // any in-flight job under the mutex, then flip every RUNNING / QUEUED / COMPRESSING row to
    // FAILED via the DAO (same DB "mark failed" half the Android impl performs). There is no
    // WorkManager job to cancel on iOS/Desktop — the in-process worker parks on `wakeups` and
    // re-queries the DAO, so once the rows are FAILED there is nothing left to drain.
    override suspend fun cancelAllDownloads() {
        val toCancel: Job?
        val cancelledChapterId: Long?
        activeJobMutex.withLock {
            toCancel = activeJob
            cancelledChapterId = activeChapterId
            activeJob = null
            activeChapterId = null
        }
        toCancel?.cancelAndJoin()
        // Delete the partial pages of the chapter that was mid-download, mirroring
        // cancelARunningChapter (and the Android worker's cancellation cleanup). The bulk
        // markAllRunningOrQueuedAsFailed below leaves no row that would later overwrite or purge
        // them, so without this they orphan in chapterDir until the whole manga is purged.
        if (cancelledChapterId != null) {
            val mangaId = dao.getDownloadByChapter(cancelledChapterId)?.mangaId
            if (mangaId != null) deleteChapterFiles(mangaId, cancelledChapterId)
        }
        dao.markAllRunningOrQueuedAsFailed()
    }

    // Restart-freeze fix (2026-06-02). Reset rows orphaned in RUNNING / COMPRESSING by a previous
    // process back to QUEUED, then wake the in-process worker loop so it re-pulls them via
    // getNextQueuedChapter (the init-block recovery only handles rows already QUEUED). There is no
    // WorkManager equivalent on iOS/Desktop — the worker parks on `wakeups` and re-queries the DAO,
    // so once the orphaned rows are QUEUED again a single wake-up drains them.
    override suspend fun reconcileInterruptedDownloads() {
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
        // Park on the UNLIMITED wake-up channel; any trySend issued before we reach receive() is
        // buffered (never lost), and the inner drain re-queries the DAO per iteration so a single
        // wake-up is enough to drain whatever is QUEUED. Serialized one-job-at-a-time processing
        // (each job flips its row to RUNNING before work) makes duplicate wake-ups harmless.
        while (currentCoroutineContext().isActive) {
            // Park until something signals there might be work.
            wakeups.receive()
            // Process as many queued chapters as the DB has — each iteration re-queries so newly
            // enqueued rows during a long download still get picked up without another wake-up.
            // The drain body runs directly on this coroutine, so a throwable from the DAO pull or
            // the await/mutex bookkeeping (transient I/O, disk pressure) would otherwise kill the
            // lone worker for the process lifetime; we catch it, log, and break back to the park so
            // the next wake-up retries. CancellationException still propagates (structured concurrency).
            while (currentCoroutineContext().isActive) {
                try {
                    val next = dao.getNextQueuedChapter() ?: break
                    val done = CompletableDeferred<Unit>()
                    val job = applicationScope.launch(Dispatchers.Default) {
                        try {
                            // Hold an iOS background-task assertion for the chapter so it can keep
                            // going briefly if the app is backgrounded (no-op on Desktop).
                            backgroundGuard.runGuarded("dl-${next.chapterId}") { processJob(next) }
                        } catch (ce: CancellationException) {
                            log.w { "Job for chapter ${next.chapterId} cancelled" }
                            runCatching { dao.updateFailure(next.chapterId, CANCELLED_BY_USER) }
                            throw ce
                        } catch (t: Throwable) {
                            log.e(t) { "Job for chapter ${next.chapterId} failed: ${t.message}" }
                            runCatching { dao.updateFailure(next.chapterId, t.message) }
                        } finally {
                            done.complete(Unit)
                        }
                    }
                    activeJobMutex.withLock {
                        activeJob = job
                        activeChapterId = next.chapterId
                    }
                    done.await()
                    // Download-progress notification (iOS): alert on the terminal outcome. The
                    // silent per-page progress is posted inside processJob; here we fire the
                    // banner+sound completion/failure notice, or clear it on a user cancel.
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
                    activeJobMutex.withLock {
                        if (activeChapterId == next.chapterId) {
                            activeJob = null
                            activeChapterId = null
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    log.e(t) { "Worker drain failed: ${t.message}; parking until next wake-up" }
                    break
                }
            }
        }
    }

    private suspend fun processJob(entity: ChapterDownloadEntity) {
        log.i { "Processing chapter ${entity.chapterId} (manga ${entity.mangaId})" }
        // Conditional QUEUED -> RUNNING: claim the row only while it is still QUEUED. A cancel that
        // raced in between getNextQueuedChapter() and here (flipping the row to FAILED) updates 0 rows,
        // so we abort instead of unconditionally overwriting the cancel and downloading to completion.
        if (dao.claimQueuedAsRunning(entity.chapterId) == 0) {
            log.w { "Chapter ${entity.chapterId} no longer QUEUED; skipping (likely cancelled)" }
            return
        }

        // M1 (clean seam): page-URL + header resolution (config/generic vs legacy scraper) now lives
        // in ChapterPageResolver — same logic, just relocated so the iOS background engine reuses it.
        // A resolve failure is classified here (not left to the worker loop's generic catch) so a
        // WebView-solvable Cloudflare/anti-bot challenge stamps the sentinel — the Details VM then
        // auto-routes to the solver and re-enqueues, exactly like the iOS background engine and the
        // reading path. Non-challenge failures keep the raw message; the worker loop's terminal
        // notification (NotifierRules → onFailed) fires identically either way.
        val resolved = try {
            chapterPageResolver.resolve(entity)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val isChallenge = HeaderRefreshRules.isCloudflareChallengeFailure(t.message)
            log.e(t) { "Resolve failed for chapter ${entity.chapterId} (challenge=$isChallenge): ${t.message}" }
            dao.updateFailure(entity.chapterId, if (isChallenge) CLOUDFLARE_CHALLENGE else (t.message ?: "Resolve failed"))
            return
        }
        val imageUrls = resolved.imageUrls
        if (imageUrls.isEmpty()) {
            dao.updateFailure(entity.chapterId, "No images for chapter")
            return
        }

        val savedChapter = entity.toChapterEntity()
        val outDir = appFileSystem.chapterDir(entity.mangaId, entity.chapterId)
        appFileSystem.fileSystem().createDirectories(outDir)

        val downloadedPaths = mutableListOf<String>()
        for ((index, url) in imageUrls.withIndex()) {
            currentCoroutineContext().ensureActive()
            // Cooperative cancel: if an outside caller flipped this chapter to FAILED, stop.
            val state = dao.getDownloadByChapter(entity.chapterId)?.state
            if (state != DownloadingState.RUNNING) {
                log.w { "Chapter ${entity.chapterId} no longer RUNNING (state=$state); aborting" }
                // Partial pages stay on disk; the caller-driven cleanup path (`onCancel` ->
                // `cancelARunningChapter` -> `deleteChapterFiles`) removes them. A bare `onCancel`
                // without a follow-up file delete intentionally leaves files for inspection.
                return
            }

            try {
                val path = downloadOnePage(url, savedChapter, index, resolved.repo, resolved.overrideHeaders)
                downloadedPaths += path
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                log.e(t) { "Failed page $index of chapter ${entity.chapterId}: ${t.message}" }
                // Mark failed and bail. We keep the partially-downloaded pages on disk — the next
                // retry will overwrite them and the cleanup path runs via deleteChapterFiles().
                dao.updateFailure(entity.chapterId, t.message ?: "Page $index failed")
                return
            }

            val percent = (((index + 1).toFloat() / imageUrls.size.toFloat()) * 100).toInt()
            dao.updateProgress(entity.chapterId, percent)
            // Silent per-page progress notification (iOS only; Desktop binds a no-op).
            runCatching {
                downloadNotifier.onProgress(entity.chapterId.toInt(), notifTitle(entity), index + 1, imageUrls.size)
            }
        }

        // M1 (clean seam): CBZ archiving + size capture + library/notification bookkeeping + the
        // terminal SUCCESS write now live in ChapterFinalizer (idempotent + reusable by the iOS
        // background engine, which finalizes once the background URLSession reports all pages done).
        chapterFinalizer.finalize(entity, downloadedPaths)
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
        repo: BaseMangaRepository?,
        // Sources Migration Phase 3: when non-null (config-backed/generic path), these per-page headers
        // are used verbatim and the legacy repo-based header logic is skipped (repo is null there).
        overrideHeaders: Map<String, String>?,
    ): String = withContext(Dispatchers.Default) {
        val response: HttpResponse = httpClient.get(imageUrl) {
            headers {
                when {
                    overrideHeaders != null -> overrideHeaders.forEach { (name, value) -> append(name, value) }
                    // Parity with Android ChapterDownloadService.downloadImage: MangamelloPlus image
                    // URLs on the mangamello CDN take the dedicated imgsHeader (host: cdn.mangamello.com)
                    // and everything else for that repo takes NO headers; all other repos use defaultHeaders.
                    repo is MangamelloPlusRepository -> {
                        if (imageUrl.contains("mangamello", ignoreCase = true) ||
                            imageUrl.contains("mello", ignoreCase = true) ||
                            imageUrl.contains("cdn.mangamello.com", ignoreCase = true)
                        ) {
                            repo.imgsHeader.forEach { (name, value) -> append(name, value) }
                        }
                    }
                    repo != null -> repo.defaultHeaders.forEach { (name, value) -> append(name, value) }
                }
            }
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Image download HTTP ${response.status.value} for $imageUrl")
        }
        val contentType = response.headers["Content-Type"]
        val extension = detectImageExtension(contentType, imageUrl)

        val dir = appFileSystem.chapterDir(chapter.mangaId, chapter.id)
        appFileSystem.fileSystem().createDirectories(dir)
        val outPath = dir / "image_$imageIndex.$extension"

        // Stream the page body in bounded chunks instead of materialising the whole (multi-megabyte)
        // image in heap before the write — avoids per-page peak-memory spikes, which matter on iOS
        // especially when a download runs concurrently with the reader.
        val channel = response.bodyAsChannel()
        appFileSystem.fileSystem().sink(outPath).buffer().use { sink ->
            while (!channel.exhausted()) {
                val chunk = channel.readBuffer(STREAM_CHUNK_BYTES).readByteArray()
                if (chunk.isNotEmpty()) sink.write(chunk)
            }
        }
        outPath.toString()
    }

    private fun detectImageExtension(contentType: String?, imageUrl: String): String {
        val urlExt = imageUrl.substringAfterLast('.', "").substringBefore('?').lowercase()
        if (urlExt in IMAGE_EXTENSIONS) return urlExt
        val ct = contentType?.lowercase().orEmpty()
        return when {
            "avif" in ct -> "avif"
            "jpeg" in ct || "jpg" in ct -> "jpg"
            "png" in ct -> "png"
            "gif" in ct -> "gif"
            "webp" in ct -> "webp"
            "bmp" in ct -> "bmp"
            else -> "jpg"
        }
    }

    private fun deleteChapterFiles(mangaId: Long, chapterId: Long) {
        val dir = appFileSystem.chapterDir(mangaId, chapterId)
        runCatching {
            if (appFileSystem.fileSystem().exists(dir)) {
                appFileSystem.fileSystem().deleteRecursively(dir)
            }
        }.onFailure { log.w(it) { "Failed to delete chapter files at $dir" } }
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
        // Per-read chunk size for streaming page bodies to disk (kotlin.io.DEFAULT_BUFFER_SIZE is
        // JVM-only, so it is unavailable on the iOS/native target this nonAndroid source set covers).
        const val STREAM_CHUNK_BYTES = 8192
        val IMAGE_EXTENSIONS = setOf("avif", "jpg", "jpeg", "png", "gif", "webp", "bmp")
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

