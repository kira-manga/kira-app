package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Persist the user's chosen [LibraryFilter].
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.setFilter]". Counterpart
 * to [ObserveLibraryFilterUseCase]; both are constructor-injected into the Library VM so the VM
 * holds narrow, intent-specific surfaces rather than the broader repository handle.
 *
 * Invoked from the VM's `OnFilterChange` handler — the VM updates state synchronously (so the UI
 * recomposes immediately) AND launches this setter on `viewModelScope` to persist. The Flow
 * observer in `init {}` will re-emit the new value but the resulting `applyView` call is idempotent
 * (same filter → same `LibraryState.items`); `StateFlow`'s distinct-emission guard collapses the
 * echo to a no-op recomposition. Same observer-echo posture as the §152 sort-persistence wiring.
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster129.staleKdocSweep.cascade,
 * Task #585, 2026-05-28): classified as follows after recursive symbol
 * verification (ninety-seventh sibling of the cluster57-128 sweep —
 * second file of the wave-23 `:domain/usecase/library/` 4-file filter
 * plus grid-density Observe/Set-pair batch alongside ObserveLibrary-
 * Filter plus ObserveLibraryGridDensity plus SetLibraryGridDensity):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.setFilter +
 *  counterpart-to-ObserveLibraryFilter + narrow-intent-specific-
 *  surfaces" — LIVE-NOT-STALE + FULFILLED-PREDICTION. LibraryViewModel.kt
 *  L37 import, L99 ctor `private val setLibraryFilter:
 *  SetLibraryFilterUseCase`, L407 realization `viewModelScope.launch
 *  { setLibraryFilter(filter) }` inside the `onFilterChange(filter)`
 *  handler — VM L400-407 wraps the `updateState { it.copy(filter =
 *  filter, items = applyView(...)) }` optimistic write plus persist
 *  launch.
 *  (b) "VM-updates-state-synchronously-for-immediate-UI-recompose +
 *  setter-launched-on-viewModelScope-to-persist + Flow-observer-re-
 *  emits + applyView-idempotent + StateFlow-distinct-emission-guard-
 *  collapses-echo-to-no-op + same-observer-echo-posture-as-§152-sort-
 *  persistence" — LIVE-NOT-STALE + FULFILLED-PREDICTION. The same
 *  optimistic-write-then-flow-re-emits-back idempotent round-trip
 *  pattern as the 93rd + 95th siblings (SetLibrarySort +
 *  SetLibrarySortDirection) is preserved verbatim — the echo-coalescing
 *  invariant stands across all 3 Set* siblings in the wave-23 cycle.
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-
 *  NOT-STALE. LibraryReworkModule.kt L23 import, L133 `factory {
 *  SetLibraryFilterUseCase(get()) }` realization. Closes 96th + 97th
 *  filter Observe/Set pair. Three classifications STAND on their own
 *  merits. Original Phase 7.x.library.filter.persist-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class SetLibraryFilterUseCase(
    private val repository: LibraryPrefsRepository,
) {
    suspend operator fun invoke(filter: LibraryFilter) {
        repository.setFilter(filter)
    }
}
