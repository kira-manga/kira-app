package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.LibraryRefreshRepository

/**
 * Observe the terminal outcome of the most recent inline library-refresh run (Desktop/iOS).
 *
 * Thin pass-through over [LibraryRefreshRepository.observeLastRefreshResult]. Emits `null` until a
 * run completes, then the run's [AppResult] (`Success(newChapterCount)` / `Failure(error)`). The
 * rework `LibraryViewModel` collects this to surface a refresh failure as an error effect rather
 * than letting a fully-failed pull-to-refresh look identical to "library is up to date".
 *
 * Constructor injection per contract §6 DIP. Koin binds as a `factory` in
 * [me.manga.kira.di.libraryReworkModule].
 */
class ObserveLibraryRefreshResultUseCase(
    private val repository: LibraryRefreshRepository,
) {
    operator fun invoke(): Flow<AppResult<Int>?> = repository.observeLastRefreshResult()
}
