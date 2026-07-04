package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.repository.ChapterPagesRepository

/**
 * Best-effort cleanup of the temp images extracted from a downloaded chapter's CBZ archive, so the
 * per-chapter extract dirs don't accumulate unbounded. Non-suspend / fire-and-forget — safe to call
 * from a ViewModel `onCleared()`. No-op for chapters that were not downloaded as a CBZ.
 */
class ClearExtractedPagesUseCase(
    private val repository: ChapterPagesRepository,
) {
    operator fun invoke(chapter: Chapter) = repository.clearExtractedPages(chapter)
}
