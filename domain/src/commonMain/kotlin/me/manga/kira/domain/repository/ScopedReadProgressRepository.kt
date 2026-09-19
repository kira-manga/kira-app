package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.ProgressHandle
import me.manga.kira.domain.model.identity.ProgressSnapshot
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.progress.ProgressWriteResult

/** Source/work-scoped native progress. The old URL-only port is not an ownership adapter. */
interface ScopedReadProgressRepository {
    /** Explicit Reader entry acquires current durable epochs without resetting them. */
    suspend fun beginSession(chapter: ChapterLocator): AppResult<ProgressSnapshot>

    /** Read-only, including when absent; never opens a session or performs legacy preparation. */
    suspend fun readPosition(chapter: ChapterLocator): AppResult<Int?>

    /** Revalidates both epochs and retained saved IDs atomically. Stale handles never reopen. */
    suspend fun save(handle: ProgressHandle, pageIndex: Int): AppResult<ProgressWriteResult>

    /** Advances only this chapter's fence, creating its tombstone when previously unknown. */
    suspend fun clearChapter(chapter: ChapterLocator): AppResult<Unit>

    /** Advances the work fence even without chapters. Saved deletion must share its owning writer. */
    suspend fun clearWork(work: WorkLocator): AppResult<Unit>
}
