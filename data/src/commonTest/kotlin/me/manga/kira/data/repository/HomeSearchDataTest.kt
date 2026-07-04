package me.manga.kira.data.repository

import app.cash.turbine.test
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.states.State as LegacyState
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.data.mapper.searchTypeOf
import me.manga.kira.data.mapper.toFeatured
import me.manga.kira.data.mapper.toHomeFeedItem
import me.manga.kira.data.mapper.toSiteState
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SearchMode
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.model.ChapterItem as LegacyChapterItem
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem as LegacyMangaItem
import me.manga.kira.domain.model.PopularManga as LegacyPopularManga
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.presentation.features.repo_settings.domain.SourceState
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as LegacySourcesRepository
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources_repositry.BaseMangaRepository
import me.manga.kira.sources_repositry.pt.manhastro.ManhastroDadosStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the Home + Search `:data` slice (Epic H2): mapper round-trips + the strangler-fig
 * repo impls against fakes for the legacy `BaseMangaRepository` / `SourcesRepository`.
 *
 * Matches the existing `:data` / `:domain` fake-based commonTest style (kotlin-test + turbine,
 * `StandardTestDispatcher` via a test [DispatcherProvider]). Confines the legacy types to the test
 * the same way the impls do.
 */
class HomeSearchDataTest {

    // --- Test doubles ---------------------------------------------------------------------------

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    /** Minimal [BaseMangaRepository] fake: each feed method returns a preset legacy [LegacyState] flow. */
    private class FakeMangaRepo(
        private val api: String,
        private val language: String = "en",
        var home: LegacyState<List<LegacyMangaItem>> = LegacyState.Success(emptyList()),
        var more: LegacyState<List<LegacyMangaItem>> = LegacyState.Success(emptyList()),
        var popular: LegacyState<List<LegacyPopularManga>> = LegacyState.Success(emptyList()),
        var search: LegacyState<List<LegacyMangaItem>> = LegacyState.Success(emptyList()),
        override val sortTypes: Set<String> = emptySet(),
        override val allGenres: Set<String> = emptySet(),
    ) : BaseMangaRepository() {
        var lastSearchType: SearchType? = null
        var initSiteCalls = 0

        override val BASE_URL: String = "https://$api/"
        override val URL_VERSION: Int = 0
        override var baseUrl: String = "https://$api/"
        override var imgBaseUrl: String = "https://$api/img/"
        override var imgUrlVersion: Int = 0
        override val API: String = api
        override val LANGUAGE: String = language
        override val ICON: Int = 0
        override val PRIORITY: Int = 0
        override val blackListGenres: Set<String> = emptySet()
        override val defaultHeaders: Map<String, String> = emptyMap()

        override suspend fun fetchSearchDataF(searchType: SearchType): Flow<LegacyState<List<LegacyMangaItem>>> {
            lastSearchType = searchType
            return flowOf(search)
        }

        override fun fetchMangaHomeF(query: String): Flow<LegacyState<MutableList<LegacyMangaItem>>> =
            flow { emit(home.asMutable()) }

        override suspend fun fetchMangaChaptersF(query: String): Flow<LegacyState<MangaInfo>> =
            flowOf(LegacyState.Loading)

        override fun fetchChapterDataF(url: String): Flow<LegacyState<List<String>>> =
            flowOf(LegacyState.Success(emptyList()))

        override fun fetchMoreManga(
            page: Int,
            currentItems: List<LegacyMangaItem>?,
        ): Flow<LegacyState<List<LegacyMangaItem>>> = flowOf(more)

        override suspend fun fetchPopularManga(baseUrl: String): Flow<LegacyState<List<LegacyPopularManga>>> =
            flowOf(popular)

        override suspend fun refreshHeaders(newHeaders: Map<String, String>) {}
        override suspend fun getBaseUrl(): String = baseUrl
        override suspend fun initSite(): Int { initSiteCalls++; return 0 }

        // buildImageRequest / buildItemsImageRequest are `open` (not abstract) on BaseMangaRepository,
        // so the fake inherits them — no need to override (avoids a coil3 dep in `:data` test).

        private fun LegacyState<List<LegacyMangaItem>>.asMutable(): LegacyState<MutableList<LegacyMangaItem>> =
            when (this) {
                is LegacyState.Success -> LegacyState.Success(data.toMutableList())
                is LegacyState.Error -> this
                LegacyState.Loading -> LegacyState.Loading
            }
    }

    /**
     * In-memory [SourcesDao] fake seeding one `SourcesEntity` row per repo (all enabled), so the
     * real legacy [LegacySourcesRepository] resolves its `activeRepo` / `getEnabledRepos` /
     * `allSources` / `getSiteStateFlow` over these fakes without a real Room database.
     */
    private class FakeSourcesDao(repos: List<BaseMangaRepository>) : SourcesDao {
        private val rows = repos.mapIndexed { i, repo ->
            SourcesEntity(
                name = repo.API,
                isEnabled = true,
                priority = i,
                language = repo.LANGUAGE,
                siteState = SourceState.WORKING,
                baseUrl = repo.BASE_URL,
                baseVersion = repo.URL_VERSION,
                imageBaseUrl = repo.imgBaseUrl,
                imageUrlVersion = repo.imgUrlVersion,
            )
        }

        override fun getAllSources(): Flow<List<SourcesEntity>> = flowOf(rows)
        override suspend fun insert(source: SourcesEntity): Long = 1L
        override suspend fun setEnabledByName(name: String, enabled: Boolean): Int = 1
        override suspend fun getBaseUrlFor(name: String): String? =
            rows.firstOrNull { it.name == name }?.baseUrl
        override fun getSiteStateByName(name: String): Flow<SourceState?> =
            flowOf(rows.firstOrNull { it.name == name }?.siteState)
        override suspend fun getSiteStateByNameSync(name: String): SourceState? =
            rows.firstOrNull { it.name == name }?.siteState
        // P0-SRCSEED added these remote-seed write methods to SourcesDao; this fake no-ops them.
        override suspend fun updateBaseUrlAndVersionByName(name: String, baseUrl: String, version: Int): Int = 0
        override suspend fun updateImageBaseUrlAndVersionByName(apiName: String, newImageBaseUrl: String, newImageVersion: Int): Int = 0
        override suspend fun updateSiteStateByName(name: String, siteState: SourceState): Int = 0
        override suspend fun deleteSourceByName(name: String): Int = 0
    }

    /** Build a real legacy [LegacySourcesRepository] over in-memory fakes. */
    private fun legacySources(
        repos: List<BaseMangaRepository>,
        scope: CoroutineScope,
    ): LegacySourcesRepository = LegacySourcesRepository(
        sourcesDao = FakeSourcesDao(repos),
        repos = repos.toSet(),
        prefs = SharedPrefsHelper(MapSettings()),
        applicationScope = scope,
    )

    // --- Mapper round-trips ---------------------------------------------------------------------

    @Test
    fun mangaItem_maps_to_homeFeedItem_with_chapter_refs() {
        val legacy = LegacyMangaItem(
            api = "MangaDex",
            language = "en",
            title = "Naruto",
            url = "https://md/naruto",
            imageUrl = "https://md/naruto.jpg",
            rating = 9,
            chapters = listOf(
                LegacyChapterItem(number = "700", name = "End", url = "https://md/c700", isDownloaded = true),
                LegacyChapterItem(number = "1", url = "https://md/c1", isDownloaded = false),
            ),
            genres = listOf("Action", "Shounen"),
        )

        val domain = legacy.toHomeFeedItem()

        assertEquals("MangaDex", domain.api)
        assertEquals("en", domain.language)
        assertEquals("Naruto", domain.title)
        assertEquals("https://md/naruto", domain.url)
        assertEquals("https://md/naruto.jpg", domain.coverUrl) // imageUrl → coverUrl
        assertEquals(9, domain.rating)
        assertEquals(listOf("Action", "Shounen"), domain.genres)
        assertEquals(2, domain.recentChapters.size)
        assertEquals("700", domain.recentChapters[0].number)
        assertEquals("https://md/c700", domain.recentChapters[0].url)
        assertTrue(domain.recentChapters[0].isDownloaded)
        assertEquals(false, domain.recentChapters[1].isDownloaded)
    }

    @Test
    fun mangaItem_with_null_chapters_maps_to_empty_recentChapters() {
        val legacy = LegacyMangaItem(
            api = "src", language = "en", title = "T", url = "u",
            imageUrl = "", rating = null, chapters = null, genres = emptyList(),
        )
        assertEquals(emptyList(), legacy.toHomeFeedItem().recentChapters)
    }

    @Test
    fun popularManga_maps_to_featured() {
        val legacy = LegacyPopularManga(
            api = "src", language = "ar", title = "Popular", url = "https://src/p", imageUrl = "https://src/p.jpg",
        )
        val featured = legacy.toFeatured()
        assertEquals("src", featured.api)
        assertEquals("ar", featured.language)
        assertEquals("Popular", featured.title)
        assertEquals("https://src/p", featured.url)
        assertEquals("https://src/p.jpg", featured.coverUrl)
    }

    @Test
    fun sourceState_maps_value_for_value_to_siteState() {
        assertEquals(SiteState.WORKING, SourceState.WORKING.toSiteState())
        assertEquals(SiteState.UNDER_MAINTENANCE, SourceState.UNDER_MAINTENANCE.toSiteState())
        assertEquals(SiteState.STOPPED, SourceState.STOPPED.toSiteState())
        assertEquals(SiteState.ADULT_18_PLUS, SourceState.ADULT_18_PLUS.toSiteState())
    }

    @Test
    fun searchMode_maps_to_legacy_searchType() {
        val normal = searchTypeOf("naruto", SearchMode.NORMAL, sort = null, genres = emptyList())
        assertTrue(normal is SearchType.Normal)
        assertEquals("naruto", (normal as SearchType.Normal).query)

        val sort = searchTypeOf("q", SearchMode.SORT, sort = "Latest", genres = listOf("Action", "Drama"))
        assertTrue(sort is SearchType.SORT)
        sort as SearchType.SORT
        assertEquals("q", sort.query)
        assertEquals("Latest", sort.sortType)
        assertEquals("Action,Drama", sort.genres)

        val genres = searchTypeOf("", SearchMode.GENRES, sort = null, genres = listOf("Action"))
        assertTrue(genres is SearchType.GENRES)
        assertEquals("Action", (genres as SearchType.GENRES).genres)
    }

    // --- HomeFeedRepositoryImpl.fetchHome -------------------------------------------------------

    /** A registry where nothing is piloted — Home/Search take the unchanged legacy path. */
    private object NoPilotRegistry : SourceRegistry {
        override fun get(api: String): MangaSourceClient? = null
        override fun availableApis(): List<String> = emptyList()
        override fun isConfigBacked(api: String): Boolean = false
    }

    /** A registry whose given apis are piloted, each served by a fixed stub client. */
    private class PilotedRegistry(private val clients: Map<String, MangaSourceClient>) : SourceRegistry {
        override fun get(api: String): MangaSourceClient? = clients[api]
        override fun availableApis(): List<String> = clients.keys.toList()
        override fun isConfigBacked(api: String): Boolean = api in clients
    }

    /** Minimal config-backed client returning a fixed search result. */
    private class StubSearchClient(
        override val api: String,
        private val searchResult: AppResult<List<HomeFeedItem>>,
    ) : MangaSourceClient {
        override suspend fun home(page: Int) = AppResult.Success(emptyList<HomeFeedItem>())
        override suspend fun featured(page: Int) = AppResult.Success(emptyList<FeaturedManga>())
        override suspend fun search(query: String, page: Int) = searchResult
        override suspend fun details(manga: Manga): AppResult<MangaDetails> = error("unused")
        override fun pages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> = error("unused")
    }

    private fun CoroutineScope.homeRepo(active: FakeMangaRepo): HomeFeedRepositoryImpl =
        HomeFeedRepositoryImpl(
            sourcesRepository = legacySources(listOf(active), this),
            dadosStore = ManhastroDadosStore(),
            dispatchers = testDispatchers,
            // No source is piloted here → these tests exercise the unchanged legacy path.
            sourceRegistry = NoPilotRegistry,
        )

    @Test
    fun fetchHome_success_maps_items() = runTest {
        val active = FakeMangaRepo(
            api = "src",
            home = LegacyState.Success(
                listOf(
                    LegacyMangaItem("src", "en", "A", "ua", "ia", null, null, emptyList()),
                    LegacyMangaItem("src", "en", "B", "ub", "ib", 5, null, listOf("g")),
                ),
            ),
        )
        val result = homeRepo(active).fetchHome(reset = true)
        assertTrue(result is AppResult.Success)
        result as AppResult.Success
        assertEquals(2, result.value.size)
        assertEquals("A", result.value[0].title)
        assertEquals("ib", result.value[1].coverUrl)
        assertEquals(1, active.initSiteCalls) // initSite() called before fetch, like the legacy VM
    }

    @Test
    fun fetchHome_error_maps_to_appError_bucket() = runTest {
        val active = FakeMangaRepo(
            api = "src",
            home = LegacyState.Error(code = 503, message = "Service Unavailable"),
        )
        val result = homeRepo(active).fetchHome(reset = true)
        assertTrue(result is AppResult.Failure)
        val err = (result as AppResult.Failure).error
        assertTrue(err is AppError.Network.Http)
        assertEquals(503, (err as AppError.Network.Http).statusCode)
    }

    @Test
    fun fetchHome_connectivity_error_maps_to_noConnectivity() = runTest {
        val active = FakeMangaRepo(
            api = "src",
            home = LegacyState.Error(code = 0, message = "Unknown host api.example.com"),
        )
        val result = homeRepo(active).fetchHome(reset = false)
        assertTrue(result is AppResult.Failure)
        assertTrue((result as AppResult.Failure).error is AppError.Network.NoConnectivity)
    }

    // --- SearchRepositoryImpl.searchAllRepos ----------------------------------------------------

    @Test
    fun searchAllRepos_emits_per_repo_map() = runTest {
        // Phase 5/6: search-all fans out ONLY to config-backed (piloted) sources, so A + B are piloted
        // and served by the registry's generic clients (one Success, one Failure). A non-piloted source
        // would be excluded entirely (covered by AzoraHomeSearchRoutingTest).
        val repoA = FakeMangaRepo(api = "A")
        val repoB = FakeMangaRepo(api = "B")
        val registry = PilotedRegistry(
            mapOf(
                "A" to StubSearchClient(
                    "A",
                    AppResult.Success(listOf(LegacyMangaItem("A", "en", "ra", "u", "i", null, null, emptyList()).toHomeFeedItem())),
                ),
                "B" to StubSearchClient("B", AppResult.Failure(AppError.Network.Timeout())),
            ),
        )
        val impl = SearchRepositoryImpl(
            sourcesRepository = legacySources(listOf(repoA, repoB), this),
            dispatchers = testDispatchers,
            sourceRegistry = registry,
        )

        impl.searchAllRepos("naruto").test {
            // First emission seeds every enabled repo as null (loading) so the UI shows a
            // per-source spinner section immediately.
            val seed = awaitItem()
            assertEquals(setOf("A", "B"), seed.keys)
            assertTrue(seed.values.all { it == null })
            // Then the merge emits one partial map per resolving repo; wait until both resolved
            // to a terminal (non-null) result.
            var last = awaitItem()
            while (last.size < 2 || last.values.any { it == null }) last = awaitItem()
            assertEquals(setOf("A", "B"), last.keys)
            assertTrue(last.getValue("A") is AppResult.Success)
            assertEquals(1, (last.getValue("A") as AppResult.Success).value.size)
            assertTrue(last.getValue("B") is AppResult.Failure)
            assertTrue((last.getValue("B") as AppResult.Failure).error is AppError.Network.Timeout)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
