package me.manga.kira.domain.usecase.downloads

import me.manga.kira.domain.repository.DownloadsActionRepository

/**
 * Cancel ALL in-flight downloads (top-bar "Stop"). Delegates to
 * [DownloadsActionRepository.cancelAllDownloads], which both marks the rows FAILED and stops the
 * underlying worker/coroutine — unlike looping per-chapter cancel, which only prunes rows.
 */
class CancelAllDownloadsUseCase(
    private val repository: DownloadsActionRepository,
) {
    suspend operator fun invoke(): Result<Unit> = repository.cancelAllDownloads()
}
