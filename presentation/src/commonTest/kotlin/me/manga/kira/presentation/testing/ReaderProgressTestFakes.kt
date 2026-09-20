package me.manga.kira.presentation.testing

import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.ProgressHandle
import me.manga.kira.domain.model.identity.ProgressSnapshot
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.progress.LegacyProgressDisposition
import me.manga.kira.domain.model.progress.LegacyProgressPreparation
import me.manga.kira.domain.model.progress.LegacyProgressReconciliation
import me.manga.kira.domain.model.progress.ProgressWriteResult
import me.manga.kira.domain.repository.LegacyReadProgressRepository
import me.manga.kira.domain.repository.ScopedReadProgressRepository
import me.manga.kira.domain.usecase.reader.BeginReadProgressSessionUseCase
import me.manga.kira.domain.usecase.reader.PrepareLegacyReadProgressUseCase
import me.manga.kira.domain.usecase.reader.SaveScopedPagePositionUseCase
import me.manga.kira.presentation.reader.ReaderProgressSessions

/** Scripted boundary outcomes, not a substitute implementation of Room ownership/fence checks. */
class RecordingReaderProgressRepository : ScopedReadProgressRepository {
    val positions = mutableMapOf<ChapterLocator, Int>()
    val returnedHandles = mutableMapOf<ChapterLocator, ProgressHandle>()
    val begun = mutableListOf<ChapterLocator>()
    val snapshots = mutableListOf<ProgressSnapshot>()
    val saves = mutableListOf<Pair<ProgressHandle, Int>>()
    val written = mutableListOf<Pair<ProgressHandle, Int>>()
    val readRequests = mutableListOf<ChapterLocator>()
    val chapterClears = mutableListOf<ChapterLocator>()
    val workClears = mutableListOf<WorkLocator>()
    var beginFailure: AppError? = null
    var saveResult: AppResult<ProgressWriteResult> = AppResult.Success(ProgressWriteResult.WRITTEN)
    var beforeBegin: suspend (ChapterLocator) -> Unit = {}
    var beforeSave: suspend (ProgressHandle, Int) -> Unit = { _, _ -> }

    override suspend fun beginSession(chapter: ChapterLocator): AppResult<ProgressSnapshot> {
        begun += chapter
        val failure = beginFailure
        val snapshot = ProgressSnapshot(
            returnedHandles[chapter] ?: ProgressHandle(chapter, workGeneration = 0, chapterGeneration = 0),
            positions[chapter],
        )
        beforeBegin(chapter)
        if (failure != null) return AppResult.Failure(failure)
        snapshots += snapshot
        return AppResult.Success(snapshot)
    }

    override suspend fun readPosition(chapter: ChapterLocator): AppResult<Int?> {
        readRequests += chapter
        return AppResult.Success(positions[chapter])
    }

    override suspend fun save(handle: ProgressHandle, pageIndex: Int): AppResult<ProgressWriteResult> {
        saves += handle to pageIndex
        beforeSave(handle, pageIndex)
        val result = saveResult
        if (result == AppResult.Success(ProgressWriteResult.WRITTEN)) {
            written += handle to pageIndex
            positions[handle.chapter] = pageIndex
        }
        return result
    }

    override suspend fun clearChapter(chapter: ChapterLocator): AppResult<Unit> {
        chapterClears += chapter
        return AppResult.Success(Unit)
    }

    override suspend fun clearWork(work: WorkLocator): AppResult<Unit> {
        workClears += work
        return AppResult.Success(Unit)
    }
}

class RecordingLegacyReaderProgressRepository : LegacyReadProgressRepository {
    val prepared = mutableListOf<ChapterLocator>()
    var reconciliations = 0
    var result: AppResult<LegacyProgressPreparation> =
        AppResult.Success(LegacyProgressPreparation(LegacyProgressDisposition.ABSENT))
    var beforePrepare: suspend (ChapterLocator) -> Unit = {}

    override suspend fun prepare(chapter: ChapterLocator): AppResult<LegacyProgressPreparation> {
        prepared += chapter
        beforePrepare(chapter)
        return result
    }

    override suspend fun reconcile(): AppResult<LegacyProgressReconciliation> {
        reconciliations++
        return AppResult.Success(LegacyProgressReconciliation(0, 0, 0))
    }
}

fun readerProgressSessions(
    progress: RecordingReaderProgressRepository,
    legacy: RecordingLegacyReaderProgressRepository,
): ReaderProgressSessions = ReaderProgressSessions(
    PrepareLegacyReadProgressUseCase(legacy),
    BeginReadProgressSessionUseCase(progress),
    SaveScopedPagePositionUseCase(progress),
)

fun readerLocator(manga: Manga, chapter: Chapter): ChapterLocator =
    ChapterLocator(WorkLocator(manga.api, manga.url), chapter.url)
