package me.manga.kira.domain.usecase.reader

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.repository.ScopedReadProgressRepository

/** Creates a durable work-wide clear fence, including when no chapter has been seen. */
class ClearScopedWorkProgressUseCase(private val repository: ScopedReadProgressRepository) {
    suspend operator fun invoke(work: WorkLocator): AppResult<Unit> = repository.clearWork(work)
}
