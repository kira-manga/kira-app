package me.manga.kira.presentation.search

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterOption
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.usecase.home.LoadSearchFiltersUseCase
import me.manga.kira.domain.usecase.home.SearchAllReposUseCase
import me.manga.kira.domain.usecase.home.SearchSourceUseCase
import me.manga.kira.presentation.mvi.UiState
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
class SearchViewModelFilterDraftTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val draftFilters =
        listOf(
            SourceFilter("author", "Author", FilterControlType.TEXT, defaultValues = listOf("seed")),
            SourceFilter("year", "Year", FilterControlType.NUMBER, defaultValues = listOf("1")),
        )
    private val sort =
        SourceFilter(
            "sort",
            "Sort",
            FilterControlType.SELECT,
            options = listOf("latest", "popular").map { FilterOption(it, it) },
            defaultValues = listOf("latest"),
        )
    private val clears = mapOf("author" to "  ", "year" to "")
    private val cleared = mapOf("author" to emptyList<String>(), "year" to emptyList())

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Fixture(
        filters: List<SourceFilter>,
        gate: CompletableDeferred<Unit>? = null,
        result: AppResult<List<SourceFilter>>? = null,
    ) {
        val home =
            FakeHomeFeedRepository().apply {
                sourceFilters = filters
                sourceFiltersGate = gate
                sourceFiltersResult = result
            }
        val search = FakeSearchRepository()
        val vm =
            SearchViewModel(
                searchSource = SearchSourceUseCase(search),
                searchAllRepos = SearchAllReposUseCase(search),
                loadFilters = LoadSearchFiltersUseCase(home),
            ).also { it.submit(SearchIntent.OnLoadFilters) }
    }

    @Test
    fun batch_merges_both_drafts_with_latest_immediate_selections_and_live_query() =
        runTest {
            val fixture = Fixture(draftFilters + listOf(sort, sort.copy(id = "genres", label = "Genres")))
            fixture.vm.submit(SearchIntent.OnQueryChange("earlier"))
            fixture.vm.submit(SearchIntent.OnFilterChange("genres", listOf("popular")))
            fixture.vm.submit(SearchIntent.OnFilterChange("sort", listOf("popular")))
            fixture.vm.submit(SearchIntent.OnQueryChange("live"))
            fixture.vm.submit(SearchIntent.OnModeTabChange(SearchModeTab.MULTI))
            fixture.search.calls.clear()
            fixture.vm.submit(
                SearchIntent.OnApplyFilterDrafts(
                    mapOf("author" to " edited ", "year" to "002026", "sort" to "latest", "ghost" to "ignored"),
                ),
            )
            fixture.assertSingleRequest(
                "live",
                mapOf(
                    "author" to listOf(" edited "),
                    "year" to listOf("002026"),
                    "sort" to listOf("popular"),
                    "genres" to listOf("popular"),
                ),
            )
            assertEquals(SearchModeTab.MULTI, fixture.vm.state.value.mode)
        }

    @Test
    fun blank_batch_clears_defaults_once_with_query_or_another_active_filter() =
        runTest {
            for (query in listOf("live", "")) {
                val fixture = Fixture(if (query.isBlank()) draftFilters + sort else draftFilters)
                val expected = cleared + if (query.isBlank()) mapOf("sort" to listOf("latest")) else emptyMap()
                fixture.vm.submit(SearchIntent.OnQueryChange(query))
                fixture.vm.submit(SearchIntent.OnApplyFilterDrafts(clears))
                fixture.assertSingleRequest(query, expected)
                fixture.vm.submit(SearchIntent.OnLoadFilters)
                fixture.vm.submit(SearchIntent.OnApplyFilterDrafts(clears))
                assertEquals(expected, fixture.vm.state.value.selections)
                assertEquals(1, fixture.search.calls.size, "reload and repeated clear must not search again")
            }
        }

    @Test
    fun unchanged_or_ineligible_batches_are_noops_but_a_new_empty_key_is_not() =
        runTest {
            val fixture = Fixture(draftFilters + sort)
            val before = fixture.vm.state.value
            val batches =
                listOf(
                    emptyMap(),
                    mapOf("ghost" to "value", "sort" to "popular"),
                    mapOf("author" to "seed", "year" to "1"),
                )
            for (batch in batches) {
                fixture.vm.submit(SearchIntent.OnApplyFilterDrafts(batch))
                assertEquals(before, fixture.vm.state.value)
                assertTrue(fixture.search.calls.isEmpty())
            }
            val defaultless = Fixture(draftFilters.map { it.copy(defaultValues = emptyList()) })
            defaultless.vm.submit(SearchIntent.OnQueryChange("live"))
            defaultless.vm.submit(SearchIntent.OnApplyFilterDrafts(mapOf("author" to " ")))
            defaultless.assertSingleRequest("live", mapOf("author" to emptyList()))
        }

    @Test
    fun last_blank_query_batch_clear_returns_to_idle_without_another_request() =
        runTest {
            val fixture = Fixture(draftFilters)
            val items = listOf(sampleFeedItem(title = "Browse"))
            fixture.search.singleResult = AppResult.Success(items)
            fixture.vm.submit(SearchIntent.OnApplyFilterDrafts(mapOf("author" to "changed", "year" to "2")))
            assertEquals(UiState.Success(items), fixture.vm.state.value.single)
            assertTrue(fixture.vm.state.value.hasSearched)
            fixture.vm.submit(SearchIntent.OnApplyFilterDrafts(clears))
            val state = fixture.vm.state.value
            assertEquals(cleared, state.selections)
            assertEquals(1, fixture.search.calls.size)
            assertEquals("", state.query)
            assertEquals(UiState.Success(emptyList<HomeFeedItem>()), state.single)
            assertEquals(emptyMap(), state.multi)
            assertFalse(state.hasSearched)
        }

    @Test
    fun active_batch_search_leaves_the_existing_multi_fan_out_running() =
        runTest {
            val fixture = Fixture(draftFilters)
            val cancelled = CompletableDeferred<Unit>()
            fixture.search.multiFlows["live"] =
                MutableSharedFlow<Map<String, AppResult<List<HomeFeedItem>>>>()
                    .onCompletion { cancelled.complete(Unit) }
            fixture.vm.submit(SearchIntent.OnModeTabChange(SearchModeTab.MULTI))
            fixture.vm.submit(SearchIntent.OnQueryChange("live"))
            fixture.vm.submit(SearchIntent.OnSubmit)
            assertEquals(listOf("searchAllRepos(live)"), fixture.search.calls)
            fixture.search.calls.clear()
            try {
                fixture.vm.submit(SearchIntent.OnApplyFilterDrafts(mapOf("author" to "changed")))
                fixture.assertSingleRequest("live", mapOf("author" to listOf("changed"), "year" to listOf("1")))
                assertFalse(cancelled.isCompleted, "an active filter batch must not cancel unrelated fan-out")
            } finally {
                fixture.vm.submit(SearchIntent.OnClose)
                runCurrent()
            }
            assertTrue(cancelled.isCompleted, "close still cancels the fan-out")
        }

    @Test
    fun same_session_reload_withholds_descriptors_and_preserves_explicit_clears() =
        runTest {
            val fixture = Fixture(draftFilters)
            fixture.vm.submit(SearchIntent.OnQueryChange("live"))
            fixture.vm.submit(SearchIntent.OnApplyFilterDrafts(clears))
            fixture.search.calls.clear()
            val gate = CompletableDeferred<Unit>()
            fixture.home.sourceFiltersGate = gate
            fixture.vm.submit(SearchIntent.OnLoadFilters)
            assertTrue(
                fixture.vm.state.value.filters
                    .isEmpty(),
            )
            assertEquals(cleared, fixture.vm.state.value.selections)
            fixture.vm.submit(SearchIntent.OnApplyFilterDrafts(mapOf("author" to "stale", "year" to "9")))
            assertEquals(cleared, fixture.vm.state.value.selections)
            assertTrue(fixture.search.calls.isEmpty())
            // The suspended reply must stay captured even if the next configured reply changes.
            fixture.home.sourceFilters = emptyList()
            gate.complete(Unit)
            runCurrent()
            assertEquals(draftFilters, fixture.vm.state.value.filters)
            assertEquals(cleared, fixture.vm.state.value.selections)
            assertTrue(fixture.search.calls.isEmpty())
        }

    @Test
    fun current_load_failure_stays_unavailable_preserves_selections_and_emits_its_error() =
        runTest {
            val fixture = Fixture(draftFilters)
            val held = fixture.vm.state.value.selections
            val error = AppError.Network.NoConnectivity()
            val gate = CompletableDeferred<Unit>()
            fixture.home.sourceFiltersGate = gate
            fixture.home.sourceFiltersResult = AppResult.Failure(error)
            fixture.vm.effects.test {
                fixture.vm.submit(SearchIntent.OnLoadFilters)
                assertTrue(
                    fixture.vm.state.value.filters
                        .isEmpty(),
                )
                gate.complete(Unit)
                runCurrent()
                assertEquals(SearchEffect.ShowError(error), awaitItem())
                assertTrue(
                    fixture.vm.state.value.filters
                        .isEmpty(),
                )
                fixture.vm.submit(SearchIntent.OnApplyFilterDrafts(mapOf("author" to "stale")))
                assertEquals(held, fixture.vm.state.value.selections)
                assertTrue(fixture.search.calls.isEmpty())
                expectNoEvents()
            }
        }

    @Test
    fun close_or_new_load_rejects_late_success_and_failure_without_reusing_generations() =
        runTest {
            val staleFilters = listOf(draftFilters.first().copy(id = "retired"))
            val replies =
                listOf(
                    AppResult.Success(staleFilters),
                    AppResult.Failure(AppError.Network.NoConnectivity()),
                )
            for (reply in replies) {
                assertLateLoadIgnored(reply, closeFirst = true, reload = false)
                assertLateLoadIgnored(reply, closeFirst = false, reload = true)
                assertLateLoadIgnored(reply, closeFirst = true, reload = true)
            }
        }

    private suspend fun TestScope.assertLateLoadIgnored(
        reply: AppResult<List<SourceFilter>>,
        closeFirst: Boolean,
        reload: Boolean,
    ) {
        val gate = CompletableDeferred<Unit>()
        val fixture = Fixture(emptyList(), gate, reply)
        fixture.vm.effects.test {
            if (closeFirst) {
                fixture.vm.submit(SearchIntent.OnClose)
                assertEquals(SearchEffect.Close, awaitItem())
            }
            if (reload) {
                fixture.home.sourceFilters = draftFilters
                fixture.home.sourceFiltersResult = null
                fixture.home.sourceFiltersGate = null
                fixture.vm.submit(SearchIntent.OnLoadFilters)
            }
            val current = fixture.vm.state.value
            assertEquals(if (reload) draftFilters else emptyList(), current.filters)
            val defaults = mapOf("author" to listOf("seed"), "year" to listOf("1"))
            assertEquals(if (reload) defaults else emptyMap(), current.selections)
            gate.complete(Unit)
            runCurrent()
            assertEquals(current, fixture.vm.state.value)
            assertTrue(fixture.search.calls.isEmpty())
            expectNoEvents()
        }
    }

    private fun Fixture.assertSingleRequest(
        query: String,
        expected: Map<String, List<String>>,
    ) {
        assertEquals(expected, vm.state.value.selections)
        assertEquals(expected, search.lastSelections?.byId)
        assertEquals(1, search.calls.size)
        assertTrue(search.calls.single().startsWith("searchSource($query,filters="))
    }
}
