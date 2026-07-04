package me.manga.kira.presentation.features.download.domain.clean

import co.touchlab.kermit.Logger
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.manga.kira.platform.download.BgDownloadLog
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.filesystem.folderSize
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.library.domain.LibraryRepository
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
    private val dao: ChapterDownloadDao,
    private val libraryRepository: LibraryRepository,
    // Mirrors the Android ChapterDownloadService: on completion the notification-table row's
    // localImagePaths/isDownloaded must also be set, so anything reading the notification table
    // (e.g. the Updates screen's download-button state) stays consistent after the queue row is evicted.
    private val notificationDao: NotificationDao,
    private val appFileSystem: AppFileSystem,
    private val cbzWriter: CbzWriter,
    private val dataStore: DataStoreHelper,
) {

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
    suspend fun markReadable(entity: ChapterDownloadEntity, loosePaths: List<String>): Boolean {
        // Second clause (2026-07 audit): a cancel/delete that landed just before this ran owns the
        // row — don't resurrect isDownloaded/localImagePaths bookkeeping over it (see [finalize]).
        // Short-circuit keeps the DAO re-read off the empty-pages path.
        if (loosePaths.isEmpty() || abandonedByCancelOrDelete(entity.chapterId, phase = "markReadable")) return false
        val sizeBytes = runCatching {
            appFileSystem.folderSize(appFileSystem.chapterDir(entity.mangaId, entity.chapterId))
        }.getOrDefault(0L)
        dao.updateSize(entity.chapterId, sizeBytes)
        libraryRepository.updateChapterLocalPaths(entity.chapterId, loosePaths)
        libraryRepository.markChapterAsDownloaded(entity.chapterId)
        notificationDao.addLocalImagePathByChapterId(entity.chapterId, loosePaths)
        val compressionPending = dataStore.useCbzFormatFlow.first()
        log.i { "Chapter ${entity.chapterId} readable from ${loosePaths.size} loose page(s) ($sizeBytes bytes); cbzPending=$compressionPending" }
        return compressionPending
    }

    /**
     * Reverts [markReadable]'s bookkeeping after a USER CANCEL landed inside the finalize window
     * (2026-07-04 device smoke: the chapter is marked readable at transfer-complete — BEFORE
     * "Finalizing…" even shows — so a cancel that only flips the queue row FAILED left it
     * "Downloaded" and openable). Clears `isDownloaded` + `localImagePaths` on the saved chapter
     * and the notification row. Idempotent; the caller decides WHEN this applies
     * ([FinalizeRules.cancelMustRevertReadable] — only the finalize-owned states, so a cancel of a
     * queued retry never clears a previous successful download's bookkeeping).
     */
    suspend fun revertReadable(chapterId: Long) {
        // markChapterNotDownloaded clears BOTH isDownloaded and localImagePaths (one DAO UPDATE).
        libraryRepository.markChapterNotDownloaded(chapterId)
        notificationDao.addLocalImagePathByChapterId(chapterId, emptyList(), downloaded = false)
        log.i { "Chapter $chapterId readable bookkeeping reverted (cancel during finalize window)" }
    }

    suspend fun finalize(entity: ChapterDownloadEntity, downloadedPaths: List<String>) {
        if (abandonedByCancelOrDelete(entity.chapterId, phase = "finalize.entry")) return
        // Optionally archive the downloaded pages into a single CBZ, mirroring native Android's
        // download-then-compress flow. The writer deletes the loose source pages on success and
        // returns the archive path; we then point localImagePaths at that single .cbz instead of
        // the loose page list. Copy the nullable preference into a local before branching.
        val useCbz: Boolean = dataStore.useCbzFormatFlow.first()
        val finalPaths: List<String> = if (useCbz && downloadedPaths.isNotEmpty()) {
            dao.updateStateChId(entity.chapterId, DownloadingState.COMPRESSING)
            // DLPERF (default-off, gated by BgDownloadLog.DLPERF): measure main-thread scheduling stalls
            // WHILE the CBZ encode runs, to quantify COMPRESSING-stage scroll jank and distinguish CPU
            // starvation from GC. Off by default → no Main heartbeat coroutine; flip DLPERF to profile.
            val watchdog = if (BgDownloadLog.DLPERF) startMainThreadStallWatchdog(entity.chapterId) else null
            val archived = try {
                runCatching {
                    cbzWriter.createCbzWithSplitting(
                        imagePaths = downloadedPaths.map { it.toPath() },
                        mangaId = entity.mangaId,
                        chapterId = entity.chapterId,
                    )
                }.getOrElse { t ->
                    if (t is CancellationException) throw t
                    log.e(t) { "CBZ archive failed for chapter ${entity.chapterId}; keeping loose pages" }
                    null
                }
            } finally {
                watchdog?.cancel()
            }
            if (archived != null) listOf(archived.toString()) else downloadedPaths
        } else {
            downloadedPaths
        }

        // Re-check AFTER the encode — the long window where a user cancel can land (2026-07
        // audit): on the iOS background engine `cancelARunningChapter` writes FAILED + deletes the
        // chapter's files while this encode runs off-mutex, and the unconditional SUCCESS write
        // below silently undid the cancel, pointed localImagePaths at deleted files, and let the
        // engine's "Download complete" banner fire (its guard reads the row this write clobbered).
        if (abandonedByCancelOrDelete(entity.chapterId, phase = "finalize.postEncode")) return

        // Capture the final on-disk chapter size (the .cbz if archiving ran, else the loose pages)
        // BEFORE the terminal SUCCESS write, so the single observeAllDownloads emission that flips
        // the row to SUCCESS already carries sizeBytes — the Details/Library size shows the instant
        // the row completes (native size-display parity). Best-effort: a size-walk failure must not
        // fail the download.
        val sizeBytes = runCatching {
            appFileSystem.folderSize(appFileSystem.chapterDir(entity.mangaId, entity.chapterId))
        }.getOrDefault(0L)
        dao.updateSize(entity.chapterId, sizeBytes)

        libraryRepository.updateChapterLocalPaths(entity.chapterId, finalPaths)
        libraryRepository.markChapterAsDownloaded(entity.chapterId)
        // Notification-table parity with the Android engine: set localImagePaths + isDownloaded on the
        // chapter's notification row (no-op when no such row exists). Without this, the Updates-screen
        // download button reverts to "not downloaded" on iOS/Desktop once the downloads-queue row is gone.
        notificationDao.addLocalImagePathByChapterId(entity.chapterId, finalPaths)
        dao.updateStateAndProgress(entity.chapterId, DownloadingState.SUCCESS, 100)
        log.i { "Chapter ${entity.chapterId} complete (${finalPaths.size} path(s), $sizeBytes bytes)" }
    }

    /**
     * True when the row's CURRENT state says a cancel (FAILED) or delete (row gone) took ownership
     * while this attempt's off-mutex work ran — see [FinalizeRules.shouldAbandonFinalize] for why
     * the set is exactly {FAILED, null}. The read is best-effort: if it throws, proceed (the
     * pre-audit behavior) rather than strand a finished chapter on a transient DB error.
     */
    private suspend fun abandonedByCancelOrDelete(
        chapterId: Long,
        phase: String,
    ): Boolean {
        val row = runCatching { dao.getDownloadByChapter(chapterId) }.getOrElse { return false }
        val abandoned = FinalizeRules.shouldAbandonFinalize(row?.state)
        if (abandoned) log.i { "Chapter $chapterId $phase abandoned — row re-purposed (state=${row?.state})" }
        return abandoned
    }

    /**
     * B2-durable recovery: adopt an already-published `.cbz` as the finished artifact. Used when a kill
     * during [finalize] left the `.cbz` on disk (it is now renamed BEFORE the loose pages are deleted)
     * but the loose pages gone and the row still COMPRESSING — re-running [finalize] would see no loose
     * pages and wrongly fail. Repoints `localImagePaths` + the notification row at the archive and writes
     * the terminal SUCCESS, exactly like [finalize]'s tail. Idempotent (re-writes the same rows).
     */
    suspend fun adoptExistingArchive(entity: ChapterDownloadEntity, cbzPath: String) {
        val finalPaths = listOf(cbzPath)
        val sizeBytes = runCatching {
            appFileSystem.folderSize(appFileSystem.chapterDir(entity.mangaId, entity.chapterId))
        }.getOrDefault(0L)
        dao.updateSize(entity.chapterId, sizeBytes)
        libraryRepository.updateChapterLocalPaths(entity.chapterId, finalPaths)
        libraryRepository.markChapterAsDownloaded(entity.chapterId)
        notificationDao.addLocalImagePathByChapterId(entity.chapterId, finalPaths)
        dao.updateStateAndProgress(entity.chapterId, DownloadingState.SUCCESS, 100)
        log.i { "Chapter ${entity.chapterId} adopted existing CBZ archive ($sizeBytes bytes)" }
    }

    /**
     * DLPERF (gated by `BgDownloadLog.DLPERF`, default off): heartbeat on the **main** dispatcher; whenever the gap between beats
     * exceeds [STALL_MS] the main thread was stalled (couldn't service its run loop) — logged as
     * `DLPERF.mainStall`. Run only for the duration of the CBZ encode. While the user scrolls the details
     * screen during COMPRESSING: many/large stalls ⇒ the encode is starving/pausing the UI thread (CPU
     * contention and/or Kotlin/Native GC); few/none ⇒ the lag originates elsewhere.
     */
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
