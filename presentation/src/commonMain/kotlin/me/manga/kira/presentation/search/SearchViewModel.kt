package me.manga.kira.presentation.search

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.onFailure
import me.manga.kira.core.result.onSuccess
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SearchMode
import me.manga.kira.domain.model.home.feedKey
import me.manga.kira.domain.usecase.home.LoadSearchFiltersUseCase
import me.manga.kira.domain.usecase.home.SearchAllReposUseCase
import me.manga.kira.domain.usecase.home.SearchSourceUseCase
import me.manga.kira.presentation.mvi.MviViewModel
import me.manga.kira.presentation.mvi.UiState

/**
 * Search overlay ViewModel (Epic H3b).
 *
 * Strict MVI; depends only on H1 `:domain` search use cases (DIP); Compose-free.
 *
 * Concurrency posture:
 *  - [singleSearchJob] is the single-flight single-source search (cancel-and-replace).
 *  - [multiSearchJob] is the multi-repo fan-out *collection* job. A new query (or a mode switch
 *    onto MULTI) cancels the prior collection so a stale fan-out can't keep pushing results for an
 *    abandoned query — the locked decision (4) "multi-repo fan-out cancels previous query".
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
     * re-run the EXACT failed search (a genre/sort browse runs with a blank query, which the
     * blank-query guard in [runSearch] would otherwise treat as "clear to idle").
     */
    private data class SingleSearchParams(
        val query: String,
        val mode: SearchMode,
        val sort: String?,
        val genres: List<String>,
    )

    private var lastSingleSearch: SingleSearchParams? = null

    override suspend fun handle(intent: SearchIntent) {
        when (intent) {
            SearchIntent.OnLoadFilters -> onLoadFilters()
            is SearchIntent.OnQueryChange -> onQueryChange(intent.query)
            SearchIntent.OnSubmit -> onSubmit()
            SearchIntent.OnRetrySingle -> onRetrySingle()
            is SearchIntent.OnModeTabChange -> onModeTabChange(intent.mode)
            is SearchIntent.OnApplyFilters -> onApplyFilters(intent.sort, intent.genres)
            is SearchIntent.OnGenreClick -> onGenreClick(intent.genre)
            is SearchIntent.OnSortSelect -> onSortSelect(intent.sort)
            is SearchIntent.OnMangaClick -> emit(
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
            .onSuccess { filters -> updateState { it.copy(filters = filters) } }
            // Keep the prior filters (the sheet shows whatever was last loaded) and surface the
            // failure instead of letting the throw die in the generic onUnhandledError log.
            .onFailure { error -> emit(SearchEffect.ShowError(error)) }
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
                selectedSort = null,
                selectedGenres = emptyList(),
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
        // fresh normal single-source search (the genre/sort selection stays filter-sheet display
        // state only — the F2 regression guard); MULTI submits fan out across repos.
        runSearch(state.value.query, state.value.mode, singleModeOverride = SearchMode.NORMAL)
    }

    /**
     * Re-run the single-source search that just failed (the error pane's Retry). Replays the exact
     * recorded parameters of the last single search — including the deliberately-blank query of a
     * genre/sort browse — so Retry actually re-runs that browse instead of routing through the
     * blank-query guard and silently clearing the error to the pristine idle surface (aNum 5).
     */
    private fun onRetrySingle() {
        val params = lastSingleSearch ?: run {
            // No single search has run yet — fall back to a plain submit of the current query.
            onSubmit()
            return
        }
        runSingleSearch(params.query, modeOverride = params.mode)
    }

    private fun onModeTabChange(mode: SearchModeTab) {
        // #16: switching the single/multi tab is VIEW-ONLY — it does not fire a search. The already-
        // fetched results for each mode stay shown; the next submit fans out per the selected mode.
        updateState { it.copy(mode = mode) }
    }

    private fun onApplyFilters(sort: String?, genres: List<String>) {
        updateState { it.copy(selectedSort = sort, selectedGenres = genres) }
        // Filters apply to the single-source search (legacy SearchType SORT/GENRES variants).
        // When a sort or genre is selected, route through runSingleSearch so the derived
        // SORT/GENRES mode is honoured even with a blank query (genre/sort browse hits the
        // source's listing endpoint with no text term) — going through runSearch would trip its
        // blank-query guard and silently wipe both result surfaces instead of running the filter
        // search (aNum 5: use the source's genres/sort when selected, don't drop them). With no
        // filters selected this is a plain text search, which runSearch resets to idle when blank.
        if (sort != null || genres.isNotEmpty()) {
            runSingleSearch(state.value.query)
        } else {
            runSearch(state.value.query, SearchModeTab.SINGLE)
        }
    }

    /**
     * A genre chip was tapped (legacy `MangaViewModel.onGenreClicked`). Native fires an IMMEDIATE
     * genre-browse search the instant the chip is tapped — the filter sheet stays open and results
     * update live behind it. The text query is intentionally blanked (genre-only browse: the
     * source's genre-listing endpoint is hit with no text term). Re-tapping the selected chip passes
     * `null`, which clears the genre selection and falls back to a normal query search.
     */
    private fun onGenreClick(genre: String?) {
        val genres = listOfNotNull(genre)
        updateState { it.copy(selectedGenres = genres) }
        // Blank the live query for the genre browse, matching native's
        // startSearch(GENRES(genres = type, query = "")).
        if (genres.isEmpty()) {
            // Genre cleared → re-run a normal search with whatever query is live.
            runSearch(state.value.query, SearchModeTab.SINGLE)
        } else {
            runSingleSearch(query = "", modeOverride = SearchMode.GENRES)
        }
    }

    /**
     * A sort option was chosen from the filter dropdown (legacy `MangaViewModel.onSortClick`).
     * Native fires an IMMEDIATE sorted search the instant the option is picked — only the dropdown
     * closes; the sheet stays open. The live query is preserved (legacy `onSortClick` passed the
     * current query straight through to `SearchType.SORT`).
     */
    private fun onSortSelect(sort: String) {
        updateState { it.copy(selectedSort = sort) }
        runSingleSearch(query = state.value.query, modeOverride = SearchMode.SORT)
    }

    /**
     * Dispatch a search for [query] in [mode]. Always cancels BOTH in-flight jobs first so a mode
     * switch or a new query never leaves a stale collector running (cancel-previous-query, locked
     * decision 4 for the multi fan-out; symmetric guard for the single source).
     */
    private fun runSearch(query: String, mode: SearchModeTab, singleModeOverride: SearchMode? = null) {
        singleSearchJob?.cancel()
        multiSearchJob?.cancel()
        if (query.isBlank()) {
            // A blank submit/clear is not a search — reset to the pristine idle surface.
            updateState { it.copy(single = UiState.Success(emptyList()), multi = emptyMap(), hasSearched = false) }
            return
        }
        when (mode) {
            SearchModeTab.SINGLE -> runSingleSearch(query, modeOverride = singleModeOverride)
            SearchModeTab.MULTI -> runMultiSearch(query)
        }
    }

    /**
     * Run a single-source search for [query]. [modeOverride] forces a specific [SearchMode] for the
     * immediate genre/sort taps (legacy `onGenreClicked` always fires GENRES, `onSortClick` always
     * fires SORT — the two are independent immediate actions, not derived from current selection
     * priority). When `null`, the mode is derived from the current selection (genre > sort > normal),
     * matching how a re-run after a plain query change picks the active filter.
     */
    private fun runSingleSearch(query: String, modeOverride: SearchMode? = null) {
        // Single-flight: cancel only the prior single-source search (cancel-and-replace), mirroring
        // native `startSearch`, which launches a fresh `mangaSearchItems` collection and never
        // touches the multi-repo fan-out. Leaving `multiSearchJob` alone means an immediate
        // genre/sort tap from the filter sheet won't wipe results the user is viewing on the MULTI
        // tab (the `runSearch` path still cancels both on a query change / mode switch).
        singleSearchJob?.cancel()
        updateState { it.copy(single = UiState.Loading, hasSearched = true) }
        val sort = state.value.selectedSort
        val genres = state.value.selectedGenres
        // S-9 (native parity): native's SORT search carries the chosen genre ALONGSIDE the sort type
        // (`SearchType.SORT(query, sortType, genres)`), so a selected sort takes precedence over a
        // selected genre and both are sent. The prior precedence picked GENRES whenever any genre was
        // selected, which dropped the sort entirely. Note `searchSource(...)` is already invoked with
        // BOTH `sort` and `genres` below, so SORT mode forwards the genre to the source.
        val searchMode = modeOverride ?: when {
            sort != null -> SearchMode.SORT
            genres.isNotEmpty() -> SearchMode.GENRES
            else -> SearchMode.NORMAL
        }
        // Record the resolved parameters so the error pane's Retry ([onRetrySingle]) can replay this
        // exact search (incl. the deliberately-blank query of a genre/sort browse).
        lastSingleSearch = SingleSearchParams(query = query, mode = searchMode, sort = sort, genres = genres)
        singleSearchJob = launchSafely {
            searchSource(query = query, mode = searchMode, sort = sort, genres = genres)
                .onSuccess { items -> updateState { it.copy(single = UiState.Success(items.distinctBy(HomeFeedItem::feedKey))) } }
                .onFailure { error ->
                    updateState { it.copy(single = UiState.Error(error)) }
                    emit(SearchEffect.ShowError(error))
                }
        }
    }

    private fun runMultiSearch(query: String) {
        updateState { it.copy(multi = emptyMap(), hasSearched = true) }
        multiSearchJob = searchAllRepos(query)
            .onEach { perRepo ->
                val projected = perRepo.mapValues { (_, result) ->
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
            }
            .catch { t ->
                val error = AppError.Unexpected(message = t.message ?: "search failed", cause = t)
                // A flow-level throw bypasses the per-repo mapping above, so any tile still projected
                // UiState.Loading at this point would spin forever. Resolve those stuck spinners to the
                // error slot (repos that already succeeded/failed keep their resolved state).
                updateState { s ->
                    s.copy(
                        multi = s.multi.mapValues { (_, value) ->
                            if (value is UiState.Loading) UiState.Error(error) else value
                        },
                    )
                }
                emit(SearchEffect.ShowError(error))
            }
            .launchIn(viewModelScope)
    }
}
