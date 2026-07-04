package me.manga.kira.domain.usecase.details

import me.manga.kira.domain.repository.ChapterDeletionRepository

/**
 * Delete a single saved chapter record (its `saved_chapters` row) from the library DB. The Details
 * VM calls this AFTER deleting the chapter's download, so the record + its on-disk data both go.
 */
class DeleteChapterUseCase(
    private val repository: ChapterDeletionRepository,
) {
    suspend operator fun invoke(chapterId: Long) = repository.deleteChapter(chapterId)
}
