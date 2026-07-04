package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.repository.MarkChapterReadRepository

/**
 * Bulk-mark a set of chapters as READ, keyed by their source `url`s.
 *
 * GAP-LIB-02 (per-manga library chapter management). Backs the multi-select "mark read" action on
 * the rework Details chapter list — the native `LibraryMangaScreen` selection action bar offered
 * mark-all-read over the selected set. Delegates to [MarkChapterReadRepository.markRead] (the
 * `List<String>` overload), which resolves each url to its Room id, skips not-in-library urls, and
 * batches the resolved set. Idempotent.
 *
 * Contract §6 SRP: one rule — mark every resolvable chapter read. Constructor-injected repo per §6
 * DIP; Koin binds it as a `factory` in `detailsReworkModule`.
 */
class MarkChaptersReadUseCase(
    private val repository: MarkChapterReadRepository,
) {
    suspend operator fun invoke(chapterUrls: List<String>) {
        repository.markRead(chapterUrls)
    }
}
