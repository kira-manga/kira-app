package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.repository.ChapterBookmarkRepository

/**
 * Toggle a chapter's bookmark flag, keyed by its source `url`.
 *
 * Phase 6.4.x.bookmark (task #217). Contract §6 SRP: one rule — delegate to
 * [ChapterBookmarkRepository.toggleBookmark]. Counterpart to [ObserveChapterBookmarkUseCase].
 *
 * No-op for a chapter not in the library (no `saved_chapters` row) — see
 * [ChapterBookmarkRepository]. The write flips the legacy `saved_chapters.isBookmarked` column,
 * so the Library `bookmarkedCount` badge re-derives automatically via Room invalidation.
 * Constructor-injected repo per §6 DIP; Koin binds it in `readerReworkModule` as a `factory`.
 *
 * @return `true` if the chapter was in-library and its flag flipped; `false` if not in-library
 *   (the caller surfaces an "add to Library first" hint — #15).
 */
class ToggleChapterBookmarkUseCase(
    private val repository: ChapterBookmarkRepository,
) {
    suspend operator fun invoke(chapterUrl: String): Boolean =
        repository.toggleBookmark(chapterUrl)
}
