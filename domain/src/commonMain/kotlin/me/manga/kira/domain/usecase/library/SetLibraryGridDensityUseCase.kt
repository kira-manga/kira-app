package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.model.library.GridDensity
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Persist the user's chosen [GridDensity].
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.setGridDensity]".
 * Counterpart to [ObserveLibraryGridDensityUseCase]; both are constructor-injected into the
 * Library VM so the VM holds narrow, intent-specific surfaces rather than the broader repository
 * handle.
 *
 * Invoked from the VM's `OnGridDensityChange` handler — the VM updates state synchronously (so
 * the UI recomposes immediately) AND launches this setter on `viewModelScope` to persist. The
 * Flow observer in `init {}` will re-emit the new value but the resulting state update is
 * idempotent (same density → same `state.gridDensity`); `StateFlow`'s distinct-emission guard
 * collapses the echo to a no-op recomposition. Same observer-echo posture as the §152 sort-
 * persistence and §154 filter-persistence wiring.
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster129.staleKdocSweep.cascade,
 * Task #585, 2026-05-28): classified as follows after recursive symbol
 * verification (ninety-ninth sibling of the cluster57-128 sweep —
 * fourth and closing file of the wave-23 `:domain/usecase/library/`
 * 4-file filter plus grid-density Observe/Set-pair batch alongside
 * ObserveLibraryFilter plus SetLibraryFilter plus ObserveLibrary-
 * GridDensity; closes cluster129):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.setGrid-
 *  Density + counterpart-to-ObserveLibraryGridDensity + narrow-intent-
 *  specific-surfaces" — LIVE-NOT-STALE + FULFILLED-PREDICTION.
 *  LibraryViewModel.kt L38 import, L101 ctor `private val
 *  setLibraryGridDensity: SetLibraryGridDensityUseCase`, L445
 *  realization `viewModelScope.launch { setLibraryGridDensity(density)
 *  }` inside the `OnGridDensityChange` handler. VM L438 KDoc reference
 *  preserved: "persist call is launched on `viewModelScope` and the
 *  `observeLibraryGridDensity` flow in ... re-emits".
 *  (b) "VM-updates-state-synchronously + setter-launched-on-viewModel-
 *  Scope + Flow-observer-re-emits + state-update-idempotent +
 *  StateFlow-distinct-emission-guard-collapses-echo + same-observer-
 *  echo-posture-as-§152-sort-persistence-and-§154-filter-persistence" —
 *  LIVE-NOT-STALE + FULFILLED-PREDICTION. The same optimistic-write-
 *  then-flow-re-emits-back idempotent round-trip as the 93rd + 95th +
 *  97th siblings (SetLibrarySort + SetLibrarySortDirection +
 *  SetLibraryFilter) is preserved verbatim — echo-coalescing invariant
 *  stands across all 4 Set* siblings in the wave-23 cycle.
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-
 *  NOT-STALE. LibraryReworkModule.kt L24 import, L135 `factory {
 *  SetLibraryGridDensityUseCase(get()) }` realization. Closes 98th +
 *  99th grid-density Observe/Set pair AND closes cluster129 (filter
 *  pair + grid-density pair = 4 files); remaining 11 library/ files
 *  split across cluster130 (category + lastUpdated + display observer)
 *  + cluster131 (remaining display setters + toggle pair). Three
 *  classifications STAND on their own merits. Original Phase 7.x.
 *  library.display.persist-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class SetLibraryGridDensityUseCase(
    private val repository: LibraryPrefsRepository,
) {
    suspend operator fun invoke(density: GridDensity) {
        repository.setGridDensity(density)
    }
}
