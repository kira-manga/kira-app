package me.manga.kira.domain.usecase.reader

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.repository.ScopedReadProgressRepository

/** Clears only one scoped chapter; sibling Reader handles keep their current generations. */
class ClearScopedChapterProgressUseCase(private val repository: ScopedReadProgressRepository) {
    suspend operator fun invoke(chapter: ChapterLocator): AppResult<Unit> = repository.clearChapter(chapter)
}
