package me.manga.kira.presentation.library

import kotlin.time.Instant
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.library.GridDensity
import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.domain.model.library.LibraryDisplay
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.model.library.SortDirection
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.presentation.mvi.MviState

/**
 * Immutable Library screen state.
 *
 * Strict MVI: every property is `val`, every collection is `List` / `Set` (read-only public
 * interface). Mutation happens only via `MviViewModel.updateState { it.copy(...) }`.
 *
 * [items] is the source-of-truth library snapshot (post-search-filter, post-sort). The
 * unfiltered snapshot is held internally by the ViewModel and never leaked to the view —
 * the view only ever renders this list.
 *
 * [sort] / [sortDirection] / [randomSeed] together model the user's chosen ordering for the
 * grid. The ViewModel re-applies the pipeline (filter → sort → reverse) on every flow emission
 * AND on every sort-change intent. The `randomSeed` is only consulted when `sort == RANDOM`;
 * it is seeded from persistence at startup (native KEY_SEED parity) and regenerated when the
 * user picks RANDOM. All three are persisted via the `SetLibrarySort*` / `SetLibraryRandomSeed`
 * setters and re-seeded from the matching `ObserveLibrary*` collectors in the ViewModel's
 * `init {}`, so the chosen ordering survives re-emissions and restarts.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster31.staleKdocSweep.cascade,
 * Task #487, 2026-05-28): one fulfilled-forecast / stale citation
 * appears in a member KDoc below:
 *  - Line 120 ([activeDownloadCount] KDoc, "mirroring the legacy
 *    `LibraryScreen`'s `isDownloading` AnimatedPreloader icon").
 *    STALE-SYMBOL-REFERENCE — Phase 9.x.library.retire (§347)
 *    DELETED the legacy `LibraryScreen` (and its hosting route
 *    adapter); Phase 9.x.library.deadcomposable.retire (§348)
 *    DELETED the cited `AnimatedPreloader` composable that gated
 *    the legacy `isDownloading` icon. A recursive search of the
 *    legacy library folder for a `LibraryScreen.kt` with an
 *    `isDownloading` AnimatedPreloader icon returns NO MATCHES.
 *    HOWEVER — the rework `:ui` `LibraryScreen` (same filename,
 *    different package: `me.manga.kira.ui.library.LibraryScreen`)
 *    is LIVE as the canonical Library surface backed by [LibraryState]
 *    + [LibraryViewModel]; the rework's count-based "↓ $count"
 *    badge is the LIVE realization of the documented enrichment-
 *    over-binary-icon design (the rationale that "the count is
 *    free given the derivation" STANDS on its own merits — the
 *    legacy's binary-icon constraint was a legacy-implementation
 *    quirk, not a load-bearing product requirement). The
 *    [LibraryState] data class remains LIVE as the canonical
 *    Library-screen state ADT consumed by [LibraryViewModel] +
 *    the rework `:ui` `LibraryScreen`. Original §253-era prose
 *    preserved verbatim per the audit-trail-preservation
 *    convention — the citation is historical record of the
 *    design lineage including the binary-icon-vs-count-badge
 *    rationale that was subsequently fulfilled (legacy
 *    LibraryScreen + AnimatedPreloader retired) across §§347 +
 *    348.
 */
data class LibraryState(
    val isLoading: Boolean = true,
    val items: List<LibraryManga> = emptyList(),
    val searchQuery: String = "",
    val selection: Set<MangaKey> = emptySet(),
    val isInSelectionMode: Boolean = false,
    val isDeleteDialogVisible: Boolean = false,
    /**
     * Per-card single-delete confirmation target (GAP-LIB-15). Non-null while the per-card
     * delete-confirmation dialog is shown for that one manga; `null` when no per-card delete is
     * pending. Distinct from [isDeleteDialogVisible] (the multi-select bulk-delete dialog) because
     * the two flows confirm different scopes — the bulk dialog confirms `selection`, this confirms
     * one card outside selection mode.
     *
     * Restores native parity: the legacy per-card delete routed through a route-level
     * delete-confirmation `AlertDialog` (`LibraryRoute.kt:94-154`) before `removeManga`. The
     * rework's pre-GAP-LIB-15 per-card delete fired `bulkRemoveFromLibrary` directly with no
     * confirmation — a destructive data-loss path. This field gates that delete behind a confirm.
     */
    val pendingSingleDelete: MangaKey? = null,
    val isRefreshing: Boolean = false,
    val sort: LibrarySort = LibrarySort.ALPHABETIC,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val randomSeed: Long? = null,
    /**
     * Active filter axis. Defaults to [LibraryFilter.ALL] (identity — passes every row). The VM
     * applies this predicate in `applyView` BEFORE the sort step, so the same comparator runs
     * against the smaller filtered working set. See §153 for the per-value semantic mapping and
     * the deferred `BOOKMARKED` axis.
     */
    val filter: LibraryFilter = LibraryFilter.ALL,
    /**
     * Active category-tab axis (per-manga affinity narrowing — heart / "watching now"). Defaults
     * to [LibraryCategory.NAN] (identity — passes every row). The VM applies this predicate in
     * `applyView` BEFORE [filter] so each subsequent step (filter, sort) operates on a smaller
     * working set. Orthogonal to [filter]: category narrows by per-manga affinity flag, filter
     * narrows by per-chapter status. See §158 for the per-value semantic mapping.
     */
    val category: LibraryCategory = LibraryCategory.NAN,
    /**
     * Global Settings "Downloaded only" toggle, projected from the reactive SettingsSnapshot. When
     * `true` it OVERRIDES [filter] in `applyView`, constraining the grid to manga with ≥1 downloaded
     * chapter (native: the Settings toggle "Filters all entries in your library"). Defaults `false`.
     */
    val downloadedOnly: Boolean = false,
    /**
     * User-chosen grid density. Drives the adaptive grid's `minSize` parameter in `:ui`
     * (see `GridDensity.minSize()` extension in `LibraryScreen.kt`). Defaults to
     * [GridDensity.COMFORTABLE] which preserves the pre-§156 120.dp cell size byte-for-byte.
     *
     * Persisted to multiplatform-settings via the §157 `library_grid_density` disk key — the VM's
     * `init {}` collector subscribes to `ObserveLibraryGridDensityUseCase` and updates this field
     * on every emission; the `OnGridDensityChange` reducer writes through synchronously.
     */
    val gridDensity: GridDensity = GridDensity.COMFORTABLE,
    /**
     * User's chosen number of grid columns (Library parity fix, audit p1/library finding 2).
     *
     * Mirrors native `LibraryViewModel.UiState.itemsPerRow` verbatim: `0` means "Auto"
     * (the `:ui` grid falls back to an adaptive cell `minSize`), and `1..8` pin the grid to
     * exactly that many fixed columns via `GridCells.Fixed(n)`. The native default is `0`
     * (Auto) — `prefs.getInt("library_items_per_row", 0)` in the native VM `init {}`.
     *
     * Native exposes this as a continuous `Slider(valueRange = 0f..8f, steps = 7)` in its
     * `DisplayOptionsSection`; the rework had replaced that control with a coarse 3-value
     * [gridDensity] enum, dropping both the explicit column count and the Auto option. This
     * field restores the native control surface; the `:ui` grid drives
     * `GridCells.Fixed(itemsPerRow)` for `itemsPerRow > 0` and the adaptive [gridDensity]
     * cell for `itemsPerRow == 0`.
     *
     * Layout-only axis — no `applyView` re-run on change (same posture as [gridDensity]); the
     * grid recomposes naturally on the state-flow emission. Persisted to multiplatform-settings
     * via the native `library_items_per_row` Int disk key (byte-for-byte native parity): the VM's
     * `init {}` collector subscribes to
     * [me.manga.kira.domain.usecase.library.ObserveLibraryItemsPerRowUseCase] and updates this
     * field on every emission; the `OnItemsPerRowChange` reducer writes through synchronously via
     * [me.manga.kira.domain.usecase.library.SetLibraryItemsPerRowUseCase]. Same observer-
     * projection persistence posture as [gridDensity].
     */
    val itemsPerRow: Int = 0,
    /**
     * Wall-clock instant at which the library was last refreshed end-to-end. `null` when no
     * refresh has ever completed (first-run user, or iOS/Desktop where the Android-only legacy
     * `LibraryRefreshWorker` cannot write the underlying SharedPrefs cell). The VM observes
     * `ObserveLibraryLastUpdatedUseCase` and projects emissions here; the `:ui` layer renders a
     * "Last updated: <relative time>" label below the filter/tab row, or "Never updated" when
     * `null`.
     *
     * Read-only — there is no `LibraryIntent` to mutate this field. The cell-of-truth writer is
     * the external legacy refresh worker (matching tag `LibraryRefresh`), not the VM. See
     * `LibraryPrefsRepository.observeLastUpdated` for the full read-only rationale.
     */
    val lastUpdated: Instant? = null,
    /**
     * Count of chapter downloads currently in the "active" bucket (RUNNING ∪ QUEUED ∪
     * COMPRESSING — see `DownloadState` KDoc for the bucket definition). Zero when no
     * downloads are in flight; positive when one or more chapters are downloading or queued.
     *
     * The VM derives this by collecting `ObserveDownloadsUseCase` and counting rows whose
     * `state` is in the active bucket. The `:ui` layer renders a "↓ $count" badge in the
     * top-bar action row when this is positive, mirroring the legacy `LibraryScreen`'s
     * `isDownloading` AnimatedPreloader icon (which is binary; the rework surfaces the count
     * for richer feedback since the derivation is free).
     *
     * Read-only — there is no `LibraryIntent` to mutate this field. The cell-of-truth writer
     * is the Downloads pipeline (legacy `:shared` `DownloadRepository`), not the VM. Same
     * status-indicator posture as [lastUpdated]: VM observes and projects; UI displays only.
     */
    val activeDownloadCount: Int = 0,
    /**
     * User's persisted Library display-toggle bundle (the five `show*` boolean flags that gate
     * optional surfaces on the Library screen — see [LibraryDisplay] KDoc for the per-flag
     * semantics). Defaults to all-`true` (the canonical default — preserves the pre-rung-16
     * "everything visible" posture byte-for-byte).
     *
     * The VM observes [me.manga.kira.domain.usecase.library.ObserveLibraryDisplayUseCase] and
     * projects each emission here; per-toggle intents (e.g. `OnToggleShowSource`) write the new
     * value through the matching setter use case (e.g. `SetLibraryShowSourceUseCase`) and update
     * this field synchronously so the UI recomposes immediately.
     *
     * The `:ui` layer gates optional surfaces on the individual flags (e.g. cardsource caption
     * on `state.display.showSource` per §162; cardcount caption on `state.display.showCount`
     * per §163; category tab row on `state.display.showTabs` per §158); flipping a flag hides
     * or shows that surface without re-running `applyView` (toggle flips don't narrow the
     * visible item set or change ordering).
     *
     * §150 ladder rung 16 (display-toggle persistence). Same observer-projection posture as the
     * sort/direction/filter/density/category persistence wirings — VM observes the use case,
     * projects to state, lets `StateFlow` distinct-emission guard collapse the echo from the
     * setter's write-through.
     */
    val display: LibraryDisplay = LibraryDisplay(),
) : MviState {

    /** Convenience: true when the user has typed a non-blank search term. */
    val isSearching: Boolean get() = searchQuery.isNotBlank()

    /** Convenience: true when the library snapshot is empty and we're not still loading. */
    val isEmpty: Boolean get() = !isLoading && items.isEmpty()
}
