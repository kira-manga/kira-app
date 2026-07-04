package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Persist the user's chosen "show buttons" display-toggle value.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.setShowButtons]".
 * Fourth of five per-flag setters in the §150 rung 16 ladder (showSource → showCount →
 * showDetails → showButtons → showTabs). Mirrors [SetLibraryShowSourceUseCase] /
 * [SetLibraryShowCountUseCase] / [SetLibraryShowDetailsUseCase] one-line delegate shape exactly
 * — same observer-echo posture, same SRP, same DIP wiring through Koin's `factory` binding.
 *
 * Why one use case per flag (vs. one bundled `SetLibraryDisplayUseCase(d: LibraryDisplay)`):
 *  - Each toggle flip is its own `LibraryIntent` variant (`OnToggleShowButtons`). A bundled
 *    setter would force every per-toggle reducer to construct a full bundle just to flip
 *    one bit; the read side already does the combine in `:data` cheaply.
 *  - SRP — one use case per intent boundary keeps the reducer narrow ("delegate the value
 *    through to its setter") and matches the established §152 / §154 / §157 / §158 / §159
 *    persistence wirings exactly.
 *
 * Invoked from the VM's `OnToggleShowButtons` handler — the VM updates `state.display`
 * synchronously (so the UI recomposes immediately) AND launches this setter on
 * `viewModelScope` to persist. The bundled `observeDisplay()` Flow observer in `init {}` will
 * re-emit the new snapshot but the resulting state update is idempotent (same booleans →
 * same `state.display`); `StateFlow`'s distinct-emission guard collapses the echo to a no-op
 * recomposition. Same observer-echo posture as [SetLibraryShowSourceUseCase] /
 * [SetLibraryShowCountUseCase] / [SetLibraryShowDetailsUseCase] / the §157 density / §159
 * category / §154 filter persistence wirings.
 *
 * No `:ui` gate today (single sub-rung): the legacy "buttons" surface is the MangaCard bottom
 * action row (resume / mark-read / remove icons) which the rework has not yet ported — there
 * is no rework `:ui` consumer to gate on the new flag yet. The VM-side write path still needs
 * to land so flips originating from the legacy display sheet propagate through
 * `LibraryPrefsRepositoryImpl`'s shared `library_show_buttons` disk cell and the cross-route
 * truth stays in sync. When a future slice ports the bottom action row to the rework
 * `LibraryCard`, gating it on `display.showButtons` will be a 1-file `:ui` follow-on (analogous
 * to rung 16b2 / 16c2 / 16d2 — but lifted *after* the action-row port itself, not before).
 *
 * Constructor-injected [LibraryPrefsRepository] per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * §150 ladder rung 16e (showButtons VM-side wiring).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster131.staleKdocSweep.cascade,
 * Task #587, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-seventh sibling of the cluster57-130 sweep
 * — fourth file of the wave-23 `:domain/usecase/library/` 5-display-
 * setter batch alongside SetLibraryShowSource plus SetLibraryShowCount
 * plus SetLibraryShowDetails plus SetLibraryShowTabs):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.setShowButtons
 *  + fourth-of-five-per-flag-setters-§150-rung-16-ladder + mirrors-Set-
 *  LibraryShowSource-SetLibraryShowCount-SetLibraryShowDetails-one-line-
 *  delegate-shape-exactly-same-observer-echo-posture-same-SRP-same-DIP-
 *  wiring" — LIVE-NOT-STALE + FULFILLED-PREDICTION. LibraryViewModel.kt
 *  L42 import, L120 ctor `private val setLibraryShowButtons:
 *  SetLibraryShowButtonsUseCase`, L524 realization `viewModelScope.launch
 *  { setLibraryShowButtons(value) }` inside the `OnToggleShowButtons`
 *  handler. The nine-axis-Set-sibling chain (sort, sortDirection, filter,
 *  gridDensity, category, showSource, showCount, showDetails, showButtons)
 *  is preserved verbatim across the wave-23 cycle.
 *  (b) "VM-updates-state.display-synchronously + setter-launched-on-
 *  viewModelScope + bundled-observeDisplay-Flow-observer-re-emits-new-
 *  snapshot + state-update-idempotent + StateFlow-distinct-emission-guard-
 *  collapses-echo + no-:ui-gate-today-legacy-MangaCard-bottom-action-row-
 *  not-yet-ported-rework-:ui-consumer-missing + VM-side-write-path-still-
 *  needs-to-land-so-flips-originating-from-legacy-display-sheet-propagate-
 *  through-LibraryPrefsRepositoryImpl-shared-library_show_buttons-disk-
 *  cell-cross-route-truth-stays-in-sync" — LIVE-NOT-STALE + FORECAST-NOT-
 *  YET-FULFILLED. The same optimistic-write-then-flow-re-emits-back
 *  idempotent round-trip pattern as the 93rd plus 95th plus 97th plus
 *  99th plus 101st plus 104th plus 105th plus 106th siblings is preserved
 *  verbatim — echo-coalescing invariant stands across all 9 Set siblings
 *  in the wave-23 cycle. The forecast that the future :ui follow-on
 *  "gating it on display.showButtons will be a 1-file :ui follow-on
 *  analogous to rung 16b2 / 16c2 / 16d2 — but lifted after the action-row
 *  port itself, not before" remains LIVE FORECAST — the rework MangaCard
 *  bottom action row has not yet landed as of 2026-05-28; no rework :ui
 *  consumer to gate today.
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule + §150
 *  ladder rung 16e (showButtons VM-side wiring)" — LIVE-NOT-STALE.
 *  LibraryReworkModule.kt L28 import, L143 `factory {
 *  SetLibraryShowButtonsUseCase(get()) }` realization. The §150 rung 16e
 *  slice is the canonical introducer; fourth-of-five-per-flag-setters
 *  framing stands. The "VM-side-only-no-:ui-gate-today" asymmetry vs
 *  sibling rungs 16b/c/d/f is documented as intentional — write-path-
 *  parity-with-legacy-disk-cell precedes the :ui gate by design. Three
 *  classifications STAND on their own merits. Original Phase 7.x.library.
 *  display.showbuttons-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
class SetLibraryShowButtonsUseCase(
    private val repository: LibraryPrefsRepository,
) {
    suspend operator fun invoke(showButtons: Boolean) {
        repository.setShowButtons(showButtons)
    }
}
