package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.download.artifacts.ChapterArtifactReference
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.domain.repository.DownloadsActionRepository
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * [DownloadsActionRepository] strangler-fig delegate over the legacy `:shared`
 * [DownloadRepository] mutation methods + the legacy [ChapterDownloadDao] for the retry
 * row-lookup.
 *
 * Phase 7.x.downloads.actions rework. Each method wraps a single legacy invocation in
 * `runCatching {}` so any throw (Room write failure, WorkManager enqueue rejection on
 * Android, Ktor connectivity error during a cooperative cancel on iOS/Desktop, the
 * already-deleted-row race on retry) surfaces as [Result.failure]. The rework VM surfaces
 * the throwable's `message` via a `DownloadsEffect.ShowError` snackbar.
 *
 * **Strangler-fig posture**: same shape as [ComplaintActionRepositoryImpl] (mutation slice
 * over per-method legacy calls) and the read-side sibling
 * [DownloadsRepositoryImpl] (which already reaches the legacy [DownloadRepository] for the
 * observe path). Two reaches at this boundary — the legacy `DownloadRepository` for the
 * three direct mutations + the legacy [ChapterDownloadDao] for the retry row-lookup. Both
 * collaborators are per-platform Koin singletons declared in
 * `PlatformModule.{android,ios,desktop}.kt`. Retired by Phase 9.x route-swap.
 *
 * **Why retry reaches the DAO directly** instead of asking the legacy `DownloadRepository`
 * for the entity: the legacy `DownloadRepository` interface (in `:shared`) does not expose
 * a "look up by chapterId" method — only `observeRunningChapter()` (single-row flow over
 * an unrelated state filter) and `observeAllDownloads()` (full list). Adding a new method
 * to the legacy interface would force a corresponding edit on the Android impl
 * (`DownloadRepositoryImpl`) and the nonAndroid impl (`CoroutineDownloadRepositoryImpl`),
 * each owning a different concurrency model — a needlessly large blast radius. The DAO
 * already has [ChapterDownloadDao.getDownloadByChapter] (suspend single-row select by
 * chapterId), so the impl reaches it directly. This stretches the strangler-fig boundary
 * one row wider than the read-side sibling but stays within the established `:data` →
 * `:shared` permission ([DownloadsRepositoryImpl] / [ReadingStatisticsRepositoryImpl] /
 * [ReadingSessionRepositoryImpl] all reach into the same `:shared` cell of truth).
 *
 * **Why enqueue reaches a different DAO** (`ChapterDao`, not `ChapterDownloadDao`): retry's
 * source row is in the `downloads` table because the chapter has been enqueued at least once
 * before. Enqueue's target chapter has NO `downloads` row yet — but it does have a
 * `saved_chapters` row (populated by `MangaSyncWorker` when the chapter list was first
 * scraped). `ChapterDao.getChapterByIdSuspend(chapterId)` returns the
 * `SavedChapterEntity` directly, which is exactly the shape the legacy
 * `enqueueChapterDownload(chapter, title, api)` expects. The two DAOs are kept as distinct
 * constructor params ([chapterDownloadDao] / [chapterDao]) so the impl's intent at each
 * call site is unambiguous — retry reads its lookup target from the downloads table,
 * enqueue reads its lookup target from the chapters table.
 *
 * **Retry semantics — `getDownloadByChapter` returns null**: if the row has been deleted
 * between the user seeing the FAILED row and tapping retry (race vs the legacy
 * `cancelAllDownloads` / `clearFailedAndQueued` / direct delete on a sibling device), the
 * impl returns `Result.failure(IllegalStateException("download row not found"))`. The
 * caller surfaces the throwable's message via the snackbar effect. The legacy path has
 * the same race (calls `toChapterEntity()` on whatever entity the row-pass-through hands
 * the callback — but in the legacy the entity is captured at observe time so the race
 * surfaces as "re-enqueued a non-existent chapter, the worker will fail at fetch time"
 * instead of "no-op with snackbar"). The rework's fail-fast posture is the safer of the
 * two — the user gets feedback that nothing happened, rather than a silent
 * snackbar-less worker failure later.
 *
 * **No `?: ""` fallback on `mangaTitle` after the legacy entity is found**: the legacy
 * `ChapterDownloadEntity.mangaTitle` field is nullable, and the legacy `DownloadsScreen
 * Route` substitutes empty string when null (`it.mangaTitle ?: ""`). The rework mirrors
 * this exactly.
 *
 * Retry uses `retryChapterDownload(capturedRow)`, not the fresh-enqueue path. Each platform
 * compares the original FAILED generation and reserves file custody in one Room transaction.
 * A deleted/replaced/busy row reports failure; it never silently inserts new history.
 *
 * **`cancel` / `cancelRunning` / `delete` are direct passthroughs**: legacy
 * `onCancel(chapterId)` / `cancelARunningChapter(chapterId, mangaId)` /
 * `deleteDownload(chapterId)` accept the same identifiers the rework already keeps in
 * `DownloadedChapter`. No DAO touch — the legacy repo itself fans the call into the DAO
 * + the worker / coroutine cancel signal.
 *
 * **No suspend collector lifetime**: each method is `suspend Unit` — does its work and
 * returns. The reactive list updates flow back through the existing
 * `DownloadsRepository.observeAll()` (Room re-emits on every write, propagating state to
 * both the rework path and any legacy collectors).
 *
 * **SRP**: ONE rule — "submit a Downloads per-row mutation through the legacy facade and
 * report success/failure". No reads (those live on [DownloadsRepositoryImpl]); no
 * derivation; no orchestration beyond the per-method legacy call.
 *
 * **DIP**: implements the [DownloadsActionRepository] interface from `:domain`. The
 * interface is the seam — `:presentation` / `:ui` never see this impl, only the use cases
 * that depend on the interface.
 *
 * **Lifecycle**: `single` in Koin (matches the read-side sibling and the legacy
 * `DownloadRepository`/`ChapterDownloadDao` singletons).
 *
 * **Threading**: no explicit dispatcher pinning. Each suspend method delegates to a legacy
 * call which is already dispatcher-aware (Room writes pin to its own IO dispatcher;
 * WorkManager enqueue on Android is its own executor).
 *
 * **Load-bearing fixes preserved**: this slice does NOT touch the Coil ImageLoader, AVIF
 * decoder, HighQualitySkiaImageDecoder, or `:platform` — download mutations are Room +
 * worker signals only. No load-bearing risk.
 *
 * **Audit-trail postscript** (Phase 9.x.chapterdownloaddao.componentprune.cascade, Task #441,
 * 2026-05-28): the "Why retry reaches the DAO directly" paragraph above cites
 * `observeRunningChapter()` as one of the only two legacy-interface methods (the rationale
 * for the strangler-fig boundary stretch). Both that interface method AND its DAO backing
 * (`ChapterDownloadDao.observeRunningChapter`) have since been retired — the interface
 * method by §440 slice A (Phase 9.x.downloadrepository.componentprune.cascade.interface)
 * after `DownloadViewModelv2.runningChapter` itself became cascade-orphan, and the DAO
 * method by §441 (this slice) once it lost its sole impl reachers. The "Retry semantics —
 * `getDownloadByChapter` returns null" paragraph cites `cancelAllDownloads` /
 * `clearFailedAndQueued` as race partners — `cancelAllDownloads` retired in §440 slice A,
 * `clearFailedAndQueued` retired in §398. The retry-race surface is now narrower — only
 * direct per-row delete on a sibling device + the rework `DownloadsActionRepository
 * .deleteDownload`/`.cancelDownload` paths can produce a between-the-tap delete. The
 * rationale's spirit (the legacy interface offered no chapterId-keyed single-row lookup;
 * DAO `getDownloadByChapter` is the only suitable seam) remains correct — both retired
 * methods were the only candidates that came close, neither was actually suitable.
 */
class DownloadsActionRepositoryImpl(
    private val legacy: DownloadRepository,
    private val chapterDownloadDao: ChapterDownloadDao,
    private val chapterDao: ChapterDao,
    // Restart-freeze + size back-fill (2026-06-02): used by [reconcileInterrupted] to compute the
    // on-disk size of completed rows that pre-date the sizeBytes column. Reaches `:platform` — the
    // same `:data` -> `:platform` direction the layering contract permits.
    private val appFileSystem: AppFileSystem,
    private val artifacts: ChapterArtifacts,
) : DownloadsActionRepository {
    override suspend fun enqueueDownload(
        chapterId: Long,
        mangaTitle: String,
        api: String,
    ): Result<Unit> =
        runCatchingCancellable {
            val savedChapter =
                chapterDao.getChapterByIdSuspend(chapterId)
                    ?: error("chapter row not found")
            legacy.enqueueChapterDownload(
                chapter = savedChapter,
                title = mangaTitle,
                mangaApi = api,
            )
        }

    override suspend fun retryDownload(chapterId: Long): Result<Unit> =
        runCatchingCancellable {
            val row =
                chapterDownloadDao.getDownloadByChapter(chapterId)
                    ?: error("download row not found")
            check(legacy.retryChapterDownload(row)) { "download changed or retry is not currently available" }
        }

    override suspend fun cancelDownload(chapterId: Long): Result<Unit> =
        runCatchingCancellable {
            legacy.onCancel(chapterId)
        }

    override suspend fun cancelRunningDownload(
        chapterId: Long,
        mangaId: Long,
    ): Result<Unit> = runCatchingCancellable { legacy.cancelARunningChapter(chapterId, mangaId) }

    override suspend fun cancelAllDownloads(): Result<Unit> = runCatchingCancellable { legacy.cancelAllDownloads() }

    override suspend fun deleteDownload(chapterId: Long): Result<Unit> =
        runCatchingCancellable {
            // #10 (native-wins): ROW-ONLY delete — remove the chapter_downloads queue row only, exactly
            // like native DownloadRepositoryImpl.deleteDownload (= dao.deleteByChapterId). The on-disk
            // files and the saved_chapters `isDownloaded` flag are intentionally LEFT intact, so the
            // chapter stays readable offline and the "Downloaded" badge stays lit. Full cleanup
            // (clear the flag + delete files) is the SEPARATE Library "delete downloaded" path
            // (LibraryRepository.deleteDownloadedChapters), surfaced via [deleteDownloadedChapter].
            legacy.deleteDownload(chapterId)
        }

    override suspend fun deleteDownloadedChapter(chapterId: Long): Result<Unit> =
        runCatchingCancellable {
            val saved = chapterDao.getChapterByIdSuspend(chapterId) ?: error("chapter row not found")
            val removed = try {
                artifacts.removeChapter(ChapterArtifactOwner.of(saved)) {
                    // A signal accelerates the drain, but only the shared file-use fence proves it.
                    runCatchingCancellable { legacy.cancelARunningChapter(chapterId, saved.mangaId) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false // Durable custody remains; do not expose filesystem paths in the UI error.
            }
            check(removed) { "Chapter artifact removal could not be settled" }
        }

    override suspend fun reconcileInterrupted(): Result<Unit> =
        runCatchingCancellable {
            // 1) Reset rows orphaned in RUNNING / COMPRESSING by a killed process and re-trigger the
            //    engine (WorkManager re-enqueue on Android; worker-loop wake-up on iOS/Desktop).
            legacy.reconcileInterruptedDownloads()
            // 2) Back-fill the on-disk size of completed rows that pre-date the sizeBytes column (rows
            //    migrated up from schema v8). Read only exact referenced files, never a recursive
            //    directory walk. Finish unrelated rows, then report a sanitized aggregate failure.
            var failures = 0
            chapterDownloadDao.getCompletedWithoutSize().forEach { row ->
                val result = runCatchingCancellable {
                    artifacts.read(row.chapterId) { record ->
                        if (record?.token != null) return@read
                        val saved = chapterDao.getChapterByIdSuspend(row.chapterId) ?: return@read
                        if (saved.mangaId != row.mangaId || saved.url != row.url) return@read
                        val paths = referencedPaths(saved, record)
                        if (paths.isEmpty()) return@read
                        val size = withContext(platformIoDispatcher) {
                            paths.distinct().fold(0L) { total, raw ->
                                val metadata = appFileSystem.fileSystem().metadata(raw.toPath())
                                val bytes = checkNotNull(metadata.size)
                                check(metadata.isRegularFile && bytes > 0 && total <= Long.MAX_VALUE - bytes)
                                total + bytes
                            }
                        }
                        chapterDownloadDao.refreshCompletedSize(row, saved.localImagePaths, size)
                    }
                }
                if (result.isFailure) failures++
            }
            // 3) Repair the old separately committed SUCCESS/saved-false window, not an interrupted
            //    full deletion (which clears paths first) or intentional history-only deletion.
            failures += repairCompletedDownloadFlags()
            check(failures == 0) { "Download maintenance could not be completed" }
        }

    private suspend fun repairCompletedDownloadFlags(): Int {
        var failures = 0
        chapterDownloadDao.getCompletedWithoutDownloadedFlag().forEach { row ->
            val result = runCatchingCancellable {
                artifacts.read(row.chapterId) { record ->
                    if (record?.token != null) return@read
                    val saved = chapterDao.getChapterByIdSuspend(row.chapterId) ?: return@read
                    if (saved.mangaId != row.mangaId || saved.url != row.url || saved.isDownloaded) return@read
                    if (hasReadableDownloadPaths(referencedPaths(saved, record))) {
                        chapterDownloadDao.repairCompletedDownloadFlag(row, saved.localImagePaths)
                    }
                }
            }
            if (result.isFailure) failures++
        }
        return failures
    }

    private fun referencedPaths(saved: SavedChapterEntity, record: ChapterArtifactEntity?): List<String> {
        if (record != null && (record.mangaId != saved.mangaId || record.chapterUrl != saved.url)) return emptyList()
        val relative = record?.committedRelativePath ?: return saved.localImagePaths
        return listOf(ChapterArtifactReference.resolve(appFileSystem, ChapterArtifactOwner.of(saved), relative).toString())
    }

    private suspend fun hasReadableDownloadPaths(paths: List<String>): Boolean = withContext(platformIoDispatcher) {
        if (paths.isEmpty()) return@withContext false
        val fs = appFileSystem.fileSystem()
        // Open the actual retained files, not merely their directory. This is a point-in-time
        // check outside SQL; the transaction separately rechecks the expected paths and state.
        paths.all { raw ->
            if (raw.isBlank()) return@all false
            val path = raw.toPath()
            val metadata = fs.metadataOrNull(path) ?: return@all false
            metadata.isRegularFile &&
                (metadata.size ?: 0L) > 0L &&
                fs.source(path).buffer().use { !it.exhausted() }
        }
    }
}
