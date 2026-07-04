package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Persist the user's chosen items-per-row (grid column count: 0 = Auto, 1..8 = fixed columns).
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.setItemsPerRow]".
 * Counterpart to [ObserveLibraryItemsPerRowUseCase]; both are constructor-injected into the
 * Library VM so the VM holds narrow, intent-specific surfaces rather than the broader repository
 * handle. Mirrors [SetLibraryGridDensityUseCase] exactly.
 *
 * Invoked from the VM's `OnItemsPerRowChange` handler — the VM updates state synchronously (so
 * the UI recomposes immediately) AND launches this setter on `viewModelScope` to persist. The
 * Flow observer in `init {}` will re-emit the new value but the resulting state update is
 * idempotent (same count → same `state.itemsPerRow`); `StateFlow`'s distinct-emission guard
 * collapses the echo to a no-op recomposition. Same observer-echo posture as the grid-density
 * persistence wiring.
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 */
class SetLibraryItemsPerRowUseCase(
    private val repository: LibraryPrefsRepository,
) {
    suspend operator fun invoke(itemsPerRow: Int) {
        repository.setItemsPerRow(itemsPerRow)
    }
}
