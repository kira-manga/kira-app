package me.manga.kira.presentation.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeChapterRef
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SearchFilters
import me.manga.kira.domain.model.home.SearchMode
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.domain.repository.HomeFeedRepository
import me.manga.kira.domain.repository.SearchRepository

/**
 * Hand fakes for the `HomeFeedRepository` + `SearchRepository` that the H1 use cases wrap. Local to
 * `:presentation` commonTest (module test source sets are not shared). Reuses `FakeLibraryRepository`
 * from `LibraryTestFakes.kt` for the heart-sync use cases.
 */

fun sampleFeedItem(
    api: String = "api",
    language: String = "en",
    title: String = "Title",
    url: String = "https://example.test/$title",
    coverUrl: String = "",
    rating: Int? = null,
    genres: List<String> = emptyList(),
    recentChapters: List<HomeChapterRef> = emptyList(),
): HomeFeedItem = HomeFeedItem(api, language, title, url, coverUrl, rating, genres, recentChapters)

fun sampleSourceTab(
    api: String = "api",
    language: String = "en",
    iconKey: String? = null,
    siteState: SiteState = SiteState.WORKING,
): SourceTab = SourceTab(api, language, iconKey, siteState)

class FakeHomeFeedRepository : HomeFeedRepository {
    val sourceTabs = MutableStateFlow<List<SourceTab>>(emptyList())
    val activeTabIndex = MutableStateFlow(0)
    val siteState = MutableStateFlow(SiteState.WORKING)
    val calls: MutableList<String> = mutableListOf()

    /** Queued results per `fetchHome` call (FIFO); falls back to [defaultHome] when exhausted. */
    val homePages: ArrayDeque<AppResult<List<HomeFeedItem>>> = ArrayDeque()
    var defaultHome: AppResult<List<HomeFeedItem>> = AppResult.Success(emptyList())
    var moreResult: AppResult<List<HomeFeedItem>> = AppResult.Success(emptyList())

    /**
     * Optional gate: when set, `fetchMore` suspends on it before returning, so a test can hold the
     * first page-load in-flight and submit a second `OnEndReached` to exercise the double-load
     * guard. `null` (the default) keeps `fetchMore` non-suspending for the existing tests.
     */
    var fetchMoreGate: CompletableDeferred<Unit>? = null
    var featuredResult: AppResult<List<FeaturedManga>> = AppResult.Success(emptyList())
    var filters: SearchFilters = SearchFilters(sortTypes = emptyList(), genres = emptyList())

    override fun observeSourceTabs(): Flow<List<SourceTab>> = sourceTabs.asStateFlow()
    override fun observeActiveTabIndex(): Flow<Int> = activeTabIndex.asStateFlow()
    override fun observeSiteState(api: String): Flow<SiteState> = siteState.asStateFlow()

    override suspend fun selectTab(index: Int) { calls += "selectTab($index)"; activeTabIndex.value = index }
    override suspend fun selectSource(api: String) { calls += "selectSource($api)" }

    /** When set, [fetchHome] THROWS instead of returning — pins the launchSafely absorption path. */
    var fetchHomeThrows: Throwable? = null

    override suspend fun fetchHome(reset: Boolean): AppResult<List<HomeFeedItem>> {
        calls += "fetchHome(reset=$reset)"
        fetchHomeThrows?.let { throw it }
        return if (homePages.isNotEmpty()) homePages.removeFirst() else defaultHome
    }

    override suspend fun fetchMore(page: Int): AppResult<List<HomeFeedItem>> {
        calls += "fetchMore($page)"
        fetchMoreGate?.await()
        return moreResult
    }

    override suspend fun fetchFeatured(): AppResult<List<FeaturedManga>> {
        calls += "fetchFeatured()"
        return featuredResult
    }

    /** When set, [loadFilters] returns this instead of `AppResult.Success(filters)` (failure-path tests). */
    var filtersResult: AppResult<SearchFilters>? = null

    override suspend fun loadFilters(): AppResult<SearchFilters> {
        calls += "loadFilters()"
        return filtersResult ?: AppResult.Success(filters)
    }

    /** Config-driven filter descriptors (2026-07); [sourceFiltersResult] overrides for failure-path tests. */
    var sourceFilters: List<SourceFilter> = emptyList()
    var sourceFiltersResult: AppResult<List<SourceFilter>>? = null

    override suspend fun loadSourceFilters(): AppResult<List<SourceFilter>> {
        calls += "loadSourceFilters()"
        return sourceFiltersResult ?: AppResult.Success(sourceFilters)
    }
}

class FakeSearchRepository : SearchRepository {
    val calls: MutableList<String> = mutableListOf()
    var singleResult: AppResult<List<HomeFeedItem>> = AppResult.Success(emptyList())

    /** When set, [searchSource] THROWS instead of returning — pins the launchSafely absorption path. */
    var searchSourceThrows: Throwable? = null

    /** Per-query flows the multi-repo fan-out returns. New query → look up by query text. */
    val multiFlows: MutableMap<String, Flow<Map<String, AppResult<List<HomeFeedItem>>?>>> = mutableMapOf()

    override suspend fun searchSource(
        query: String,
        mode: SearchMode,
        sort: String?,
        genres: List<String>,
    ): AppResult<List<HomeFeedItem>> {
        calls += "searchSource($query,$mode)"
        searchSourceThrows?.let { throw it }
        return singleResult
    }

    /** The selections the last filtered [searchSource] received (config-driven filters, 2026-07). */
    var lastSelections: FilterSelections? = null

    override suspend fun searchSource(
        query: String,
        selections: FilterSelections,
    ): AppResult<List<HomeFeedItem>> {
        calls += "searchSource($query,filters=${selections.byId})"
        lastSelections = selections
        searchSourceThrows?.let { throw it }
        return singleResult
    }

    override fun searchAllRepos(query: String): Flow<Map<String, AppResult<List<HomeFeedItem>>?>> {
        calls += "searchAllRepos($query)"
        return multiFlows[query] ?: MutableStateFlow(emptyMap())
    }
}
