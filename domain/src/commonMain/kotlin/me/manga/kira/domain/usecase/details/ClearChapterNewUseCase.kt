package me.manga.kira.domain.usecase.details

import me.manga.kira.domain.repository.ChapterNewBadgeRepository

/**
 * Clear a chapter's NEW badge when it is opened from the Details screen — without marking it read
 * (native clears `isNew` on chapter click). Fire-and-forget; no-op for a non-saved chapter.
 */
class ClearChapterNewUseCase(
    private val repository: ChapterNewBadgeRepository,
) {
    suspend operator fun invoke(chapterUrl: String) = repository.clearNew(chapterUrl)
}
