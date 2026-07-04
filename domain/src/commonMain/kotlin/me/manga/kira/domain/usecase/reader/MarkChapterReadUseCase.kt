package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.repository.MarkChapterReadRepository

/**
 * Mark a chapter as READ, keyed by its source `url` (Reader-convergence R3b).
 *
 * Restores the verb the rework Reader was missing: the legacy reader marked the current chapter
 * read on page-advance / next-chapter, feeding the Library `readCount` + the UNREAD filter
 * (`saved_chapters.isRead`). Contract §6 SRP: one rule — delegate to
 * [MarkChapterReadRepository.markRead].
 *
 * **In-library only / no-op when no saved row.** A `saved_chapters` row exists only once its manga
 * is in the library, so the write is a no-op for a chapter the user has not added — see
 * [MarkChapterReadRepository]. The mark sets the legacy `saved_chapters.isRead` column, so the
 * Library `readCount` + UNREAD filter re-derive automatically via Room invalidation.
 *
 * **NOT incognito-gated** (legacy parity): unlike [RecordHistoryUseCase], the legacy mark-read was
 * never behind the incognito flag — read state tracks library progress, not a browsing trail.
 *
 * Constructor-injected repo per §6 DIP; Koin binds it in `readerReworkModule` as a `factory`.
 */
class MarkChapterReadUseCase(
    private val repository: MarkChapterReadRepository,
) {
    suspend operator fun invoke(chapterUrl: String) {
        repository.markRead(chapterUrl)
    }
}
