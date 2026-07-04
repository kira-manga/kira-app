package me.manga.kira.presentation.search

import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SearchFilters
import me.manga.kira.presentation.mvi.MviState
import me.manga.kira.presentation.mvi.UiState

/** Which results surface the Search overlay shows. Legacy: single-source vs multi-repo tabbed pager. */
enum class SearchModeTab {
    /** Search only the active source. */
    SINGLE,

    /** Fan the query out across all enabled repos (tabbed/aggregated results). */
    MULTI,
}

/**
 * Immutable Search screen state (Epic H3b).
 *
 * UiState modelling:
 *  - [single] uses the [UiState] envelope (Loading / Success / Error) because the single-source
 *    results are one async value that the screen renders with a `when`.
 *  - [multi] is a `Map<sourceApi, UiState<...>>` — each repo carries its OWN [UiState] so one
 *    repo's failure shows only that tab's error while the others still render. The envelope is the
 *    only shape that fits a per-repo collection (the split-flag shape `DetailsState` uses cannot be
 *    stored as a map value).
 *
 * Legacy parity (`MangaViewModel` single-source search + `HomeViewModel` multi-repo fan-out):
 *  - [query] is the live search text; [mode] is the single/multi tab.
 *  - [filters] holds the source's available sort types + genres for the filter sheet; [selectedSort]
 *    and [selectedGenres] are the active selections applied to a single-source search.
 *  - [hasSearched] tracks whether a real search has run since the overlay opened (a genre/sort
 *    browse runs with a deliberately blank [query], so the idle/empty-state heuristic can't be
 *    derived from `query.isBlank()`).
 */
data class SearchState(
    val query: String = "",
    val mode: SearchModeTab = SearchModeTab.SINGLE,
    val single: UiState<List<HomeFeedItem>> = UiState.Success(emptyList()),
    val multi: Map<String, UiState<List<HomeFeedItem>>> = emptyMap(),
    val filters: SearchFilters = SearchFilters(sortTypes = emptyList(), genres = emptyList()),
    val selectedSort: String? = null,
    val selectedGenres: List<String> = emptyList(),
    val hasSearched: Boolean = false,
) : MviState
