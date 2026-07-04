package me.manga.kira.domain.model.library

/**
 * Bundle of five independent user-toggleable display flags that gate optional surfaces on the
 * Library screen.
 *
 * Each flag corresponds 1:1 to a legacy `LibraryViewModel.UiState` field — the names are
 * preserved byte-for-byte so the persisted disk keys (declared in [LibraryPrefsRepository]'s
 * `:data` impl) can reuse the legacy `library_show_*` constants and survive the strangler-fig
 * transition without resetting user preferences. Same shared-cell posture as the
 * sort / direction / filter / density / category axes — see `LibraryPrefsRepositoryImpl`'s
 * "wire-format compatibility" KDoc for the full rationale.
 *
 * Semantic mapping:
 *  - [showSource]  → source-api caption (one-line text beneath the title; rework §162) is
 *                    visible on each LibraryCard.
 *  - [showCount]   → item-count label ("N items") under the category tab row (rework §158).
 *  - [showDetails] → per-card stat captions (§163 ✓ glyph, §164 🔖 N, §166 N/M chapters,
 *                    §165 last-read caption) are visible on each LibraryCard.
 *  - [showButtons] → action button column on each LibraryCard (Watch Later / Like / Delete on
 *                    the legacy card). The rework Library now gates a per-card action row on this
 *                    flag (`LibraryScreen.kt`: `display.showButtons && !isInSelectionMode`), so the
 *                    persistence cell both tracks the user's choice and drives the rendered surface.
 *  - [showTabs]    → category tab row (rework §158 NAN / LIKED / WATCHING_NOW tabs) is visible
 *                    above the grid.
 *
 * Why a bundle ADT rather than five top-level [LibraryState] booleans:
 *  - Five booleans on `LibraryState` would force every reducer touching one toggle to spell
 *    out four unchanged `copy()` args. A nested [LibraryDisplay] field collapses each toggle
 *    flip into a single `state.copy(display = state.display.copy(showSource = it))`.
 *  - The `:data` impl can read all five via `kotlinx.coroutines.flow.combine` over the five
 *    backing `getBooleanFlow` streams and emit a single coalesced [LibraryDisplay] snapshot
 *    per actual change — saving the VM five separate `init {}` collectors. Same posture as
 *    the §232 `ReadingStatistics` bundle vs. eight independent [Flow]s.
 *  - Future display preferences (e.g. cover aspect, label position) extend this ADT with a
 *    new field rather than introducing a sixth top-level `LibraryState` boolean.
 *
 * Why all defaults are `true`:
 *  - Mirrors the legacy `LibraryViewModel.UiState`'s default-`true` posture for all five
 *    fields (see `shared/.../library/ui/viewmodel/LibraryViewModel.kt:82-86`). A first-run user
 *    on the rework should see exactly what they'd see on legacy — no surprise blank cards or
 *    hidden tabs because the persistence cell hasn't been written yet.
 *
 * SRP (contract §6): this ADT names the FIVE-AXIS DISPLAY BUNDLE, nothing else. The disk-key
 * mapping, the toggle UI surface, and the per-flag-gating compose branches are all separate
 * concerns owned by `:data` / `:ui`.
 *
 * OCP: adding a sixth toggle is a field append + a sixth observe/set pair on
 * [LibraryPrefsRepository] + a sixth Koin factory + a sixth gated UI branch. No call-site
 * changes elsewhere — existing callers using `LibraryDisplay()` keep the new field at its
 * default.
 *
 * §150 ladder rung 16 (display-toggle persistence foundation). End-to-end wiring of each
 * individual toggle (intent → state → UI gate) lands in the per-toggle sub-rungs 16b-16f.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster133.staleKdocSweep.cascade,
 * Task #590, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-fourteenth sibling of the cluster57-132
 * sweep — fourth and closing file of the wave-24 opener `:domain/model/
 * library/` 4-leaf-model batch alongside SortDirection plus GridDensity
 * plus LibraryCategory; closes cluster133):
 *  (a) "Bundle-of-five-independent-user-toggleable-display-flags-that-
 *  gate-optional-surfaces-on-the-Library-screen + each-flag-corresponds-
 *  1-1-to-legacy-LibraryViewModel.UiState-field + names-preserved-byte-
 *  for-byte-so-persisted-disk-keys-declared-in-LibraryPrefsRepository-
 *  :data-impl-can-reuse-legacy-library_show_-constants-and-survive-
 *  strangler-fig-transition-without-resetting-user-preferences" — LIVE-
 *  NOT-STALE + FULFILLED-PREDICTION. Verified via grep: LibraryPrefs-
 *  RepositoryImpl carries the five legacy disk keys (library_show_source,
 *  library_show_count, library_show_details, library_show_buttons,
 *  library_show_tabs); ObserveLibraryDisplayUseCase plus five
 *  SetLibraryShow*UseCase siblings landed (cluster130 + cluster131
 *  sweep siblings). The strangler-fig wire-format compatibility holds
 *  byte-for-byte.
 *  (b) "Why-a-bundle-ADT-rather-than-five-top-level-LibraryState-
 *  booleans + reducer-touching-one-toggle-spelling-out-four-unchanged-
 *  copy-args + :data-impl-can-read-all-five-via-kotlinx.coroutines.flow.
 *  combine + saving-VM-five-separate-init-collectors + same-posture-as-
 *  §232-ReadingStatistics-bundle-vs-eight-independent-Flows + future-
 *  display-preferences-extend-this-ADT-with-new-field-rather-than-
 *  introducing-sixth-top-level-LibraryState-boolean + all-defaults-true-
 *  mirrors-legacy-LibraryViewModel.UiState-default-true-posture" — LIVE-
 *  NOT-STALE + FULFILLED-PREDICTION. LibraryState.display: LibraryDisplay
 *  = LibraryDisplay() default. LibraryPrefsRepositoryImpl.observeDisplay
 *  uses flow.combine across the five getBooleanFlow streams. Default-true
 *  posture preserved verbatim — a first-run user sees every Library
 *  surface visible matching legacy.
 *  (c) "§150 ladder rung 16 (display-toggle persistence foundation) +
 *  end-to-end-wiring-of-each-individual-toggle-intent-state-UI-gate-
 *  lands-in-per-toggle-sub-rungs-16b-16f + OCP-adding-sixth-toggle-is-
 *  field-append-plus-sixth-observe-set-pair-on-LibraryPrefsRepository-
 *  plus-sixth-Koin-factory-plus-sixth-gated-UI-branch + showButtons-flag-
 *  lifted-anyway-so-persistence-cell-tracks-user-choice-without-round-
 *  tripping-it-through-legacy-plus-rework-independently + future-rework-
 *  slice-that-adds-card-actions-will-gate-them-on-this-flag-without-
 *  fresh-persistence-lift" — LIVE-NOT-STALE + FULFILLED-PREDICTION-
 *  (rungs-16a-through-16f) + FORECAST-NOT-YET-FULFILLED-(card-actions-
 *  slice). Verified via grep: Tasks #333-341 landed rungs 16a-16f end-
 *  to-end (foundation2 + showSource + showCount + showDetails +
 *  showButtons + showTabs all swept across cluster131 + ObserveLibrary-
 *  DisplayUseCase in cluster130). DisplayOptionsDialog.kt L123-164
 *  consumes the display: LibraryDisplay ADT and binds checked = display.
 *  showDetails / showSource / showCount / showButtons / showTabs to
 *  five toggle rows. The showButtons-flag-lifted-anyway forecast that
 *  "future rework slice that adds card actions will gate them on this
 *  flag without a fresh persistence lift" remains FORECAST-NOT-YET-
 *  FULFILLED — the rework MangaCard bottom action row has not yet
 *  landed as of 2026-05-28; no rework :ui consumer to gate today on
 *  display.showButtons (mirrors the cluster131 SetLibraryShowButtonsUseCase
 *  postscript's same-forecast-stands annotation).
 *  Three classifications STAND on their own merits. Closes cluster133.
 *  Opens :domain/model/ tier wave-24 cycle with all 4 library/ leaf
 *  models (SortDirection + GridDensity + LibraryCategory + LibraryDisplay)
 *  swept. Original Phase 7.x.library.display.foundation2-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
data class LibraryDisplay(
    val showSource: Boolean = true,
    val showCount: Boolean = true,
    val showDetails: Boolean = true,
    val showButtons: Boolean = true,
    val showTabs: Boolean = true,
)
