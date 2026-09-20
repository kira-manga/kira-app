package me.manga.kira.data.repository.progress

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.ProgressHandle
import me.manga.kira.domain.model.identity.ProgressSnapshot
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.progress.ProgressWriteResult
import me.manga.kira.domain.repository.ScopedReadProgressRepository

/** Native Room progress only. Composition must explicitly prepare/reconcile legacy storage. */
class RoomScopedReadProgressRepository(private val owners: ProgressOwnerTransactions) : ScopedReadProgressRepository {
    override suspend fun beginSession(chapter: ChapterLocator): AppResult<ProgressSnapshot> =
        progressStorageResult { owners.write { beginSession(chapter) } }

    override suspend fun readPosition(chapter: ChapterLocator): AppResult<Int?> =
        progressStorageResult { owners.write { readPosition(chapter) } }

    override suspend fun save(handle: ProgressHandle, pageIndex: Int): AppResult<ProgressWriteResult> =
        progressSaveResult { owners.write { save(handle, pageIndex) } }

    override suspend fun clearChapter(chapter: ChapterLocator): AppResult<Unit> =
        progressStorageResult { owners.write { clearChapter(chapter) } }

    override suspend fun clearWork(work: WorkLocator): AppResult<Unit> =
        progressStorageResult { owners.write { clearWork(work) } }
}
