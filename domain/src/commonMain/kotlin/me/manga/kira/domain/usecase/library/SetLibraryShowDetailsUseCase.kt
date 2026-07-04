package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Persist the user's chosen "show details" display-toggle value.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.setShowDetails]".
 * Third of five per-flag setters in the §150 rung 16 ladder (showSource → showCount →
 * showDetails → showButtons → showTabs). Mirrors [SetLibraryShowSourceUseCase] /
 * [SetLibraryShowCountUseCase] one-line delegate shape exactly — same observer-echo
 * posture, same SRP, same DIP wiring through Koin's `factory` binding.
 *
 * Why one use case per flag (vs. one bundled `SetLibraryDisplayUseCase(d: LibraryDisplay)`):
 *  - Each toggle flip is its own `LibraryIntent` variant (`OnToggleShowDetails`). A bundled
 *    setter would force every per-toggle reducer to construct a full bundle just to flip
 *    one bit; the read side already does the combine in `:data` cheaply.
 *  - SRP — one use case per intent boundary keeps the reducer narrow ("delegate the value
 *    through to its setter") and matches the established §152 / §154 / §157 / §158 / §159
 *    persistence wirings exactly.
 *
 * Invoked from the VM's `OnToggleShowDetails` handler — the VM updates `state.display`
 * synchronously (so the UI recomposes immediately) AND launches this setter on
 * `viewModelScope` to persist. The bundled `observeDisplay()` Flow observer in `init {}` will
 * re-emit the new snapshot but the resulting state update is idempotent (same booleans →
 * same `state.display`); `StateFlow`'s distinct-emission guard collapses the echo to a no-op
 * recomposition. Same observer-echo posture as [SetLibraryShowSourceUseCase] /
 * [SetLibraryShowCountUseCase] / the §157 density / §159 category / §154 filter persistence
 * wirings.
 *
 * UI gates landing in rung 16d2 (1-file `:ui` follow-on): `showDetails` is the legacy
 * "Details" display surface — gates BOTH the §165 cardlastread caption AND the §166
 * cardprogress caption together. The legacy `DisplayOptionsSection` bundles these under a
 * single "Details" switch row; the rework preserves that bundling at the flag level (one
 * flag, two `:ui` gates) — same persisted disk cell (`library_show_details`), same
 * observable behaviour as the legacy, same "two-captions-per-flag" pattern established by
 * rung 16c (`showCount` gates §163 + §164).
 *
 * Constructor-injected [LibraryPrefsRepository] per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * §150 ladder rung 16d (showDetails end-to-end).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster131.staleKdocSweep.cascade,
 * Task #587, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-sixth sibling of the cluster57-130 sweep
 * — third file of the wave-23 `:domain/usecase/library/` 5-display-setter
 * batch alongside SetLibraryShowSource plus SetLibraryShowCount plus
 * SetLibraryShowButtons plus SetLibraryShowTabs):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.setShowDetails
 *  + third-of-five-per-flag-setters-§150-rung-16-ladder + mirrors-Set-
 *  LibraryShowSource-SetLibraryShowCount-one-line-delegate-shape-exactly-
 *  same-observer-echo-posture-same-SRP-same-DIP-wiring" — LIVE-NOT-STALE +
 *  FULFILLED-PREDICTION. LibraryViewModel.kt L41 import, L113 ctor
 *  `private val setLibraryShowDetails: SetLibraryShowDetailsUseCase`,
 *  L502 realization `viewModelScope.launch { setLibraryShowDetails(value)
 *  }` inside the `OnToggleShowDetails` handler. The eight-axis-Set-sibling
 *  chain (sort, sortDirection, filter, gridDensity, category, showSource,
 *  showCount, showDetails) is preserved verbatim across the wave-23
 *  cycle.
 *  (b) "VM-updates-state.display-synchronously + setter-launched-on-
 *  viewModelScope + bundled-observeDisplay-Flow-observer-re-emits-new-
 *  snapshot + state-update-idempotent + StateFlow-distinct-emission-guard-
 *  collapses-echo + showDetails-gates-§165-cardlastread-§166-cardprogress-
 *  captions-bundled-one-flag-two-:ui-gates-legacy-bundles-under-single-
 *  Details-switch-row + same-two-captions-per-flag-pattern-as-rung-16c-
 *  showCount-§163-§164" — LIVE-NOT-STALE + FULFILLED-PREDICTION. The same
 *  optimistic-write-then-flow-re-emits-back idempotent round-trip pattern
 *  as the 93rd plus 95th plus 97th plus 99th plus 101st plus 104th plus
 *  105th siblings is preserved verbatim — echo-coalescing invariant
 *  stands across all 8 Set siblings in the wave-23 cycle. The bundled
 *  ObserveLibraryDisplay observer re-emits idempotent snapshots on every
 *  per-flag write. The two-captions-per-flag bundling (§165 + §166 under
 *  showDetails) mirrors rung 16c exactly.
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule + §150
 *  ladder rung 16d (showDetails end-to-end)" — LIVE-NOT-STALE.
 *  LibraryReworkModule.kt L27 import, L142 `factory {
 *  SetLibraryShowDetailsUseCase(get()) }` realization. The §150 rung 16d
 *  slice is the canonical introducer; third-of-five-per-flag-setters
 *  framing stands. Three classifications STAND on their own merits.
 *  Original Phase 7.x.library.display.showdetails-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
class SetLibraryShowDetailsUseCase(
    private val repository: LibraryPrefsRepository,
) {
    suspend operator fun invoke(showDetails: Boolean) {
        repository.setShowDetails(showDetails)
    }
}
