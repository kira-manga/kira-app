package me.manga.kira.presentation.search

import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.presentation.mvi.MviIntent

/**
 * User actions submitted from the Search overlay.
 *
 * Sealed so the reducer's `when` is exhaustive (OCP). Mirrors the legacy `MangaViewModel.startSearch`
 * (single source) + `HomeViewModel` multi-repo fan-out action surface; the per-axis sort/genre
 * intents were generalized to [OnFilterChange]/[OnResetFilters] by the config-driven filters
 * campaign (2026-07) — one intent surface for standard AND custom filters.
 */
sealed interface SearchIntent : MviIntent {
    /** Overlay opened: load the active source's ordered filter descriptors for the filter sheet. */
    data object OnLoadFilters : SearchIntent

    /**
     * Search text changed (#16: VIEW-ONLY). Updates the query text but does NOT fire a search —
     * submit-driven (native): the search runs on [OnSubmit] (IME search action), not per keystroke.
     */
    data class OnQueryChange(
        val query: String,
    ) : SearchIntent

    /**
     * IME search action / explicit submit (#16). Runs the mode-aware search for the current query.
     * F2 regression guard (native parity): a typed submit is a PLAIN search — the sheet's filter
     * selections stay display-only on submit; they apply through [OnFilterChange]'s immediate-apply.
     */
    data object OnSubmit : SearchIntent

    /**
     * Retry the single-source search that just failed (the error pane's Retry button).
     *
     * Re-runs the LAST-executed single search with its exact parameters (query + selections). A
     * filter browse runs with a deliberately blank query, so routing Retry through [OnSubmit] would
     * hit the blank-query guard and silently clear the error to a pristine idle screen instead of
     * re-running the failed browse.
     */
    data object OnRetrySingle : SearchIntent

    /** Single ↔ multi results tab changed (#16: VIEW-ONLY — does not fire a search). */
    data class OnModeTabChange(
        val mode: SearchModeTab,
    ) : SearchIntent

    /**
     * A filter's value changed in the sheet (config-driven filters, 2026-07). IMMEDIATE-APPLY
     * (native parity with the legacy genre/sort taps): the search re-runs the instant the value
     * changes, with the sheet staying open. [values] is the complete new selection for [filterId]
     * (empty = cleared). Changes to unknown filter ids are ignored defensively.
     *
     * Legacy-parity quirk preserved: a non-empty change to the standard `genres` filter runs a
     * genre BROWSE with a blank text query (native `startSearch(GENRES(genres, query = ""))`);
     * every other filter keeps the live query.
     */
    data class OnFilterChange(
        val filterId: String,
        val values: List<String>,
    ) : SearchIntent

    /** Reset every filter to its declared defaults (deterministic) and re-run the search. */
    data object OnResetFilters : SearchIntent

    /** User tapped a result → navigates to Details. */
    data class OnMangaClick(
        val item: HomeFeedItem,
    ) : SearchIntent

    /** User dismissed the search overlay. */
    data object OnClose : SearchIntent
}
