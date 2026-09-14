package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.ChapterBookmarkRepository

/**
 * Toggle a chapter's bookmark flag within its captured owning [Manga].
 *
 * Phase 6.4.x.bookmark (task #217). Contract §6 SRP: one rule — delegate to
 * [ChapterBookmarkRepository.toggleBookmark]. Counterpart to [ObserveChapterBookmarkUseCase].
 *
 * No-op for a chapter not in the library (no `saved_chapters` row) — see
 * [ChapterBookmarkRepository]. The write flips the legacy `saved_chapters.isBookmarked` column,
 * so the Library `bookmarkedCount` badge re-derives automatically via Room invalidation.
 * Constructor-injected repo per §6 DIP; Koin binds it in `readerReworkModule` as a `factory`.
 *
 * The single-chapter overload returns `true` if its row existed and was flipped, `false` otherwise
 *   (the caller surfaces an "add to Library first" hint — #15).
 */
class ToggleChapterBookmarkUseCase(
    private val repository: ChapterBookmarkRepository,
) {
    suspend operator fun invoke(
        manga: Manga,
        chapterUrl: String,
    ): Boolean = repository.toggleBookmark(manga, chapterUrl)

    suspend operator fun invoke(
        manga: Manga,
        chapterUrls: List<String>,
    ) {
        repository.toggleBookmark(manga, chapterUrls)
    }
}
