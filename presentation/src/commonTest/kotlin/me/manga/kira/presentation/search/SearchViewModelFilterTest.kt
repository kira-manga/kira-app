package me.manga.kira.presentation.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterOption
import me.manga.kira.domain.model.filters.SourceFilter
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
import kotlin.test.assertTrue

/**
 * Filter-STATE invariants of the generic filter pipeline (config-driven filters, 2026-07 —
 * CONFIG_DRIVEN_FILTERS_PLAN.md §8 items 8/15/16): defaults + deterministic reset, source-switch
 * reconciliation (no leaked selections, stale ids/values dropped), unknown-id defensiveness, and
 * the no-filter plain-search floor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelFilterTest {
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

    private fun select(
        id: String,
        vararg optionValues: String,
        defaults: List<String> = emptyList(),
        type: FilterControlType = FilterControlType.SELECT,
    ) = SourceFilter(
        id = id,
        label = id,
        type = type,
        options = optionValues.map { FilterOption(it, it) },
        defaultValues = defaults,
    )

    @Test
    fun defaults_are_seeded_on_filter_load_and_selection_overrides_them() =
        runTest {
            val vm = vm()
            homeRepo.sourceFilters =
                listOf(
                    select("sort", "latest", "views", defaults = listOf("latest")),
                    select("genres", "action", "drama", type = FilterControlType.MULTISELECT),
                )
            vm.submit(SearchIntent.OnLoadFilters)

            assertEquals(mapOf("sort" to listOf("latest")), vm.state.value.selections)

            vm.submit(SearchIntent.OnFilterChange("sort", listOf("views")))
            assertEquals(listOf("views"), vm.state.value.selections["sort"])
        }

    @Test
    fun reset_restores_the_declared_defaults_deterministically_and_reruns_the_search() =
        runTest {
            val vm = vm()
            homeRepo.sourceFilters =
                listOf(
                    select("sort", "latest", "views", defaults = listOf("latest")),
                    select("genres", "action", "drama", type = FilterControlType.MULTISELECT),
                )
            vm.submit(SearchIntent.OnLoadFilters)
            searchRepo.singleResult = AppResult.Success(listOf(sampleFeedItem(title = "Hit")))
            vm.submit(SearchIntent.OnQueryChange("q"))
            vm.submit(SearchIntent.OnFilterChange("sort", listOf("views")))
            vm.submit(SearchIntent.OnFilterChange("genres", listOf("drama")))
            searchRepo.calls.clear()

            vm.submit(SearchIntent.OnResetFilters)

            assertEquals(mapOf("sort" to listOf("latest")), vm.state.value.selections)
            assertEquals(mapOf("sort" to listOf("latest")), searchRepo.lastSelections?.byId, "reset re-runs with the defaults")
        }

    @Test
    fun switching_sources_drops_incompatible_selections_and_seeds_the_new_defaults() =
        runTest {
            // Source A: genres with Arabic-source values. The user selects one.
            val vm = vm()
            homeRepo.sourceFilters = listOf(select("genres", "action", "drama"))
            vm.submit(SearchIntent.OnLoadFilters)
            vm.submit(SearchIntent.OnFilterChange("genres", listOf("action")))
            assertEquals(listOf("action"), vm.state.value.selections["genres"])

            // The overlay closes (source switches happen on Home), then reopens on source B whose
            // filters share the `genres` id but with DIFFERENT option values, plus a defaulted sort.
            vm.submit(SearchIntent.OnClose)
            homeRepo.sourceFilters =
                listOf(
                    select("genres", "isekai", "romance"),
                    select("sort", "top", "new", defaults = listOf("top")),
                )
            vm.submit(SearchIntent.OnLoadFilters)

            // No leak: the old "action" value is gone (close cleared it; reconciliation would drop
            // it as an unknown value anyway), and source B's defaults are seeded.
            assertEquals(mapOf("sort" to listOf("top")), vm.state.value.selections)
        }

    @Test
    fun reload_prunes_unknown_ids_and_stale_option_values_from_held_state() =
        runTest {
            // Held state survives a filter RELOAD on the same overlay (no close): the source's
            // config was re-authored — `year` retired, genre option "drama" removed.
            val vm = vm()
            homeRepo.sourceFilters =
                listOf(
                    select("genres", "action", "drama", type = FilterControlType.MULTISELECT),
                    select("year", "2024", "2025"),
                )
            vm.submit(SearchIntent.OnLoadFilters)
            vm.submit(SearchIntent.OnFilterChange("genres", listOf("action", "drama")))
            vm.submit(SearchIntent.OnFilterChange("year", listOf("2024")))

            homeRepo.sourceFilters = listOf(select("genres", "action", type = FilterControlType.MULTISELECT))
            vm.submit(SearchIntent.OnLoadFilters)

            assertEquals(mapOf("genres" to listOf("action")), vm.state.value.selections)
        }

    @Test
    fun changes_to_unknown_filter_ids_are_ignored() =
        runTest {
            val vm = vm()
            homeRepo.sourceFilters = listOf(select("genres", "action"))
            vm.submit(SearchIntent.OnLoadFilters)

            vm.submit(SearchIntent.OnFilterChange("ghost", listOf("boo")))

            assertTrue(
                vm.state.value.selections
                    .isEmpty(),
                vm.state.value.selections
                    .toString(),
            )
            assertTrue(searchRepo.calls.isEmpty(), "an unknown-id change must not fire a search")
        }

    @Test
    fun a_source_without_filters_still_plain_searches() =
        runTest {
            val vm = vm()
            homeRepo.sourceFilters = emptyList()
            vm.submit(SearchIntent.OnLoadFilters)
            searchRepo.singleResult = AppResult.Success(listOf(sampleFeedItem(title = "Hit")))

            vm.submit(SearchIntent.OnQueryChange("naruto"))
            vm.submit(SearchIntent.OnSubmit)

            assertTrue(
                vm.state.value.filters
                    .isEmpty(),
            )
            assertEquals(emptyMap(), searchRepo.lastSelections?.byId)
            val single = vm.state.value.single
            assertTrue(single is me.manga.kira.presentation.mvi.UiState.Success)
            assertEquals(listOf("Hit"), single.data.map { it.title })
        }

    @Test
    fun selections_live_in_mvi_state_so_they_survive_recomposition_by_construction() =
        runTest {
            // Recomposition reads state anew — the invariant is simply that selections are IN the
            // state (not in composable-local memory) and unchanged by unrelated state updates.
            val vm = vm()
            homeRepo.sourceFilters = listOf(select("genres", "action"))
            vm.submit(SearchIntent.OnLoadFilters)
            vm.submit(SearchIntent.OnFilterChange("genres", listOf("action")))

            vm.submit(SearchIntent.OnQueryChange("typed later"))
            vm.submit(SearchIntent.OnModeTabChange(SearchModeTab.MULTI))

            assertEquals(listOf("action"), vm.state.value.selections["genres"])
        }
}
