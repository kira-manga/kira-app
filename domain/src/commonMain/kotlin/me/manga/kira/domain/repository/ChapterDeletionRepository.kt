package me.manga.kira.domain.repository

/**
 * Narrow port (ISP) for deleting a single saved chapter RECORD from the local library DB (the
 * `saved_chapters` row), identified by its Room id. Backs the Details per-chapter delete button.
 *
 * The caller cleans the chapter's download FIRST (on-disk files + `chapter_downloads` row) so this
 * leaves nothing orphaned. No-op when the id has no `saved_chapters` row. Note: for a source-backed
 * manga a later library/details refresh may re-discover and re-insert the chapter.
 */
interface ChapterDeletionRepository {
    suspend fun deleteChapter(chapterId: Long)
}
