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

    // F1 (native parity): tapping a genre chip fires an IMMEDIATE genre-browse search even when no
    // query is typed. F2: that search blanks the text query (genre-only browse) and runs the GENRES
    // mode — mirrors `MangaViewModel.onGenreClicked { startSearch(GENRES(genres = type, query = "")) }`.
    @Test
    fun genreClick_runsImmediateGenresSearch_withBlankQuery() =
        runTest {
            val vm = vm()
            searchRepo.singleResult = AppResult.Success(listOf(sampleFeedItem(title = "Hit")))
            vm.submit(SearchIntent.OnGenreClick("Action"))
            assertEquals(listOf("Action"), vm.state.value.selectedGenres)
            // Query is forced blank; mode is GENRES.
            assertTrue(searchRepo.calls.contains("searchSource(,GENRES)"), searchRepo.calls.toString())
        }

    // F1: re-tapping the selected genre clears it (null), returning to a normal search with the
    // live query.
    @Test
    fun genreReClick_clearsSelection_andRunsNormalSearch() =
        runTest {
            val vm = vm()
            searchRepo.singleResult = AppResult.Success(emptyList())
            vm.submit(SearchIntent.OnQueryChange("naruto"))
            vm.submit(SearchIntent.OnGenreClick("Action"))
            vm.submit(SearchIntent.OnGenreClick(null))
            assertTrue(
                vm.state.value.selectedGenres
                    .isEmpty(),
            )
            assertTrue(searchRepo.calls.contains("searchSource(naruto,NORMAL)"), searchRepo.calls.toString())
        }

    // F1: picking a sort option fires an IMMEDIATE sorted search, preserving the live query —
    // mirrors `MangaViewModel.onSortClick { startSearch(SORT(query = query, sortType = type, genres)) }`.
    @Test
    fun sortSelect_runsImmediateSortSearch_withLiveQuery() =
        runTest {
            val vm = vm()
            searchRepo.singleResult = AppResult.Success(emptyList())
            vm.submit(SearchIntent.OnQueryChange("one piece"))
            vm.submit(SearchIntent.OnSortSelect("Popular"))
            assertEquals("Popular", vm.state.value.selectedSort)
            assertTrue(searchRepo.calls.contains("searchSource(one piece,SORT)"), searchRepo.calls.toString())
        }

    // F2: after a genre browse, typing a new query fires a plain NORMAL search (the typed query is a
    // fresh normal search, not a continuation of the GENRES filter that would blank it) — mirrors
    // native `onSearchChange = { startSearch(SearchType.Normal(q)) }`.
    @Test
    fun queryChange_afterGenre_runsNormalSearch_notGenres() =
        runTest {
            val vm = vm()
            searchRepo.singleResult = AppResult.Success(emptyList())
            vm.submit(SearchIntent.OnGenreClick("Action"))
            searchRepo.calls.clear()
            vm.submit(SearchIntent.OnQueryChange("bleach"))
            vm.submit(SearchIntent.OnSubmit)
            assertTrue(searchRepo.calls.contains("searchSource(bleach,NORMAL)"), searchRepo.calls.toString())
            assertFalse(searchRepo.calls.any { it.contains("GENRES") }, searchRepo.calls.toString())
        }

    // S-9 (native parity): native's SORT search carries the chosen genre ALONGSIDE the sort type
    // (`SearchType.SORT(query, sortType, genres)`). On the derived path (applying both a sort and a
    // genre), a selected sort takes precedence so the request runs in SORT mode — NOT GENRES, which
    // would drop the sort. `searchSource(...)` is invoked with both `sort` and `genres` so the genre
    // still reaches the source.
    @Test
    fun applyFilters_withSortAndGenre_runsSortNotGenres() =
        runTest {
            val vm = vm()
            searchRepo.singleResult = AppResult.Success(emptyList())
            vm.submit(SearchIntent.OnQueryChange("one piece"))
            searchRepo.calls.clear()
            vm.submit(SearchIntent.OnApplyFilters(sort = "Popular", genres = listOf("Action")))
            assertEquals("Popular", vm.state.value.selectedSort)
            assertEquals(listOf("Action"), vm.state.value.selectedGenres)
            assertTrue(searchRepo.calls.contains("searchSource(one piece,SORT)"), searchRepo.calls.toString())
            assertFalse(searchRepo.calls.any { it.contains("GENRES") }, searchRepo.calls.toString())
        }

    // S-6 (native parity): closing the overlay wipes the query + results + filter selections so a
    // re-open starts blank (native `SearchAppBar` navigation-icon also calls `onQueryChange("")`).
    @Test
    fun close_clearsQueryResultsAndSelections() =
        runTest {
            val vm = vm()
            searchRepo.singleResult = AppResult.Success(listOf(sampleFeedItem(title = "Hit")))
            vm.submit(SearchIntent.OnQueryChange("naruto"))
            vm.submit(SearchIntent.OnGenreClick("Action"))
            vm.submit(SearchIntent.OnSortSelect("Popular"))

            vm.submit(SearchIntent.OnClose)

            val state = vm.state.value
            assertEquals("", state.query)
            assertTrue(state.selectedGenres.isEmpty(), state.selectedGenres.toString())
            assertEquals(null, state.selectedSort)
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
