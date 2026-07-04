package me.manga.kira.domain.usecase.reader

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.repository.ChapterBookmarkRepository

/**
 * Observe whether a chapter is bookmarked, keyed by its source `url`.
 *
 * Phase 6.4.x.bookmark (task #217). Contract §6 SRP: one rule — delegate to
 * [ChapterBookmarkRepository.observeBookmark]. Counterpart to [ToggleChapterBookmarkUseCase];
 * both are constructor-injected into the rework Reader VM so the VM's signature reveals exactly
 * which capabilities it consumes (mirrors the Observe/Set reading-mode and Load/Save position
 * verb-split pairs in this package).
 *
 * Emits `false` for a chapter not in the library (no `saved_chapters` row) — see
 * [ChapterBookmarkRepository]. Constructor-injected repo per §6 DIP; Koin binds it in
 * `readerReworkModule` as a `factory`.
 */
class ObserveChapterBookmarkUseCase(
    private val repository: ChapterBookmarkRepository,
) {
    operator fun invoke(chapterUrl: String): Flow<Boolean> =
        repository.observeBookmark(chapterUrl)
}
