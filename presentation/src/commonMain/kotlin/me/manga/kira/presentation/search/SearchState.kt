package me.manga.kira.presentation.search

import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.domain.model.home.HomeFeedItem
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
 * Immutable Search screen state (Epic H3b; filters generalized by the config-driven filters
 * campaign, 2026-07).
 *
 * UiState modelling:
 *  - [single] uses the [UiState] envelope (Loading / Success / Error) because the single-source
 *    results are one async value that the screen renders with a `when`.
 *  - [multi] is a `Map<sourceApi, UiState<...>>` — each repo carries its OWN [UiState] so one
 *    repo's failure shows only that tab's error while the others still render. The envelope is the
 *    only shape that fits a per-repo collection (the split-flag shape `DetailsState` uses cannot be
 *    stored as a map value).
 *
 * Filters (config-driven, one model for generic AND legacy sources):
 *  - [filters] holds the active source's ORDERED filter descriptors — from the validated config
 *    stanza for a config-backed source, from the legacy sortTypes/allGenres adapter otherwise. The
 *    sheet renders them in list order; empty = plain search only.
 *  - [selections] is the user's current values keyed by filter id (backend option values /
 *    `"true"`/`"false"` for toggles / free text). Reconciled on every filter load: unknown ids and
 *    values from a previous source are dropped, defaults are seeded for untouched filters — so
 *    switching sources can never leak incompatible selections.
 *  - [hasSearched] tracks whether a real search has run since the overlay opened (a filter browse
 *    runs with a deliberately blank [query], so the idle/empty-state heuristic can't be derived
 *    from `query.isBlank()`).
 */
data class SearchState(
    val query: String = "",
    val mode: SearchModeTab = SearchModeTab.SINGLE,
    val single: UiState<List<HomeFeedItem>> = UiState.Success(emptyList()),
    val multi: Map<String, UiState<List<HomeFeedItem>>> = emptyMap(),
    val filters: List<SourceFilter> = emptyList(),
    val selections: Map<String, List<String>> = emptyMap(),
    val hasSearched: Boolean = false,
) : MviState
