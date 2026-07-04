package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Persist the user's chosen "show tabs" display-toggle value.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.setShowTabs]".
 * Fifth and final per-flag setter in the §150 rung 16 ladder (showSource → showCount →
 * showDetails → showButtons → showTabs). Mirrors [SetLibraryShowSourceUseCase] /
 * [SetLibraryShowCountUseCase] / [SetLibraryShowDetailsUseCase] /
 * [SetLibraryShowButtonsUseCase] one-line delegate shape exactly — same observer-echo
 * posture, same SRP, same DIP wiring through Koin's `factory` binding.
 *
 * Why one use case per flag (vs. one bundled `SetLibraryDisplayUseCase(d: LibraryDisplay)`):
 *  - Each toggle flip is its own `LibraryIntent` variant (`OnToggleShowTabs`). A bundled
 *    setter would force every per-toggle reducer to construct a full bundle just to flip
 *    one bit; the read side already does the combine in `:data` cheaply.
 *  - SRP — one use case per intent boundary keeps the reducer narrow ("delegate the value
 *    through to its setter") and matches the established §152 / §154 / §157 / §158 / §159
 *    persistence wirings exactly.
 *
 * Invoked from the VM's `OnToggleShowTabs` handler — the VM updates `state.display`
 * synchronously (so the UI recomposes immediately) AND launches this setter on
 * `viewModelScope` to persist. The bundled `observeDisplay()` Flow observer in `init {}`
 * will re-emit the new snapshot but the resulting state update is idempotent (same booleans
 * → same `state.display`); `StateFlow`'s distinct-emission guard collapses the echo to a
 * no-op recomposition. Same observer-echo posture as [SetLibraryShowSourceUseCase] /
 * [SetLibraryShowCountUseCase] / [SetLibraryShowDetailsUseCase] /
 * [SetLibraryShowButtonsUseCase] / the §157 density / §159 category / §154 filter
 * persistence wirings.
 *
 * `:ui` gate present (single sub-rung): unlike rung 16e (showButtons), the `showTabs` flag
 * has an existing rework `:ui` consumer — the §158 `CategoryTabs` row at the screen level
 * (NAN / LIKED / WATCHING_NOW tabs above the grid). The 1-file `:ui` gate lifts the
 * `CategoryTabs(...)` call into `if (state.display.showTabs) { ... }`, which fits inside
 * the same ≤5-file commit cap as the VM wiring (the gate is one `if` at the screen level,
 * not per-card — far simpler than the 16b2 / 16c2 / 16d2 per-card caption gates).
 *
 * Constructor-injected [LibraryPrefsRepository] per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * §150 ladder rung 16f (showTabs end-to-end — closes the 5/5 per-flag vertical ladder).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster131.staleKdocSweep.cascade,
 * Task #587, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eighth sibling of the cluster57-130 sweep
 * — fifth and closing file of the wave-23 `:domain/usecase/library/`
 * 5-display-setter batch alongside SetLibraryShowSource plus SetLibrary-
 * ShowCount plus SetLibraryShowDetails plus SetLibraryShowButtons; closes
 * cluster131):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.setShowTabs +
 *  fifth-and-final-per-flag-setter-§150-rung-16-ladder + mirrors-Set-
 *  LibraryShowSource-SetLibraryShowCount-SetLibraryShowDetails-Set-
 *  LibraryShowButtons-one-line-delegate-shape-exactly-same-observer-echo-
 *  posture-same-SRP-same-DIP-wiring" — LIVE-NOT-STALE + FULFILLED-
 *  PREDICTION. LibraryViewModel.kt L43 import, L126 ctor `private val
 *  setLibraryShowTabs: SetLibraryShowTabsUseCase`, L547 realization
 *  `viewModelScope.launch { setLibraryShowTabs(value) }` inside the
 *  `OnToggleShowTabs` handler. The ten-axis-Set-sibling chain (sort,
 *  sortDirection, filter, gridDensity, category, showSource, showCount,
 *  showDetails, showButtons, showTabs) is preserved verbatim across the
 *  wave-23 cycle — closes the 5/5 per-flag vertical ladder.
 *  (b) "VM-updates-state.display-synchronously + setter-launched-on-
 *  viewModelScope + bundled-observeDisplay-Flow-observer-re-emits-new-
 *  snapshot + state-update-idempotent + StateFlow-distinct-emission-guard-
 *  collapses-echo + :ui-gate-present-single-sub-rung-unlike-rung-16e-
 *  showButtons + showTabs-flag-has-existing-rework-:ui-consumer-§158-
 *  CategoryTabs-row-at-screen-level-NAN-LIKED-WATCHING_NOW-tabs-above-
 *  grid + 1-file-:ui-gate-lifts-CategoryTabs-call-into-if-state.display.
 *  showTabs-block-fits-inside-≤5-file-commit-cap-far-simpler-than-16b2-
 *  16c2-16d2-per-card-caption-gates" — LIVE-NOT-STALE + FULFILLED-
 *  PREDICTION. The same optimistic-write-then-flow-re-emits-back
 *  idempotent round-trip pattern as the 93rd plus 95th plus 97th plus
 *  99th plus 101st plus 104th plus 105th plus 106th plus 107th siblings
 *  is preserved verbatim — echo-coalescing invariant stands across all
 *  10 Set siblings in the wave-23 cycle. The :ui-gate-at-screen-level
 *  asymmetry vs the per-card 16b2/c2/d2 gates is preserved verbatim — one
 *  if-block, not per-card.
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule + §150
 *  ladder rung 16f (showTabs end-to-end — closes the 5/5 per-flag vertical
 *  ladder)" — LIVE-NOT-STALE. LibraryReworkModule.kt L29 import, L144
 *  `factory { SetLibraryShowTabsUseCase(get()) }` realization. The §150
 *  rung 16f slice is the canonical introducer; fifth-and-final-per-flag-
 *  setter framing stands. Closes cluster131; remaining 2 library/ files
 *  (ToggleMangaLiked + ToggleMangaWatchingNow) deferred to cluster132.
 *  Three classifications STAND on their own merits. Original Phase 7.x.
 *  library.display.showtabs-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class SetLibraryShowTabsUseCase(
    private val repository: LibraryPrefsRepository,
) {
    suspend operator fun invoke(showTabs: Boolean) {
        repository.setShowTabs(showTabs)
    }
}
