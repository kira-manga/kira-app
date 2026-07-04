package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Observe the user's persisted Library items-per-row (grid column count) as a live stream.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.observeItemsPerRow]".
 * Mirrors [ObserveLibraryGridDensityUseCase] exactly — same one-line delegate shape that gives
 * the VM a narrow, intent-specific dependency rather than a wide repository handle.
 *
 * The Library VM's `init {}` collects this flow and projects each emission into
 * [me.manga.kira.presentation.library.LibraryState.itemsPerRow]. Like the grid-density axis,
 * `applyView` is NOT re-run because the column count only changes how the same `items` list is
 * laid out (the `:ui` grid recomposes on the `state.itemsPerRow` flip because its `GridCells`
 * selection is derived from it). The emitted value is `0` (Auto) when nothing has been persisted
 * yet, or `1..8` for an explicit fixed column count — same Int semantics as native.
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 */
class ObserveLibraryItemsPerRowUseCase(
    private val repository: LibraryPrefsRepository,
) {
    operator fun invoke(): Flow<Int> = repository.observeItemsPerRow()
}
