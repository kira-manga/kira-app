package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Persist the user's chosen "show count" display-toggle value.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.setShowCount]".
 * Second of five per-flag setters in the §150 rung 16 ladder (showSource → showCount →
 * showDetails → showButtons → showTabs). Mirrors [SetLibraryShowSourceUseCase] one-line
 * delegate shape exactly — same observer-echo posture, same SRP, same DIP wiring through
 * Koin's `factory` binding.
 *
 * Why one use case per flag (vs. one bundled `SetLibraryDisplayUseCase(d: LibraryDisplay)`):
 *  - Each toggle flip is its own `LibraryIntent` variant (`OnToggleShowCount`). A bundled
 *    setter would force every per-toggle reducer to construct a full bundle just to flip
 *    one bit; the read side already does the combine in `:data` cheaply.
 *  - SRP — one use case per intent boundary keeps the reducer narrow ("delegate the value
 *    through to its setter") and matches the established §152 / §154 / §157 / §158 / §159
 *    persistence wirings exactly.
 *
 * Invoked from the VM's `OnToggleShowCount` handler — the VM updates `state.display`
 * synchronously (so the UI recomposes immediately) AND launches this setter on
 * `viewModelScope` to persist. The bundled `observeDisplay()` Flow observer in `init {}` will
 * re-emit the new snapshot but the resulting state update is idempotent (same booleans →
 * same `state.display`); `StateFlow`'s distinct-emission guard collapses the echo to a no-op
 * recomposition. Same observer-echo posture as [SetLibraryShowSourceUseCase] / the §157
 * density / §159 category / §154 filter persistence wirings.
 *
 * UI gates landing in rung 16c2 (1-file `:ui` follow-on): `showCount` is the legacy "Count"
 * display surface — gates BOTH the §163 carddownloaded caption AND the §164 cardbookmarks
 * caption together. The legacy `DisplayOptionsSection` bundles these under a single "Count"
 * switch row; the rework preserves that bundling at the flag level (one flag, two `:ui`
 * gates) rather than splitting into separate `showDownloaded` / `showBookmarks` flags.
 *
 * Constructor-injected [LibraryPrefsRepository] per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * §150 ladder rung 16c (showCount end-to-end).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster131.staleKdocSweep.cascade,
 * Task #587, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-fifth sibling of the cluster57-130 sweep
 * — second file of the wave-23 `:domain/usecase/library/` 5-display-
 * setter batch alongside SetLibraryShowSource plus SetLibraryShowDetails
 * plus SetLibraryShowButtons plus SetLibraryShowTabs):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.setShowCount +
 *  second-of-five-per-flag-setters-§150-rung-16-ladder + mirrors-Set-
 *  LibraryShowSource-one-line-delegate-shape-exactly-same-observer-echo-
 *  posture-same-SRP-same-DIP-wiring" — LIVE-NOT-STALE + FULFILLED-
 *  PREDICTION. LibraryViewModel.kt L40 import, L110 ctor `private val
 *  setLibraryShowCount: SetLibraryShowCountUseCase`, L483 realization
 *  `viewModelScope.launch { setLibraryShowCount(value) }` inside the
 *  `OnToggleShowCount` handler. The seven-axis-Set-sibling chain (sort,
 *  sortDirection, filter, gridDensity, category, showSource, showCount)
 *  is preserved verbatim across the wave-23 cycle.
 *  (b) "VM-updates-state.display-synchronously + setter-launched-on-
 *  viewModelScope + bundled-observeDisplay-Flow-observer-re-emits-new-
 *  snapshot + state-update-idempotent + StateFlow-distinct-emission-guard-
 *  collapses-echo + showCount-gates-§163-carddownloaded-§164-cardbookmarks-
 *  captions-bundled-one-flag-two-:ui-gates-legacy-bundles-under-single-
 *  Count-switch-row" — LIVE-NOT-STALE + FULFILLED-PREDICTION. The same
 *  optimistic-write-then-flow-re-emits-back idempotent round-trip pattern
 *  as the 93rd plus 95th plus 97th plus 99th plus 101st plus 104th
 *  siblings (SetLibrarySort plus SetLibrarySortDirection plus SetLibrary-
 *  Filter plus SetLibraryGridDensity plus SetLibraryCategory plus
 *  SetLibraryShowSource) is preserved verbatim — echo-coalescing
 *  invariant stands across all 7 Set siblings in the wave-23 cycle. The
 *  bundled ObserveLibraryDisplay observer re-emits idempotent snapshots
 *  on every per-flag write. The two-captions-per-flag bundling (§163 +
 *  §164 under showCount) is preserved verbatim from the legacy
 *  DisplayOptionsSection.
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule + §150
 *  ladder rung 16c (showCount end-to-end)" — LIVE-NOT-STALE.
 *  LibraryReworkModule.kt L26 import, L141 `factory {
 *  SetLibraryShowCountUseCase(get()) }` realization. The §150 rung 16c
 *  slice is the canonical introducer; second-of-five-per-flag-setters
 *  framing stands. Cross-ref to ObserveLibraryDisplay (103rd sibling)
 *  confirms the bundle-read-side single-bundle-narrow-setters write-side
 *  posture stands. Three classifications STAND on their own merits.
 *  Original Phase 7.x.library.display.showcount-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
class SetLibraryShowCountUseCase(
    private val repository: LibraryPrefsRepository,
) {
    suspend operator fun invoke(showCount: Boolean) {
        repository.setShowCount(showCount)
    }
}
