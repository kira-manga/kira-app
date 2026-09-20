package me.manga.kira.domain.usecase.reader

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.ProgressHandle
import me.manga.kira.domain.model.progress.ProgressWriteResult
import me.manga.kira.domain.repository.ScopedReadProgressRepository

/** Saves a within-chapter index using the handle acquired for that chapter, never a new handle. */
class SaveScopedPagePositionUseCase(private val repository: ScopedReadProgressRepository) {
    suspend operator fun invoke(handle: ProgressHandle, pageIndex: Int): AppResult<ProgressWriteResult> =
        repository.save(handle, pageIndex)
}
