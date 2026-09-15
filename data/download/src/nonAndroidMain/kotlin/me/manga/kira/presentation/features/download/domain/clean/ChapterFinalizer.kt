package me.manga.kira.presentation.features.download.domain.clean

import co.touchlab.kermit.Logger
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.manga.kira.platform.download.BgDownloadLog
import me.manga.kira.data.download.artifacts.ChapterDownloadArtifacts
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.inspectPageArchive
import me.manga.kira.platform.media.requireValid
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.Path.Companion.toPath

/**
 * Finalizes a chapter whose pages are all present on disk: optional CBZ archiving, on-disk size
 * capture, library + notification-table bookkeeping, and the terminal `SUCCESS` write.
 *
 * M1 (clean seam): extracted verbatim from `CoroutineDownloadRepositoryImpl.processJob`'s terminal
 * block so the shared download orchestration has one reusable, **idempotent** finalize step. The
 * Desktop coroutine engine calls it inline once all pages download; the iOS background engine
 * reuses it when the background `URLSession` reports a chapter's pages complete (possibly on next
 * foreground, since CBZ encoding cannot run while the app is suspended).
 */
class ChapterFinalizer(
    records: ChapterCompletionRecords,
    private val appFileSystem: AppFileSystem,
    private val cbzWriter: CbzWriter,
    private val dataStore: DataStoreHelper,
    private val mediaInspector: PageMediaInspector,
) {
    private val dao: ChapterDownloadDao = records.downloads
    private val artifacts: ChapterDownloadArtifacts = records.artifacts

    private val log = Logger.withTag(TAG)

    /**
     * Make a fully-transferred chapter **readable from its loose page files immediately** — the cheap
     * half of [finalize], with NO CBZ encode (no Skia, no CPU window required). Captures the on-disk
     * size, points `localImagePaths` at the loose `image_<N>` pages, flips `isDownloaded`, and mirrors
     * the notification-table row — so the reader's downloaded-chapter fast-path
     * (`isDownloaded && localImagePaths.isNotEmpty()`, see `ChapterPagesRepositoryImpl`) opens it from the
     * loose files without re-fetching.
     *
     * Leaves the download row in its current `DOWNLOADED` state; CBZ archiving is a separate, deferrable
     * post-processing step ([finalize]). This is the iOS-background contract: the queue must advance on
     * **"pages transferred"**, never on **"CBZ built"** — compression can never run while the app is
     * plain-suspended, so making it a queue checkpoint lets `noCpuWindow` stall the whole queue.
     *
     * Idempotent (re-running just re-writes the same rows). Returns whether a CBZ archive is still
     * **pending** — `true` when the CBZ preference is on and pages exist (the caller should schedule
     * [finalize] when a CPU window arrives); `false` when CBZ is off (loose pages ARE the final artifact,
     * so the caller can go straight to the terminal `SUCCESS` write).
     */
    suspend fun markReadable(
        entity: ChapterDownloadEntity,
        loosePaths: List<String>,
        claim: ChapterArtifactClaim? = null,
    ): Boolean {
        val attempt = claim ?: artifacts.claim(entity) ?: return false
        val published = artifacts.ownership.files(attempt) {
            requireReadablePages(loosePaths)
            artifacts.complete(attempt, entity, loosePaths, terminal = false)
        } == true
        return published && dataStore.useCbzFormatFlow.first()
    }

    /** Existing callers must retain the attempt; cancellation never clears a replacement's paths. */
    suspend fun revertReadable(chapterId: Long) {
        artifacts.cancel(chapterId, "__cancelled_by_user__")
    }

    suspend fun finalize(
        entity: ChapterDownloadEntity,
        downloadedPaths: List<String>,
        claim: ChapterArtifactClaim? = null,
    ) {
        val attempt = claim ?: artifacts.claim(entity) ?: return
        artifacts.ownership.files(attempt) {
            currentCoroutineContext().ensureActive()
            require(downloadedPaths.isNotEmpty()) { "Cannot finalize an empty chapter" }
            val finalPaths = if (dataStore.useCbzFormatFlow.first()) {
                artifacts.ownership.publish(attempt) {
                    dao.updateStateChId(entity.chapterId, DownloadingState.COMPRESSING)
                }
                val watchdog = if (BgDownloadLog.DLPERF) startMainThreadStallWatchdog(entity.chapterId) else null
                val archived = try {
                    cbzWriter.createCbzWithSplitting(
                        imagePaths = downloadedPaths.map { it.toPath() },
                        mangaId = entity.mangaId,
                        chapterId = entity.chapterId,
                    )
                } finally {
                    watchdog?.cancel()
                }
                listOf(archived.toString())
            } else {
                requireReadablePages(downloadedPaths)
                downloadedPaths
            }
            currentCoroutineContext().ensureActive()
            if (artifacts.complete(attempt, entity, finalPaths)) {
                log.i { "Chapter ${entity.chapterId} complete (${finalPaths.size} path(s))" }
            }
        }
    }

    private suspend fun requireReadablePages(paths: List<String>) {
        require(paths.isNotEmpty()) { "Cannot finalize an empty chapter" }
        paths.forEach { path ->
            currentCoroutineContext().ensureActive()
            mediaInspector.inspect(path.toPath()).requireValid()
        }
        currentCoroutineContext().ensureActive()
    }

    suspend fun adoptExistingArchive(
        entity: ChapterDownloadEntity,
        cbzPath: String,
        claim: ChapterArtifactClaim? = null,
    ) {
        val attempt = claim ?: artifacts.claim(entity) ?: return
        artifacts.ownership.files(attempt) {
            currentCoroutineContext().ensureActive()
            inspectPageArchive(appFileSystem.fileSystem(), cbzPath.toPath(), mediaInspector)
            currentCoroutineContext().ensureActive()
            artifacts.complete(attempt, entity, listOf(cbzPath))
        }
    }

    private fun startMainThreadStallWatchdog(chapterId: Long): Job? =
        // runCatching guards platforms where Dispatchers.Main isn't installed (e.g. Desktop without the
        // swing coroutines module) — a missing Main dispatcher must never break finalize.
        runCatching {
            CoroutineScope(Dispatchers.Main).launch {
                var last = TimeSource.Monotonic.markNow()
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    val gapMs = last.elapsedNow().inWholeMilliseconds
                    if (gapMs > STALL_MS) BgDownloadLog.dlperf("mainStall", "chapterId" to chapterId, "gapMs" to gapMs)
                    last = TimeSource.Monotonic.markNow()
                }
            }
        }.getOrNull()

    private companion object {
        const val TAG = "ChapterFinalizer"
        const val HEARTBEAT_MS = 33L // ~2 frames @60Hz
        const val STALL_MS = 80L // a main-thread gap beyond this is a perceptible scroll hitch
    }
}
