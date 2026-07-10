package me.manga.kira.presentation.search

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.onFailure
import me.manga.kira.core.result.onSuccess
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.feedKey
import me.manga.kira.domain.usecase.home.LoadSearchFiltersUseCase
import me.manga.kira.domain.usecase.home.SearchAllReposUseCase
import me.manga.kira.domain.usecase.home.SearchSourceUseCase
import me.manga.kira.presentation.mvi.MviViewModel
import me.manga.kira.presentation.mvi.UiState

/**
 * Search overlay ViewModel (Epic H3b; filters generalized by the config-driven filters campaign,
 * 2026-07 — one ordered [SourceFilter] pipeline for standard and custom filters, generic and
 * legacy sources alike).
 *
 * Strict MVI; depends only on H1 `:domain` search use cases (DIP); Compose-free.
 *
 * Concurrency posture:
 *  - [singleSearchJob] is the single-flight single-source search (cancel-and-replace).
 *  - [multiSearchJob] is the multi-repo fan-out *collection* job. A new query (or a mode switch
 *    onto MULTI) cancels the prior collection so a stale fan-out can't keep pushing results for an
 *    abandoned query — the locked decision (4) "multi-repo fan-out cancels previous query".
 *
 * Filter-state invariants (pinned by SearchViewModelFilterTest):
 *  - selections are reconciled against every freshly loaded filter list — unknown ids and unknown
 *    option values are dropped, defaults seed untouched filters — so a source switch (close →
 *    reopen on another source) can never leak incompatible values;
 *  - [SearchIntent.OnResetFilters] deterministically restores the declared defaults;
 *  - selections live in MVI state, so they survive recomposition by construction;
 *  - a source with no filters keeps plain search untouched.
 */
class SearchViewModel(
    private val searchSource: SearchSourceUseCase,
    private val searchAllRepos: SearchAllReposUseCase,
    private val loadFilters: LoadSearchFiltersUseCase,
) : MviViewModel<SearchState, SearchIntent, SearchEffect>(
        initialState = SearchState(),
    ) {
    private var singleSearchJob: Job? = null
    private var multiSearchJob: Job? = null

    /**
     * Snapshot of the last-executed single-source search parameters, so the error pane's Retry can
     * re-run the EXACT failed search (a filter browse runs with a blank query, which the
     * blank-query guard in [runSearch] would otherwise treat as "clear to idle").
     */
    private data class SingleSearchParams(
        val query: String,
        val selections: FilterSelections,
    )

    private var lastSingleSearch: SingleSearchParams? = null

    override suspend fun handle(intent: SearchIntent) {
        when (intent) {
            SearchIntent.OnLoadFilters -> onLoadFilters()
            is SearchIntent.OnQueryChange -> onQueryChange(intent.query)
            SearchIntent.OnSubmit -> onSubmit()
            SearchIntent.OnRetrySingle -> onRetrySingle()
            is SearchIntent.OnModeTabChange -> onModeTabChange(intent.mode)
            is SearchIntent.OnFilterChange -> onFilterChange(intent.filterId, intent.values)
            SearchIntent.OnResetFilters -> onResetFilters()
            is SearchIntent.OnMangaClick ->
                emit(
                    SearchEffect.NavigateToDetails(
                        api = intent.item.api,
                        language = intent.item.language,
                        title = intent.item.title,
                        mangaUrl = intent.item.url,
                        coverUrl = intent.item.coverUrl,
                        rating = intent.item.rating,
                        genres = intent.item.genres,
                    ),
                )
            SearchIntent.OnClose -> onClose()
        }
    }

    private suspend fun onLoadFilters() {
        loadFilters()
            .onSuccess { filters ->
                updateState {
                    it.copy(
                        filters = filters,
                        selections = reconcileSelections(filters, it.selections),
                    )
                }
            }
            // Keep the prior filters (the sheet shows whatever was last loaded) and surface the
            // failure instead of letting the throw die in the generic onUnhandledError log.
            .onFailure { error -> emit(SearchEffect.ShowError(error)) }
    }

    /**
     * Reconcile held selections against a freshly loaded filter list (the source may have changed
     * or its filters may have been re-authored):
     *  - selections for ids the new list doesn't declare are DROPPED (safe removal of stale state);
     *  - select/multiselect values not among the filter's declared option values are dropped, and a
     *    toggle value outside `true`/`false` is dropped (a stale value can never leak);
     *  - a filter the user never touched (no held entry) is seeded with its declared defaults.
     * An explicitly cleared selection (held empty list) stays cleared — defaults only seed
     * untouched filters.
     */
    private fun reconcileSelections(
        filters: List<SourceFilter>,
        held: Map<String, List<String>>,
    ): Map<String, List<String>> =
        buildMap {
            for (filter in filters) {
                val heldValues = held[filter.id]
                val values =
                    if (heldValues == null) filter.defaultValues else pruneValues(filter, heldValues)
                if (values.isNotEmpty()) put(filter.id, values)
            }
        }

    private fun pruneValues(
        filter: SourceFilter,
        values: List<String>,
    ): List<String> =
        when (filter.type) {
            FilterControlType.SELECT, FilterControlType.MULTISELECT -> {
                val known = filter.options.map { it.value }.toSet()
                values.filter { it in known }
            }
            FilterControlType.TOGGLE -> values.filter { it == "true" || it == "false" }
            FilterControlType.TEXT, FilterControlType.NUMBER -> values.filter { it.isNotBlank() }
        }

    /**
     * S-6 (native parity): closing the search overlay also wipes the query (native `SearchAppBar`
     * navigation-icon `onClick = { onToggleSearch(); onQueryChange("") }`). Reset the query, both
     * result surfaces, and the active filter selections so re-opening search starts blank — and
     * cancel any in-flight searches so a stale collector can't push results into the cleared state.
     */
    private suspend fun onClose() {
        singleSearchJob?.cancel()
        multiSearchJob?.cancel()
        updateState {
            it.copy(
                query = "",
                single = UiState.Success(emptyList()),
                multi = emptyMap(),
                selections = emptyMap(),
                hasSearched = false,
            )
        }
        emit(SearchEffect.Close)
    }

    private fun onQueryChange(query: String) {
        // #16 SUBMIT-DRIVEN (native parity): typing only updates the query text; the search fires on
        // IME submit ([SearchIntent.OnSubmit]), not on every keystroke. This matches native, where
        // the field commits the query on the keyboard search action rather than live-searching.
        updateState { it.copy(query = query) }
    }

    private fun onSubmit() {
        // #16: run the mode-aware search for the current query on explicit submit. A typed query is a
        // fresh PLAIN single-source search (the filter selections stay filter-sheet display state
        // only — the F2 regression guard); MULTI submits fan out across repos.
        runSearch(state.value.query, state.value.mode, plainSingle = true)
    }

    /**
     * Re-run the single-source search that just failed (the error pane's Retry). Replays the exact
     * recorded parameters of the last single search — including the deliberately-blank query of a
     * filter browse — so Retry actually re-runs that browse instead of routing through the
     * blank-query guard and silently clearing the error to the pristine idle surface (aNum 5).
     */
    private fun onRetrySingle() {
        val params =
            lastSingleSearch ?: run {
                // No single search has run yet — fall back to a plain submit of the current query.
                onSubmit()
                return
            }
        runSingleSearch(params.query, selectionsOverride = params.selections)
    }

    private fun onModeTabChange(mode: SearchModeTab) {
        // #16: switching the single/multi tab is VIEW-ONLY — it does not fire a search. The already-
        // fetched results for each mode stay shown; the next submit fans out per the selected mode.
        updateState { it.copy(mode = mode) }
    }

    /**
     * A filter value changed in the sheet — IMMEDIATE-APPLY (native parity with the legacy
     * genre/sort taps: the search fires the instant the value changes; the sheet stays open).
     *
     * Legacy-parity quirk preserved: a non-empty change to the standard `genres` filter runs a
     * genre BROWSE with a blank text query (native `startSearch(GENRES(genres, query = ""))`).
     * Every other filter keeps the live query. With no active selection left and a blank query the
     * surface resets to idle (the legacy genre-clear behavior).
     */
    private fun onFilterChange(
        filterId: String,
        values: List<String>,
    ) {
        val filter = state.value.filters.firstOrNull { it.id == filterId } ?: return // unknown id — ignore
        val pruned = pruneValues(filter, values)
        updateState { it.copy(selections = it.selections + (filterId to pruned)) }
        val hasActiveSelection = state.value.selections.any { (_, v) -> v.isNotEmpty() }
        when {
            filterId == "genres" && pruned.isNotEmpty() -> runSingleSearch(query = "")
            hasActiveSelection -> runSingleSearch(state.value.query)
            else -> runSearch(state.value.query, SearchModeTab.SINGLE)
        }
    }

    /** Deterministic reset: every filter back to its declared defaults, then re-run like a change. */
    private fun onResetFilters() {
        val defaults =
            buildMap {
                for (filter in state.value.filters) {
                    if (filter.defaultValues.isNotEmpty()) put(filter.id, filter.defaultValues)
                }
            }
        updateState { it.copy(selections = defaults) }
        if (defaults.values.any { it.isNotEmpty() }) {
            runSingleSearch(state.value.query)
        } else {
            runSearch(state.value.query, SearchModeTab.SINGLE)
        }
    }

    /**
     * Dispatch a search for [query] in [mode]. Always cancels BOTH in-flight jobs first so a mode
     * switch or a new query never leaves a stale collector running (cancel-previous-query, locked
     * decision 4 for the multi fan-out; symmetric guard for the single source). [plainSingle]
     * forces an EMPTY selection set for the single-source path (the F2 submit guard).
     */
    private fun runSearch(
        query: String,
        mode: SearchModeTab,
        plainSingle: Boolean = false,
    ) {
        singleSearchJob?.cancel()
        multiSearchJob?.cancel()
        if (query.isBlank()) {
            // A blank submit/clear is not a search — reset to the pristine idle surface.
            updateState { it.copy(single = UiState.Success(emptyList()), multi = emptyMap(), hasSearched = false) }
            return
        }
        when (mode) {
            SearchModeTab.SINGLE ->
                runSingleSearch(query, selectionsOverride = if (plainSingle) FilterSelections.EMPTY else null)
            SearchModeTab.MULTI -> runMultiSearch(query)
        }
    }

    /**
     * Run a single-source search for [query]. [selectionsOverride] forces a specific selection set
     * (the plain-submit F2 guard passes [FilterSelections.EMPTY]; Retry replays the recorded set);
     * `null` sends the current sheet selections (non-empty entries only).
     */
    private fun runSingleSearch(
        query: String,
        selectionsOverride: FilterSelections? = null,
    ) {
        // Single-flight: cancel only the prior single-source search (cancel-and-replace), mirroring
        // native `startSearch`, which launches a fresh `mangaSearchItems` collection and never
        // touches the multi-repo fan-out. Leaving `multiSearchJob` alone means an immediate filter
        // change from the sheet won't wipe results the user is viewing on the MULTI tab (the
        // `runSearch` path still cancels both on a query change / mode switch).
        singleSearchJob?.cancel()
        updateState { it.copy(single = UiState.Loading, hasSearched = true) }
        val selections =
            selectionsOverride
                ?: FilterSelections(state.value.selections.filterValues { it.isNotEmpty() })
        // Record the resolved parameters so the error pane's Retry ([onRetrySingle]) can replay this
        // exact search (incl. the deliberately-blank query of a filter browse).
        lastSingleSearch = SingleSearchParams(query = query, selections = selections)
        singleSearchJob =
            launchSafely {
                searchSource(query = query, selections = selections)
                    .onSuccess { items -> updateState { it.copy(single = UiState.Success(items.distinctBy(HomeFeedItem::feedKey))) } }
                    .onFailure { error ->
                        updateState { it.copy(single = UiState.Error(error)) }
                        emit(SearchEffect.ShowError(error))
                    }
            }
    }

    private fun runMultiSearch(query: String) {
        updateState { it.copy(multi = emptyMap(), hasSearched = true) }
        multiSearchJob =
            searchAllRepos(query)
                .onEach { perRepo ->
                    val projected =
                        perRepo.mapValues { (_, result) ->
                            when (result) {
                                // null = that repo is still fetching → per-source spinner (RepoSection's
                                // Loading branch), instead of an empty-success "no results" while loading.
                                null -> UiState.Loading
                                is me.manga.kira.core.result.AppResult.Success ->
                                    UiState.Success(result.value.distinctBy(HomeFeedItem::feedKey))
                                is me.manga.kira.core.result.AppResult.Failure ->
                                    UiState.Error(result.error)
                            }
                        }
                    updateState { it.copy(multi = projected) }
                }.catch { t ->
                    val error = AppError.Unexpected(message = t.message ?: "search failed", cause = t)
                    // A flow-level throw bypasses the per-repo mapping above, so any tile still projected
                    // UiState.Loading at this point would spin forever. Resolve those stuck spinners to the
                    // error slot (repos that already succeeded/failed keep their resolved state).
                    updateState { s ->
                        s.copy(
                            multi =
                                s.multi.mapValues { (_, value) ->
                                    if (value is UiState.Loading) UiState.Error(error) else value
                                },
                        )
                    }
                    emit(SearchEffect.ShowError(error))
                }.launchIn(viewModelScope)
    }
}
