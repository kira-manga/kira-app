package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.repository.MarkChapterReadRepository

/**
 * Toggle a chapter's READ flag (read↔unread), keyed by its source `url`.
 *
 * GAP-LIB-02 (per-manga library chapter management on the rework Details screen). Restores the
 * native `LibraryMangaScreen` per-chapter RemoveRedEye toggle that the rework dropped — the rework
 * Details screen rendered read state (dimmed rows) but offered no affordance to flip it. Delegates
 * to [MarkChapterReadRepository.toggleRead]; no-op for a chapter with no saved-library row
 * (preserves the legacy "read state is meaningful only for saved chapters" semantics).
 *
 * Contract §6 SRP: one rule — flip the read flag. Constructor-injected repo per §6 DIP; Koin binds
 * it as a `factory` in `detailsReworkModule`.
 */
class ToggleChapterReadUseCase(
    private val repository: MarkChapterReadRepository,
) {
    suspend operator fun invoke(chapterUrl: String) {
        repository.toggleRead(chapterUrl)
    }
}
