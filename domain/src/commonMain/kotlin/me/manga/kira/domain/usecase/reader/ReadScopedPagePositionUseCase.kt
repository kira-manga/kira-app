package me.manga.kira.domain.usecase.reader

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.repository.ScopedReadProgressRepository

/** Reads without creating progress state; absence stays distinct from an explicit first page. */
class ReadScopedPagePositionUseCase(private val repository: ScopedReadProgressRepository) {
    suspend operator fun invoke(chapter: ChapterLocator): AppResult<Int?> = repository.readPosition(chapter)
}
