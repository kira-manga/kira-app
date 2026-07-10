package me.manga.kira.presentation.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterOption
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.usecase.home.LoadSearchFiltersUseCase
import me.manga.kira.domain.usecase.home.SearchAllReposUseCase
import me.manga.kira.domain.usecase.home.SearchSourceUseCase
import me.manga.kira.presentation.testing.FakeHomeFeedRepository
import me.manga.kira.presentation.testing.FakeSearchRepository
import me.manga.kira.presentation.testing.sampleFeedItem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var searchRepo: FakeSearchRepository
    private lateinit var homeRepo: FakeHomeFeedRepository

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(): SearchViewModel {
        searchRepo = FakeSearchRepository()
        homeRepo = FakeHomeFeedRepository()
        return SearchViewModel(
            searchSource = SearchSourceUseCase(searchRepo),
            searchAllRepos = SearchAllReposUseCase(searchRepo),
            loadFilters = LoadSearchFiltersUseCase(homeRepo),
        )
    }

    @Test
    fun multiRepo_cancelsPreviousQuery_onNewQuery() =
        runTest {
            val vm = vm()
            vm.submit(SearchIntent.OnModeTabChange(SearchModeTab.MULTI))

            // First query's fan-out flow: never-completing SharedFlow, instrumented to record cancel.
            val firstCancelled = CompletableDeferred<Unit>()
            val firstFlow =
                MutableSharedFlow<Map<String, AppResult<List<HomeFeedItem>>>>(replay = 1)
                    .onCompletion { firstCancelled.complete(Unit) }
            searchRepo.multiFlows["one"] = firstFlow

            // Second query's fan-out flow: emits a result we can observe landed.
            val secondFlow = MutableSharedFlow<Map<String, AppResult<List<HomeFeedItem>>>>(replay = 1)
            searchRepo.multiFlows["two"] = secondFlow

            vm.submit(SearchIntent.OnQueryChange("one"))
            vm.submit(SearchIntent.OnSubmit) // #16 submit-driven: the search fires on submit, not typing
            assertTrue(searchRepo.calls.contains("searchAllRepos(one)"))
            assertFalse(firstCancelled.isCompleted, "first fan-out still collecting")

            // A new submit must cancel the previous collection.
            vm.submit(SearchIntent.OnQueryChange("two"))
            vm.submit(SearchIntent.OnSubmit)
            assertTrue(firstCancelled.isCompleted, "previous query's fan-out collection cancelled")
            assertTrue(searchRepo.calls.contains("searchAllRepos(two)"))

            // The second query's results land in state.
            secondFlow.emit(mapOf("repoB" to AppResult.Success(listOf(sampleFeedItem(title = "R")))))
            assertEquals(setOf("repoB"), vm.state.value.multi.keys)
        }

    @Test
    fun singleSearch_populatesSuccess() =
        runTest {
            val vm = vm()
            searchRepo.singleResult = AppResult.Success(listOf(sampleFeedItem(title = "Hit")))
            vm.submit(SearchIntent.OnQueryChange("q"))
            vm.submit(SearchIntent.OnSubmit)
            val single = vm.state.value.single
            assertTrue(single is me.manga.kira.presentation.mvi.UiState.Success)
            assertEquals(listOf("Hit"), single.data.map { it.title })
        }

    @Test
    fun blankQuery_clearsResults() =
        runTest {
            val vm = vm()
            searchRepo.singleResult = AppResult.Success(listOf(sampleFeedItem(title = "Hit")))
            vm.submit(SearchIntent.OnQueryChange("q"))
            vm.submit(SearchIntent.OnSubmit)
            vm.submit(SearchIntent.OnQueryChange(""))
            vm.submit(SearchIntent.OnSubmit)
            val single = vm.state.value.single
            assertTrue(single is me.manga.kira.presentation.mvi.UiState.Success)
            assertTrue(single.data.isEmpty())
        }

    // #16 (submit-driven): typing only updates the query — no search fires until OnSubmit.
    @Test
    fun queryChange_withoutSubmit_doesNotSearch() =
        runTest {
            val vm = vm()
            searchRepo.singleResult = AppResult.Success(listOf(sampleFeedItem(title = "Hit")))
            vm.submit(SearchIntent.OnQueryChange("q"))
            assertTrue(searchRepo.calls.none { it.startsWith("searchSource") }, searchRepo.calls.toString())
            assertEquals("q", vm.state.value.query)
        }

    // #16: switching the single/multi tab is view-only — it must NOT fan out a search.
    @Test
    fun modeTabChange_doesNotSearch() =
        runTest {
            val vm = vm()
            vm.submit(SearchIntent.OnModeTabChange(SearchModeTab.MULTI))
            assertEquals(SearchModeTab.MULTI, vm.state.value.mode)
            assertTrue(searchRepo.calls.isEmpty(), "tab switch is view-only: ${searchRepo.calls}")
        }

    /** Load a genres+sort filter pair (the legacy-adapter shape) into the VM's state. */
    private suspend fun SearchViewModel.loadStandardFilters() {
        homeRepo.sourceFilters =
            listOf(
                SourceFilter(
                    id = "genres",
                    label = "genres",
                    type = FilterControlType.SELECT,
                    options = listOf("Action", "Drama").map { FilterOption(it, it) },
                ),
                SourceFilter(
                    id = "sort",
                    label = "sort",
                    type = FilterControlType.SELECT,
                    options = listOf("Latest", "Popular").map { FilterOption(it, it) },
                ),
            )
        submit(SearchIntent.OnLoadFilters)
    }

    // F1 (native parity): a genre selection fires an IMMEDIATE genre-browse search even when no
    // query is typed, with the text query blanked (genre-only browse) — mirrors
    // `MangaViewModel.onGenreClicked { startSearch(GENRES(genres = type, query = "")) }`.
    @Test
    fun genresChange_runsImmediateBrowse_withBlankQuery() =
        runTest {
            val vm = vm()
            vm.loadStandardFilters()
            searchRepo.singleResult = AppResult.Success(listOf(sampleFeedItem(title = "Hit")))
            vm.submit(SearchIntent.OnFilterChange("genres", listOf("Action")))
            assertEquals(listOf("Action"), vm.state.value.selections["genres"])
            // Query is forced blank; the selections carry the genre.
            assertTrue(searchRepo.calls.any { it.startsWith("searchSource(,") }, searchRepo.calls.toString())
            assertEquals(mapOf("genres" to listOf("Action")), searchRepo.lastSelections?.byId)
        }

    // F1: clearing the genre selection returns to a normal search with the live query.
    @Test
    fun genresCleared_runsPlainSearch_withLiveQuery() =
        runTest {
            val vm = vm()
            vm.loadStandardFilters()
            searchRepo.singleResult = AppResult.Success(emptyList())
            vm.submit(SearchIntent.OnQueryChange("naruto"))
            vm.submit(SearchIntent.OnFilterChange("genres", listOf("Action")))
            vm.submit(SearchIntent.OnFilterChange("genres", emptyList()))
            assertTrue(vm.state.value.selections["genres"].orEmpty().isEmpty())
            assertTrue(searchRepo.calls.any { it.startsWith("searchSource(naruto,") }, searchRepo.calls.toString())
            assertEquals(emptyMap(), searchRepo.lastSelections?.byId)
        }

    // F1: picking a sort fires an IMMEDIATE sorted search, preserving the live query — mirrors
    // `MangaViewModel.onSortClick { startSearch(SORT(query = query, sortType = type, genres)) }`.
    @Test
    fun sortChange_runsImmediateSearch_withLiveQuery() =
        runTest {
            val vm = vm()
            vm.loadStandardFilters()
            searchRepo.singleResult = AppResult.Success(emptyList())
            vm.submit(SearchIntent.OnQueryChange("one piece"))
            vm.submit(SearchIntent.OnFilterChange("sort", listOf("Popular")))
            assertEquals(listOf("Popular"), vm.state.value.selections["sort"])
            assertTrue(searchRepo.calls.any { it.startsWith("searchSource(one piece,") }, searchRepo.calls.toString())
            assertEquals(mapOf("sort" to listOf("Popular")), searchRepo.lastSelections?.byId)
        }

    // F2 (regression guard): a typed submit is a PLAIN search — the sheet's selections stay
    // display-only on submit; they apply through the immediate-apply filter changes only. Mirrors
    // native `onSearchChange = { startSearch(SearchType.Normal(q)) }`.
    @Test
    fun submit_afterFilterSelection_runsPlainSearch() =
        runTest {
            val vm = vm()
            vm.loadStandardFilters()
            searchRepo.singleResult = AppResult.Success(emptyList())
            vm.submit(SearchIntent.OnFilterChange("genres", listOf("Action")))
            searchRepo.calls.clear()
            vm.submit(SearchIntent.OnQueryChange("bleach"))
            vm.submit(SearchIntent.OnSubmit)
            assertTrue(searchRepo.calls.any { it.startsWith("searchSource(bleach,") }, searchRepo.calls.toString())
            assertEquals(emptyMap(), searchRepo.lastSelections?.byId, "submit sends NO filter selections (F2)")
        }

    // Both a sort and a genre selected → ONE search carrying BOTH selections (the generic model
    // sends the whole selection set; the legacy sort>genres precedence is a `:data` translation
    // concern, pinned in GenericFilterRoutingTest).
    @Test
    fun sortAndGenre_bothTravelInTheSelections() =
        runTest {
            val vm = vm()
            vm.loadStandardFilters()
            searchRepo.singleResult = AppResult.Success(emptyList())
            vm.submit(SearchIntent.OnQueryChange("one piece"))
            vm.submit(SearchIntent.OnFilterChange("genres", listOf("Action")))
            vm.submit(SearchIntent.OnFilterChange("sort", listOf("Popular")))
            assertEquals(
                mapOf("genres" to listOf("Action"), "sort" to listOf("Popular")),
                searchRepo.lastSelections?.byId,
            )
        }

    // S-6 (native parity): closing the overlay wipes the query + results + filter selections so a
    // re-open starts blank (native `SearchAppBar` navigation-icon also calls `onQueryChange("")`).
    @Test
    fun close_clearsQueryResultsAndSelections() =
        runTest {
            val vm = vm()
            vm.loadStandardFilters()
            searchRepo.singleResult = AppResult.Success(listOf(sampleFeedItem(title = "Hit")))
            vm.submit(SearchIntent.OnQueryChange("naruto"))
            vm.submit(SearchIntent.OnFilterChange("genres", listOf("Action")))
            vm.submit(SearchIntent.OnFilterChange("sort", listOf("Popular")))

            vm.submit(SearchIntent.OnClose)

            val state = vm.state.value
            assertEquals("", state.query)
            assertTrue(state.selections.isEmpty(), state.selections.toString())
            val single = state.single
            assertTrue(single is me.manga.kira.presentation.mvi.UiState.Success && single.data.isEmpty())
            assertTrue(state.multi.isEmpty())
        }

    // Audit P1 regression pin (bare-launch → launchSafely): a search use case that THROWS instead
    // of returning a failure Result must be absorbed by the MviViewModel safety net — before the
    // fix the throw escaped `viewModelScope` (process crash on device; failed runTest here).
    @Test
    fun throwingSearch_isAbsorbedByTheSafetyNet_andTheVmKeepsWorking() =
        runTest {
            val vm = vm()
            searchRepo.searchSourceThrows = IllegalStateException("scraper bug")
            vm.submit(SearchIntent.OnQueryChange("q"))
            vm.submit(SearchIntent.OnSubmit)

            // Absorbed: the tile stays Loading (the documented degradation for an unmodelled throw),
            // and the VM is still alive…
            assertTrue(vm.state.value.single is me.manga.kira.presentation.mvi.UiState.Loading)

            // …and keeps processing intents: a healthy re-submit recovers.
            searchRepo.searchSourceThrows = null
            searchRepo.singleResult = AppResult.Success(listOf(sampleFeedItem(title = "Hit")))
            vm.submit(SearchIntent.OnSubmit)
            val single = vm.state.value.single
            assertTrue(single is me.manga.kira.presentation.mvi.UiState.Success)
            assertEquals(listOf("Hit"), single.data.map { it.title })
        }
}
