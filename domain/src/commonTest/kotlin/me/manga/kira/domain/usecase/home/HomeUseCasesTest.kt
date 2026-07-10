package me.manga.kira.domain.usecase.home

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.domain.repository.HomeFeedRepository
import me.manga.kira.domain.repository.SearchRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the Home + Search use cases (Epic H1b).
 *
 * Pins the thin-delegation contracts of the foundation use cases against fake repositories:
 * [ObserveSourceTabsUseCase] forwards the repo's tab flow, [FetchHomeFeedUseCase] forwards the
 * repo's [AppResult] verbatim, and [SearchAllReposUseCase] forwards the per-repo result map. The
 * strangler-fig source-routing + fan-out cancellation live in the `:data` impls and are out of
 * scope for these pure-`:domain` delegation tests.
 */
class HomeUseCasesTest {

    private fun feedItem(title: String) = HomeFeedItem(
        api = "src",
        language = "en",
        title = title,
        url = "https://src/$title",
        coverUrl = "",
        rating = null,
        genres = emptyList(),
        recentChapters = emptyList(),
    )

    private class FakeHomeFeedRepository : HomeFeedRepository {
        val tabs = MutableStateFlow<List<SourceTab>>(emptyList())
        val activeIndex = MutableStateFlow(0)
        val siteState = MutableStateFlow(SiteState.WORKING)
        var homeResult: AppResult<List<HomeFeedItem>> = AppResult.Success(emptyList())
        var moreResult: AppResult<List<HomeFeedItem>> = AppResult.Success(emptyList())
        var featuredResult: AppResult<List<FeaturedManga>> = AppResult.Success(emptyList())
        var sourceFilters: List<SourceFilter> = emptyList()
        val selectedTabs = mutableListOf<Int>()
        val selectedSources = mutableListOf<String>()
        var lastFetchReset: Boolean? = null

        override fun observeSourceTabs(): Flow<List<SourceTab>> = tabs
        override fun observeActiveTabIndex(): Flow<Int> = activeIndex
        override fun observeSiteState(api: String): Flow<SiteState> = siteState
        override suspend fun selectTab(index: Int) { selectedTabs += index }
        override suspend fun selectSource(api: String) { selectedSources += api }
        override suspend fun fetchHome(reset: Boolean): AppResult<List<HomeFeedItem>> {
            lastFetchReset = reset
            return homeResult
        }
        override suspend fun fetchMore(page: Int): AppResult<List<HomeFeedItem>> = moreResult
        override suspend fun fetchFeatured(): AppResult<List<FeaturedManga>> = featuredResult
        override suspend fun loadSourceFilters(): AppResult<List<SourceFilter>> = AppResult.Success(sourceFilters)
    }

    private class FakeSearchRepository : SearchRepository {
        var sourceResult: AppResult<List<HomeFeedItem>> = AppResult.Success(emptyList())
        val allReposResult = MutableStateFlow<Map<String, AppResult<List<HomeFeedItem>>>>(emptyMap())

        override suspend fun searchSource(
            query: String,
            selections: FilterSelections,
        ): AppResult<List<HomeFeedItem>> = sourceResult

        override fun searchAllRepos(query: String): Flow<Map<String, AppResult<List<HomeFeedItem>>>> =
            allReposResult
    }

    @Test
    fun observeSourceTabs_forwards_repository_flow() = runTest {
        val repo = FakeHomeFeedRepository()
        val useCase = ObserveSourceTabsUseCase(repo)

        useCase().test {
            assertEquals(emptyList(), awaitItem())
            val tabs = listOf(SourceTab("src", "en", iconKey = null, siteState = SiteState.WORKING))
            repo.tabs.value = tabs
            assertEquals(tabs, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun fetchHomeFeed_forwards_success_result_and_reset_flag() = runTest {
        val repo = FakeHomeFeedRepository()
        val items = listOf(feedItem("A"), feedItem("B"))
        repo.homeResult = AppResult.Success(items)
        val useCase = FetchHomeFeedUseCase(repo)

        val result = useCase(reset = true)

        assertEquals(AppResult.Success(items), result)
        assertEquals(true, repo.lastFetchReset)
    }

    @Test
    fun fetchHomeFeed_forwards_failure_result() = runTest {
        val repo = FakeHomeFeedRepository()
        repo.homeResult = AppResult.Failure(AppError.Network.NoConnectivity())
        val useCase = FetchHomeFeedUseCase(repo)

        val result = useCase(reset = false)

        assertTrue(result is AppResult.Failure)
        assertTrue(result.error is AppError.Network.NoConnectivity)
    }

    @Test
    fun searchAllRepos_forwards_per_repo_result_map() = runTest {
        val repo = FakeSearchRepository()
        val useCase = SearchAllReposUseCase(repo)

        useCase("naruto").test {
            assertEquals(emptyMap(), awaitItem())
            val map = mapOf(
                "srcA" to AppResult.Success(listOf(feedItem("A"))),
                "srcB" to AppResult.Failure(AppError.Network.Timeout()),
            )
            repo.allReposResult.value = map
            assertEquals(map, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
