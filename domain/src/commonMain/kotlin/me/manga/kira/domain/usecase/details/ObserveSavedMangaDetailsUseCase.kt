package me.manga.kira.domain.usecase.details

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.SavedWorkDetails
import me.manga.kira.domain.repository.SavedMangaDetailsRepository

/**
 * Observe a saved manga's details (chapters + read/downloaded/bookmark state) from the local store.
 *
 * Thin pass-through over [SavedMangaDetailsRepository] — same SRP/DIP posture as
 * [me.manga.kira.domain.usecase.library.ObserveInLibraryUseCase]: the Details ViewModel depends
 * on this use case, never on the repository directly. Emits `Success(null)` when the work is not
 * saved; ownership conflicts and storage failures remain explicit `Failure` values.
 *
 * Part of the 2026-05-31 regression fix that restored the offline/local Details path (a
 * Library-opened manga was rendering like a fresh network result, losing its read marks).
 */
class ObserveSavedMangaDetailsUseCase(
    private val repository: SavedMangaDetailsRepository,
) {
    operator fun invoke(work: WorkLocator): Flow<AppResult<SavedWorkDetails?>> =
        repository.observeSavedDetails(work)
}
