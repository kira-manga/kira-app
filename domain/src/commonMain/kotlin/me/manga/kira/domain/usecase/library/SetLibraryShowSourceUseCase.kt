package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Persist the user's chosen "show source" display-toggle value.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.setShowSource]".
 * First of five per-flag setters in the §150 rung 16 ladder (showSource → showCount →
 * showDetails → showButtons → showTabs). Mirrors [SetLibraryGridDensityUseCase] /
 * [SetLibraryCategoryUseCase] / [SetLibraryFilterUseCase] one-line delegate shape — the VM
 * holds a narrow, intent-specific dependency rather than the broader repository handle.
 *
 * Why one use case per flag (vs. one bundled `SetLibraryDisplayUseCase(d: LibraryDisplay)`):
 *  - Each toggle flip is its own `LibraryIntent` variant (e.g. `OnToggleShowSource`). A bundled
 *    setter would force every per-toggle reducer to construct a full bundle just to flip one
 *    bit; the read side already does the combine in `:data` cheaply.
 *  - SRP — one use case per intent boundary keeps the reducer narrow ("delegate the value
 *    through to its setter") and matches the established §152 / §154 / §157 / §158 / §159
 *    persistence wirings exactly.
 *
 * Invoked from the VM's `OnToggleShowSource` handler — the VM updates `state.display`
 * synchronously (so the UI recomposes immediately) AND launches this setter on
 * `viewModelScope` to persist. The bundled `observeDisplay()` Flow observer in `init {}` will
 * re-emit the new snapshot but the resulting state update is idempotent (same booleans →
 * same `state.display`); `StateFlow`'s distinct-emission guard collapses the echo to a no-op
 * recomposition. Same observer-echo posture as the §157 density / §159 category / §154
 * filter persistence wirings.
 *
 * Constructor-injected [LibraryPrefsRepository] per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * §150 ladder rung 16b (showSource end-to-end).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster131.staleKdocSweep.cascade,
 * Task #587, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-fourth sibling of the cluster57-130 sweep
 * — first file of the wave-23 `:domain/usecase/library/` 5-display-setter
 * batch alongside SetLibraryShowCount plus SetLibraryShowDetails plus
 * SetLibraryShowButtons plus SetLibraryShowTabs; opens cluster131):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.setShowSource
 *  + first-of-five-per-flag-setters-§150-rung-16-ladder + mirrors-Set-
 *  LibraryGridDensity-SetLibraryCategory-SetLibraryFilter-one-line-
 *  delegate-shape-narrow-intent-specific-dependency" — LIVE-NOT-STALE +
 *  FULFILLED-PREDICTION. LibraryViewModel.kt L39 import, L106 ctor
 *  `private val setLibraryShowSource: SetLibraryShowSourceUseCase`, L465
 *  realization `viewModelScope.launch { setLibraryShowSource(value) }`
 *  inside the `OnToggleShowSource` handler. The six-axis-Set-sibling chain
 *  (sort, sortDirection, filter, gridDensity, category, showSource) is
 *  preserved verbatim across the wave-23 cycle.
 *  (b) "VM-updates-state.display-synchronously + setter-launched-on-
 *  viewModelScope + bundled-observeDisplay-Flow-observer-re-emits-new-
 *  snapshot + state-update-idempotent + StateFlow-distinct-emission-guard-
 *  collapses-echo + same-observer-echo-posture-as-§157-density-§159-
 *  category-§154-filter-persistence" — LIVE-NOT-STALE + FULFILLED-
 *  PREDICTION. The same optimistic-write-then-flow-re-emits-back
 *  idempotent round-trip pattern as the 93rd plus 95th plus 97th plus 99th
 *  plus 101st siblings (SetLibrarySort plus SetLibrarySortDirection plus
 *  SetLibraryFilter plus SetLibraryGridDensity plus SetLibraryCategory) is
 *  preserved verbatim — echo-coalescing invariant stands across all 6 Set
 *  siblings in the wave-23 cycle. The bundled `ObserveLibraryDisplay`
 *  observer (103rd sibling, cluster130) re-emits idempotent snapshots on
 *  every per-flag write — single-bundle read side, five-narrow-setters
 *  write side.
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule + §150
 *  ladder rung 16b (showSource end-to-end)" — LIVE-NOT-STALE.
 *  LibraryReworkModule.kt L25 import, L140 `factory {
 *  SetLibraryShowSourceUseCase(get()) }` realization. The §150 rung 16b
 *  slice is the canonical introducer; first-of-five-per-flag-setters
 *  framing stands. Three classifications STAND on their own merits.
 *  Original Phase 7.x.library.display.showsource-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
class SetLibraryShowSourceUseCase(
    private val repository: LibraryPrefsRepository,
) {
    suspend operator fun invoke(showSource: Boolean) {
        repository.setShowSource(showSource)
    }
}
