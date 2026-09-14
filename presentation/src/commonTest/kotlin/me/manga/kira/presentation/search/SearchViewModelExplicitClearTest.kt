package me.manga.kira.presentation.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class SearchViewModelExplicitClearTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Fixture(
        filters: List<SourceFilter>,
    ) {
        val home = FakeHomeFeedRepository().apply { sourceFilters = filters }
        val search = FakeSearchRepository()
        val vm =
            SearchViewModel(
                searchSource = SearchSourceUseCase(search),
                searchAllRepos = SearchAllReposUseCase(search),
                loadFilters = LoadSearchFiltersUseCase(home),
            ).also { it.submit(SearchIntent.OnLoadFilters) }
    }

    private fun filter(
        id: String,
        type: FilterControlType = FilterControlType.SELECT,
        defaults: List<String> = listOf("first"),
        options: List<String> = listOf("first", "second"),
    ) = SourceFilter(
        id = id,
        label = id,
        type = type,
        options =
            if (type == FilterControlType.SELECT || type == FilterControlType.MULTISELECT) {
                options.map { FilterOption(it, it) }
            } else {
                emptyList()
            },
        defaultValues = defaults,
    )

    @Test
    fun explicit_clears_preserve_each_shape_with_query_or_other_active_filter() =
        runTest {
            val types =
                listOf(
                    FilterControlType.SELECT,
                    FilterControlType.MULTISELECT,
                    FilterControlType.TEXT,
                    FilterControlType.NUMBER,
                )
            for (type in types) {
                assertClearForwarded(type, query = "live", withOtherFilter = false)
                assertClearForwarded(type, query = "", withOtherFilter = true)
            }
        }

    private fun assertClearForwarded(
        type: FilterControlType,
        query: String,
        withOtherFilter: Boolean,
    ) {
        val defaults = if (type == FilterControlType.NUMBER) listOf("1") else listOf("first")
        val cleared = filter("cleared", type, defaults = defaults)
        val fixture = Fixture(if (withOtherFilter) listOf(cleared, filter("other")) else listOf(cleared))
        val other = if (withOtherFilter) mapOf("other" to listOf("first")) else emptyMap()
        assertEquals(mapOf("cleared" to defaults) + other, fixture.vm.state.value.selections)
        fixture.vm.submit(SearchIntent.OnQueryChange(query))
        val clearedValues =
            when (type) {
                FilterControlType.TEXT, FilterControlType.NUMBER -> listOf("", "  ")
                else -> emptyList()
            }
        fixture.vm.submit(SearchIntent.OnFilterChange("cleared", clearedValues))
        val expected = mapOf("cleared" to emptyList<String>()) + other
        assertEquals(expected, fixture.vm.state.value.selections, "$type/$query state")
        assertEquals(expected, fixture.search.lastSelections?.byId, "$type/$query request")
        assertEquals(listOf("searchSource($query,filters=$expected)"), fixture.search.calls)
        assertTrue(fixture.vm.state.value.hasSearched)
    }

    @Test
    fun reloads_preserve_cleared_and_pruned_keys_without_seeding_untouched_filters() =
        runTest {
            val untouched = filter("untouched", defaults = emptyList())
            val future = filter("future", defaults = emptyList())
            val original = listOf("cleared", "pruned", "retired").map { filter(it) }
            val fixture = Fixture(original + listOf(untouched, future))
            assertEquals(setOf("cleared", "pruned", "retired"), fixture.vm.state.value.selections.keys)
            assertEquals(listOf("first"), fixture.vm.state.value.selections["pruned"])
            fixture.vm.submit(SearchIntent.OnFilterChange("cleared", emptyList()))
            fixture.search.calls.clear()
            fixture.home.sourceFilters =
                listOf(
                    filter("cleared"),
                    filter("pruned", defaults = listOf("second"), options = listOf("second")),
                    untouched,
                    future.copy(defaultValues = listOf("second")),
                )
            val expected = mapOf("cleared" to emptyList(), "pruned" to emptyList(), "future" to listOf("second"))
            repeat(2) { reload ->
                fixture.vm.submit(SearchIntent.OnLoadFilters)
                assertEquals(expected, fixture.vm.state.value.selections, "reload ${reload + 1}")
                assertTrue(fixture.search.calls.isEmpty())
            }
        }

    @Test
    fun retry_replays_the_failed_empty_key_snapshot_after_display_state_changes() =
        runTest {
            val fixture = Fixture(listOf(filter("cleared")))
            val error = AppError.Network.NoConnectivity()
            val expected = mapOf("cleared" to emptyList<String>())
            val request = "searchSource(original,filters=$expected)"
            fixture.search.singleResult = AppResult.Failure(error)
            fixture.vm.submit(SearchIntent.OnQueryChange("original"))
            fixture.vm.submit(SearchIntent.OnFilterChange("cleared", emptyList()))
            assertEquals(UiState.Error(error), fixture.vm.state.value.single)
            assertEquals(listOf(request), fixture.search.calls)
            fixture.vm.submit(SearchIntent.OnQueryChange("later"))
            fixture.vm.submit(SearchIntent.OnModeTabChange(SearchModeTab.MULTI))
            fixture.home.sourceFilters = emptyList()
            fixture.vm.submit(SearchIntent.OnLoadFilters)
            assertEquals(emptyMap(), fixture.vm.state.value.selections)
            assertEquals(listOf(request), fixture.search.calls)
            val items = listOf(sampleFeedItem(title = "Retry"))
            fixture.search.singleResult = AppResult.Success(items)
            fixture.vm.submit(SearchIntent.OnRetrySingle)
            assertEquals(expected, fixture.search.lastSelections?.byId)
            assertEquals(listOf(request, request), fixture.search.calls)
            assertEquals(UiState.Success(items), fixture.vm.state.value.single)
            assertEquals("later", fixture.vm.state.value.query)
            assertEquals(SearchModeTab.MULTI, fixture.vm.state.value.mode)
            assertEquals(emptyMap(), fixture.vm.state.value.selections)
        }

    @Test
    fun last_filter_clear_with_blank_query_returns_to_idle_without_a_request() =
        runTest {
            val fixture = Fixture(listOf(filter("genres", FilterControlType.MULTISELECT, defaults = emptyList())))
            val items = listOf(sampleFeedItem(title = "Browse"))
            fixture.search.singleResult = AppResult.Success(items)
            fixture.vm.submit(SearchIntent.OnFilterChange("genres", listOf("first")))
            assertEquals(listOf("searchSource(,filters={genres=[first]})"), fixture.search.calls)
            assertEquals(UiState.Success(items), fixture.vm.state.value.single)
            assertTrue(fixture.vm.state.value.hasSearched)
            fixture.vm.submit(SearchIntent.OnFilterChange("genres", emptyList()))
            val state = fixture.vm.state.value
            assertEquals(mapOf("genres" to emptyList()), state.selections)
            assertEquals(1, fixture.search.calls.size)
            assertEquals("", state.query)
            assertEquals(UiState.Success(emptyList<HomeFeedItem>()), state.single)
            assertEquals(emptyMap(), state.multi)
            assertFalse(state.hasSearched)
        }
}
