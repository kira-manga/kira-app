package me.manga.kira.presentation.library

import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.library.GridDensity
import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.presentation.mvi.MviIntent

/**
 * User actions submitted from the Library screen.
 *
 * Sealed so the ViewModel's `when` is exhaustive; adding a new action requires
 * adding a new subclass (OCP — compile-time enforcement that the reducer handles every case).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster28.staleKdocSweep.cascade,
 * Task #484, 2026-05-28): two stale citations into the §347-retired
 * legacy `:shared` Library surface appear below:
 *  - Line 81 inside [OnOpenRandom]'s KDoc ("Mirrors the legacy
 *    `LibraryScreen.kt:184-192`'s `dropdown_button_open_random_manga`
 *    semantics, which also silently no-ops when the visible item set
 *    is empty"). STALE-SYMBOL-REFERENCE — Phase 9.x.library.retire
 *    (§347, commit `2debbec`) DELETED the cited `:shared`
 *    `LibraryScreen.kt`. A recursive search of `:shared/.../
 *    presentation/features/library/` for a file named
 *    `LibraryScreen.kt` returns NO MATCHES.
 *  - Line 92 inside [OnSortChange]'s KDoc ("legacy parity with
 *    `LibraryViewModel.kt:296-311` `onSortChanged`"). STALE-SYMBOL-
 *    REFERENCE — §347 also deleted the cited `:shared`
 *    `LibraryViewModel.kt` (per the cluster28 postscript on the
 *    rework [me.manga.kira.presentation.library.LibraryViewModel]).
 *
 * The silent-no-op-on-empty-list semantics + the regenerate-random-
 * seed-on-RANDOM-sort behaviour stand on their own merits (both are
 * justified by the rework's MVI semantics regardless of legacy
 * lineage); the citations are historical record of where the design
 * lineage originated. Phase 9.x.library.swap (§346) re-pointed
 * `Screen.Library`'s rendering adapter to the rework [LibraryScreen]
 * backed by the rework [LibraryViewModel]. The [LibraryIntent] sealed
 * hierarchy remains LIVE as the canonical user-action surface for the
 * rework Library screen. Original prose preserved verbatim per the
 * audit-trail-preservation convention — the citations mark the design
 * lineage of the per-intent semantics, even though the cited files
 * have been retired.
 */
sealed interface LibraryIntent : MviIntent {

    /** Screen first becomes visible — subscribe to the library Flow. */
    data object OnEnter : LibraryIntent

    /**
     * Trigger a user-initiated library refresh (pull-to-refresh gesture, top-bar refresh
     * action, etc.). Calls [me.manga.kira.domain.usecase.library.RefreshLibraryUseCase]
     * which enqueues the legacy `LibraryRefresh` background job. The flow observer on
     * [me.manga.kira.domain.usecase.library.ObserveLibraryRefreshUseCase] projects
     * scheduler-state into `state.isRefreshing` for spinner visibility. See §148.
     */
    data object OnRefresh : LibraryIntent

    /** User tapped a row. View navigates to details via the emitted Effect. */
    data class OnItemClick(val manga: Manga) : LibraryIntent

    /** User long-pressed a row. Enters multi-select mode and selects this row. */
    data class OnItemLongClick(val key: MangaKey) : LibraryIntent

    /** User toggled selection state for one row while already in multi-select. */
    data class OnSelectionToggle(val key: MangaKey) : LibraryIntent

    /** User dismissed multi-select (back button / "X"). */
    data object OnSelectionClear : LibraryIntent

    /**
     * User tapped the "Delete" action in multi-select mode. Shows the confirmation
     * dialog — actual deletion is gated behind [OnDeleteSelectedConfirm].
     *
     * Mirrors the legacy `LibraryScreenRoute.kt:186-189` `onToggleDelete` callback's
     * staging posture (which also showed an `AlertDialog` before the deletion actually
     * ran). Renamed semantics from "delete now" to "request delete confirmation" —
     * the destructive action is now two-step.
     */
    data object OnDeleteSelected : LibraryIntent

    /**
     * User confirmed the multi-select deletion in the dialog. Performs the actual
     * `BulkRemoveFromLibraryUseCase` invocation, emits the success/error effect, and
     * exits selection mode.
     */
    data object OnDeleteSelectedConfirm : LibraryIntent

    /**
     * User dismissed the delete-confirmation dialog (cancel button / outside-tap /
     * system back). Closes the dialog without mutating the library; selection mode
     * stays active so the user can adjust the selection and try again.
     */
    data object OnDeleteSelectedDismiss : LibraryIntent

    /** User typed in the search box. Filtering happens locally over the observed list. */
    data class OnSearchQueryChange(val query: String) : LibraryIntent

    /**
     * User tapped "Random" in the top-bar actions. ViewModel picks a uniformly-random
     * entry from the currently visible (search-filtered) `state.items` snapshot and
     * emits [LibraryEffect.NavigateToDetails] for it.
     *
     * No-op on an empty list (e.g., library is empty, or active search yields zero
     * matches) — the action silently fizzles rather than emitting a navigation Effect
     * with no target. Mirrors the legacy `LibraryScreen.kt:184-192`'s
     * `dropdown_button_open_random_manga` semantics, which also silently no-ops when
     * the visible item set is empty.
     */
    data object OnOpenRandom : LibraryIntent

    /**
     * User picked a new ordering criterion from the sort dropdown. ViewModel updates
     * `state.sort`, re-applies the view pipeline against the current unfiltered snapshot,
     * and emits the resorted [LibraryState.items]. When [sort] is [LibrarySort.RANDOM]
     * the VM also regenerates `state.randomSeed` so the user sees a fresh shuffle (legacy
     * parity with `LibraryViewModel.kt:296-311` `onSortChanged`).
     */
    data class OnSortChange(val sort: LibrarySort) : LibraryIntent

    /**
     * User flipped the sort-direction toggle (typically a small ascending/descending
     * arrow next to the sort label). ViewModel toggles `state.sortDirection` and
     * re-applies the view pipeline. No-op semantically when `state.sort == RANDOM`
     * because the legacy reverse step is skipped for RANDOM
     * (`LibraryViewModel.kt:438`); the intent still updates the direction field so the
     * UI's arrow indicator stays in sync if the user later switches to a deterministic
     * mode.
     */
    data object OnSortDirectionToggle : LibraryIntent

    /**
     * User picked a new filter axis from the filter dropdown. ViewModel updates
     * [LibraryState.filter], re-applies the view pipeline (filter → sort → reverse) against the
     * current unfiltered snapshot, and emits the new [LibraryState.items]. Legacy parity with
     * `LibraryViewModel.kt:289-294` `onFilterChanged`. Persisted via the
     * `SetLibraryFilterUseCase` / `ObserveLibraryFilterUseCase` pair (the VM writes on the intent
     * and re-seeds the field from persistence in `init {}`).
     */
    data class OnFilterChange(val filter: LibraryFilter) : LibraryIntent

    /**
     * User picked a new category tab from the category-tab row. ViewModel updates
     * [LibraryState.category], re-applies the view pipeline (category → filter → sort → reverse)
     * against the current unfiltered snapshot, and emits the new [LibraryState.items]. Legacy
     * parity with `LibraryViewModel.kt:296-304` `onFilterTabChanged` (the legacy tab handler,
     * separate from the filter dropdown — see [me.manga.kira.domain.model.library.LibraryCategory]
     * KDoc for the per-manga-affinity vs per-chapter-status orthogonality narrative).
     *
     * Persisted via the `SetLibraryCategoryUseCase` / `ObserveLibraryCategoryUseCase` pair (the
     * VM writes on the intent and re-seeds the field from persistence in `init {}`).
     */
    data class OnCategoryChange(val category: LibraryCategory) : LibraryIntent

    /**
     * User picked a new grid density from the density dropdown. ViewModel updates
     * [LibraryState.gridDensity]; the `:ui` `LibraryGrid` consumes that field via the
     * `GridDensity.minSize()` extension to parameterize the adaptive grid's cell `minSize`.
     *
     * No re-application of `applyView` is needed: density only changes how the same `items`
     * list is laid out on screen, not which items appear or in what order. The grid recomposes
     * naturally on the state-flow emission.
     *
     * Persisted via the `SetLibraryGridDensityUseCase` / `ObserveLibraryGridDensityUseCase` pair
     * (the VM writes on the intent and re-seeds the field from persistence in `init {}`).
     */
    data class OnGridDensityChange(val density: GridDensity) : LibraryIntent

    /**
     * User dragged the items-per-row slider (Library parity fix, audit p1/library finding 2).
     * ViewModel updates [LibraryState.itemsPerRow]; the `:ui` `LibraryGrid` drives
     * `GridCells.Fixed(count)` for `count > 0` and the adaptive [GridDensity] cell for
     * `count == 0` (Auto).
     *
     * Mirrors native `LibraryViewModel.onItemsPerRowChange(count: Int)` verbatim — `count`
     * is the raw slider position in `0..8`, where `0 = Auto`. No re-application of `applyView`
     * is needed: items-per-row only changes how the same `items` list is laid out on screen,
     * not which items appear or in what order (same layout-only posture as
     * [OnGridDensityChange]).
     */
    data class OnItemsPerRowChange(val count: Int) : LibraryIntent

    /**
     * User flipped the "show source" display-toggle (the cardsource caption visibility switch in
     * the display-toggles sheet — see §162). ViewModel updates [LibraryState.display.showSource]
     * synchronously so the `:ui` cardsource caption hides/shows immediately, and launches the
     * `SetLibraryShowSourceUseCase` on `viewModelScope` to persist.
     *
     * No `applyView` re-run is needed — toggle flips only change which `:ui` surfaces are visible
     * (the layer gates the cardsource caption on `state.display.showSource`), not which items
     * appear or in what order. Same status-update-only posture as `OnGridDensityChange`.
     *
     * §150 ladder rung 16b (showSource end-to-end). The remaining four toggles
     * (showCount → showDetails → showButtons → showTabs) extend this same intent shape as
     * separate variants in rungs 16c-16f rather than a single parameterised `OnToggleDisplayFlag`
     * variant — per-flag intent boundaries keep the reducer narrow and match the established
     * §152 / §154 / §157 / §158 / §159 per-axis intent shape.
     */
    data class OnToggleShowSource(val value: Boolean) : LibraryIntent

    /**
     * User flipped the "show count" display-toggle (the count-section visibility switch in the
     * display-toggles sheet). ViewModel updates [LibraryState.display.showCount] synchronously
     * so the `:ui` gates the (rung-16c2) §163 carddownloaded "✓ N" badge AND §164 cardbookmarks
     * "🔖 N" caption update immediately, and launches the `SetLibraryShowCountUseCase` on
     * `viewModelScope` to persist.
     *
     * Why one flag gates two captions: the legacy `DisplayOptionsSection` bundles the "downloaded"
     * count badge and the "bookmarks" count caption under a single "Count" switch row. The rework
     * preserves that bundling at the flag level (one `showCount` flag, two `:ui` gates) rather
     * than splitting into separate `showDownloaded` / `showBookmarks` flags — same SwitchItem
     * shape, same persisted disk cell (`library_show_count`), same observable behaviour as the
     * legacy.
     *
     * No `applyView` re-run is needed — same status-update-only posture as `OnToggleShowSource`.
     *
     * §150 ladder rung 16c (showCount end-to-end). Mirrors the rung-16b `OnToggleShowSource`
     * shape exactly; the remaining three toggles (showDetails → showButtons → showTabs) land as
     * separate parametric variants in rungs 16d-16f rather than a single collapsed
     * `OnToggleDisplayFlag(field, value)` variant — per-flag intent boundaries keep the reducer
     * narrow and match the established §152 / §154 / §157 / §158 / §159 per-axis intent shape.
     */
    data class OnToggleShowCount(val value: Boolean) : LibraryIntent

    /**
     * User flipped the "show details" display-toggle (the per-card details visibility switch in
     * the display-toggles sheet). ViewModel updates [LibraryState.display.showDetails]
     * synchronously so the `:ui` gates the §165 cardlastread caption AND §166 cardprogress
     * caption update immediately, and launches the `SetLibraryShowDetailsUseCase` on
     * `viewModelScope` to persist.
     *
     * Why one flag gates two captions: the legacy `DisplayOptionsSection` bundles the
     * "last-read" timestamp caption and the "progress" (read/total) caption under a single
     * "Details" switch row. The rework preserves that bundling at the flag level (one
     * `showDetails` flag, two `:ui` gates) rather than splitting into separate
     * `showLastRead` / `showProgress` flags — same SwitchItem shape, same persisted disk cell
     * (`library_show_details`), same observable behaviour as the legacy. Same
     * "two-captions-per-flag" pattern established by rung 16c (`showCount` gates §163 + §164).
     *
     * No `applyView` re-run is needed — same status-update-only posture as `OnToggleShowSource`
     * / `OnToggleShowCount`.
     *
     * §150 ladder rung 16d (showDetails end-to-end). Mirrors the rung-16b/16c shape exactly;
     * the remaining two toggles (showButtons → showTabs) land as separate parametric variants
     * in rungs 16e-16f rather than a single collapsed `OnToggleDisplayFlag(field, value)`
     * variant — per-flag intent boundaries keep the reducer narrow and match the established
     * §152 / §154 / §157 / §158 / §159 per-axis intent shape.
     */
    data class OnToggleShowDetails(val value: Boolean) : LibraryIntent

    /**
     * User flipped the "show buttons" display-toggle (the per-card action-row visibility switch
     * in the display-toggles sheet). ViewModel updates [LibraryState.display.showButtons]
     * synchronously and launches the `SetLibraryShowButtonsUseCase` on `viewModelScope` to
     * persist.
     *
     * No `:ui` gate today (single sub-rung): the legacy "buttons" surface is the MangaCard
     * bottom action row (resume / mark-read / remove icons) which the rework has not yet
     * ported. There is no rework `:ui` consumer to gate on `state.display.showButtons` yet,
     * but the VM-side write path still needs to land so flips originating from the legacy
     * display sheet propagate through `LibraryPrefsRepositoryImpl`'s shared
     * `library_show_buttons` disk cell and the cross-route truth stays in sync. When a future
     * slice ports the bottom action row to the rework `LibraryCard`, gating it on
     * `display.showButtons` will be a 1-file `:ui` follow-on — but lifted *after* the
     * action-row port itself, not before (so the gate has a render block to wrap).
     *
     * No `applyView` re-run is needed — same status-update-only posture as `OnToggleShowSource`
     * / `OnToggleShowCount` / `OnToggleShowDetails`.
     *
     * §150 ladder rung 16e (showButtons VM-side wiring). Mirrors the rung-16b/16c/16d shape
     * exactly minus the `:ui` gate; the remaining toggle (showTabs) lands as a separate
     * parametric variant in rung 16f, per the per-flag intent boundary established by §168
     * / §170 / §172.
     */
    data class OnToggleShowButtons(val value: Boolean) : LibraryIntent

    /**
     * User flipped the "show tabs" display-toggle (the category-tabs visibility switch in
     * the display-toggles sheet). ViewModel updates [LibraryState.display.showTabs]
     * synchronously and launches the `SetLibraryShowTabsUseCase` on `viewModelScope` to
     * persist.
     *
     * `:ui` gate landed in the SAME slice as the VM wiring (single sub-rung — different
     * from 16b/16c/16d which each split VM and `:ui` across two sub-rungs). Rationale: the
     * gate is one `if (state.display.showTabs) { CategoryTabs(...) }` at the screen level
     * (not per-card), so the 1-line wrap fits inside the same ≤5-file commit cap as the
     * VM wiring. The §158 `CategoryTabs` row above the grid hides when this flag is `false`;
     * the rest of the screen (top bar, search field, last-updated row, grid) renders
     * unchanged.
     *
     * No `applyView` re-run is needed — same status-update-only posture as
     * `OnToggleShowSource` / `OnToggleShowCount` / `OnToggleShowDetails` /
     * `OnToggleShowButtons`. The `state.category` field is NOT cleared when tabs hide; the
     * user's last-selected category remains in effect (the grid keeps filtering by it),
     * the row just disappears. When the user toggles `showTabs` back on, the previously-
     * selected category resurfaces. Same posture as the legacy `library_show_tabs` flag.
     *
     * §150 ladder rung 16f (showTabs end-to-end — closes the 5/5 per-flag vertical ladder).
     * Mirrors the rung-16b/16c/16d shape exactly for VM wiring; adds a single 1-line `:ui`
     * gate around the `CategoryTabs(...)` call.
     */
    data class OnToggleShowTabs(val value: Boolean) : LibraryIntent

    /**
     * User tapped the heart icon on a library card's per-card action row (`showButtons`
     * surface). ViewModel calls [me.manga.kira.domain.usecase.library.ToggleMangaLikedUseCase],
     * which delegates to [me.manga.kira.domain.repository.LibraryRepository.toggleLiked].
     *
     * Flip-not-set semantics — there is no `value` parameter; the repository derives the new
     * boolean from the persisted row's current value. Mirrors the legacy MangaCard
     * `onToggleLike(manga)` callback shape verbatim (legacy
     * `composeApp/.../features/library/ui/screens/MangaCard.kt` action row).
     *
     * Membership-absent (manga not in library) silently succeeds — same posture as the
     * delete-from-library variants. The action-row only renders for in-library cards so the
     * absent-key case is defensive rather than expected. Error effects surface through
     * [LibraryEffect.ShowError] just like every other write path on this surface.
     *
     * §179 (Task #345 / rung 19). Pairs with [OnToggleWatchingNow] and the per-card delete
     * ([OnSingleDeleteRequest] → [OnSingleDeleteConfirm]) — the three together close the per-card
     * action row that the legacy MangaCard exposes.
     */
    data class OnToggleLike(val key: MangaKey) : LibraryIntent

    /**
     * User tapped the watch-later (clock) icon on a library card's per-card action row.
     * Same shape and semantics as [OnToggleLike] — see that variant's KDoc for the
     * flip-not-set / membership-absent / error-surfacing narrative.
     *
     * Mirrors the legacy MangaCard `onToggleWatchLater(manga)` callback shape verbatim.
     *
     * §179 (Task #345 / rung 19).
     */
    data class OnToggleWatchingNow(val key: MangaKey) : LibraryIntent

    /**
     * User tapped the delete (trash) icon on a library card's per-card action row, requesting a
     * confirmation step (GAP-LIB-15). ViewModel stages [LibraryState.pendingSingleDelete] = key so
     * the `:ui` per-card delete-confirmation dialog renders; the actual deletion is gated behind
     * [OnSingleDeleteConfirm].
     *
     * Restores native parity: the legacy per-card delete routed through a route-level
     * delete-confirmation `AlertDialog` (`LibraryRoute.kt:94-154`) BEFORE calling `removeManga`.
     * The card dispatches THIS intent; the actual `BulkRemoveFromLibraryUseCase` call (with a
     * single-element list — same write path as the multi-select bulk-delete) runs only after the
     * user confirms, in [OnSingleDeleteConfirm].
     */
    data class OnSingleDeleteRequest(val key: MangaKey) : LibraryIntent

    /**
     * User confirmed the per-card single-delete in the dialog (GAP-LIB-15). ViewModel clears
     * [LibraryState.pendingSingleDelete] and performs the actual `BulkRemoveFromLibraryUseCase`
     * call with the staged single key (same write path as the bulk delete).
     * Emits [LibraryEffect.ShowBulkRemoveSuccess] (count = 1) on success / [LibraryEffect.ShowError]
     * on failure — the same effect surface the `:ui` snackbar already consumes.
     */
    data object OnSingleDeleteConfirm : LibraryIntent

    /**
     * User dismissed the per-card single-delete confirmation dialog (cancel / outside-tap /
     * system back) (GAP-LIB-15). ViewModel clears [LibraryState.pendingSingleDelete] without
     * mutating the library.
     */
    data object OnSingleDeleteDismiss : LibraryIntent
}
