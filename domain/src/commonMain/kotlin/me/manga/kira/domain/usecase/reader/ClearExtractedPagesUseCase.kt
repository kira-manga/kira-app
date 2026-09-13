package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.ChapterPagesRepository

/**
 * Best-effort cleanup of the temp images extracted from a downloaded chapter's CBZ archive, so the
 * per-chapter extract dirs don't accumulate unbounded. Non-suspend / fire-and-forget — safe to call
 * from a ViewModel `onCleared()`. The owner is the Manga captured with the leaving chapter.
 * No-op for chapters that were not downloaded as a CBZ.
 */
class ClearExtractedPagesUseCase(
    private val repository: ChapterPagesRepository,
) {
    operator fun invoke(manga: Manga, chapter: Chapter) = repository.clearExtractedPages(manga, chapter)
}
