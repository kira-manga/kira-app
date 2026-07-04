package me.manga.kira.presentation.search

import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.presentation.mvi.MviIntent

/**
 * User actions submitted from the Search overlay.
 *
 * Sealed so the reducer's `when` is exhaustive (OCP). Mirrors the legacy `MangaViewModel.startSearch`
 * (single source) + `HomeViewModel` multi-repo fan-out action surface.
 */
sealed interface SearchIntent : MviIntent {

    /** Overlay opened: load the active source's sort types + genres for the filter sheet. */
    data object OnLoadFilters : SearchIntent

    /**
     * Search text changed (#16: VIEW-ONLY). Updates the query text but does NOT fire a search —
     * submit-driven (native): the search runs on [OnSubmit] (IME search action), not per keystroke.
     */
    data class OnQueryChange(val query: String) : SearchIntent

    /**
     * IME search action / explicit submit (#16). Runs the mode-aware search for the current query.
     */
    data object OnSubmit : SearchIntent

    /**
     * Retry the single-source search that just failed (the error pane's Retry button).
     *
     * Re-runs the LAST-executed single search with its exact parameters (query + effective
     * [me.manga.kira.domain.model.home.SearchMode] + sort + genres). A genre/sort browse runs with
     * a deliberately blank query, so routing Retry through [OnSubmit] would hit the blank-query guard
     * and silently clear the error to a pristine idle screen instead of re-running the failed browse.
     */
    data object OnRetrySingle : SearchIntent

    /** Single ↔ multi results tab changed (#16: VIEW-ONLY — does not fire a search). */
    data class OnModeTabChange(val mode: SearchModeTab) : SearchIntent

    /** Filter sheet applied: new sort + genre selection. Re-runs the single-source search. */
    data class OnApplyFilters(val sort: String?, val genres: List<String>) : SearchIntent

    /**
     * A genre chip was tapped in the filter sheet (legacy `MangaViewModel.onGenreClicked`).
     *
     * Native parity: a genre tap fires an IMMEDIATE genre-browse search (the sheet stays open and
     * results update live behind it) and the text query is intentionally blanked — the source's
     * genre-listing endpoint is hit with no text term. Passing `null`/empty here clears the genre
     * selection (re-tap of the selected chip).
     */
    data class OnGenreClick(val genre: String?) : SearchIntent

    /**
     * A sort option was chosen from the filter sheet dropdown (legacy `MangaViewModel.onSortClick`).
     *
     * Native parity: selecting a sort fires an IMMEDIATE sorted search right away (the sheet stays
     * open); only the dropdown closes.
     */
    data class OnSortSelect(val sort: String) : SearchIntent

    /** User tapped a result → navigates to Details. */
    data class OnMangaClick(val item: HomeFeedItem) : SearchIntent

    /** User dismissed the search overlay. */
    data object OnClose : SearchIntent
}
