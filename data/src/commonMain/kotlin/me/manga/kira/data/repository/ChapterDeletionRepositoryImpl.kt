package me.manga.kira.data.repository

import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.domain.repository.ChapterDeletionRepository

/**
 * [ChapterDeletionRepository] over the Room [ChapterDao]. Deletes the `saved_chapters` row via the
 * existing `deleteChapterById` query (a no-op for a missing id). Room suspend queries are main-safe.
 */
class ChapterDeletionRepositoryImpl(
    private val chapterDao: ChapterDao,
) : ChapterDeletionRepository {

    override suspend fun deleteChapter(chapterId: Long) = chapterDao.deleteChapterById(chapterId)
}
