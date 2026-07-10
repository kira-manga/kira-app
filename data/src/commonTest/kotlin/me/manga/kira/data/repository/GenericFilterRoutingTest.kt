package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterOption
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources_repositry.BaseMangaRepository
import me.manga.kira.sources_repositry.pt.manhastro.ManhastroDadosStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import me.manga.kira.core.states.State as LegacyState
import me.manga.kira.domain.model.MangaItem as LegacyMangaItem
import me.manga.kira.domain.model.PopularManga as LegacyPopularManga
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as LegacySourcesRepository

/**
 * The config-driven-filters ROUTING invariants at the `:data` layer (2026-07,
 * docs/sources/CONFIG_DRIVEN_FILTERS_PLAN.md §5):
 *  - a config-backed source's filtered search ALWAYS runs the generic client — never the legacy
 *    scraper, even when a compiled repo still ships beside the config;
 *  - a legacy source's selections translate onto the legacy `SearchType` with pre-generic
 *    semantics (sort > genres precedence, CSV genres);
 *  - filter descriptors come from the validated stanza for config-backed sources and from the
 *    `sortTypes`/`allGenres` adapter for legacy ones — one render model for both worlds.
 */
class GenericFilterRoutingTest {
    private val api = "FilterRouted"

    private fun legacySources(
        dao: StatefulSourcesDao,
        repos: Set<BaseMangaRepository> = emptySet(),
    ) = LegacySourcesRepository(
        sourcesDao = dao,
        repos = repos,
        prefs = SharedPrefsHelper(MapSettings()),
        applicationScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun enabledRow() = sourceRow(api, baseUrl = "https://filter.test", isEnabled = true)

    private fun selections(vararg pairs: Pair<String, List<String>>) = FilterSelections(mapOf(*pairs))

    @Test
    fun config_backed_filtered_search_routes_to_the_generic_client_with_the_selections() =
        runTest {
            val client = RecordingGenericClient(api)
            val search =
                SearchRepositoryImpl(
                    sourcesRepository = legacySources(StatefulSourcesDao(listOf(enabledRow()))),
                    dispatchers = testDispatchers,
                    sourceRegistry = PilotRegistry(piloted = setOf(api), client = { client }),
                )

            val picked = selections("sort" to listOf("views"), "genres" to listOf("action", "drama"))
            val result = search.searchSource("query", picked)

            val items = (result as? AppResult.Success)?.value ?: fail("expected generic success, got $result")
            assertEquals(listOf("GENERIC"), items.map { it.title })
            assertEquals(picked, client.lastFilters)
            assertEquals("query", client.lastQuery)
        }

    @Test
    fun config_backed_filtered_search_never_touches_a_compiled_legacy_repo() =
        runTest {
            // The pre-filters behavior this test pins the REMOVAL of: a sort/genre search on a
            // pilot used to drop to the compiled legacy repo. Now the stanza is the only filter
            // authority — the legacy repo must stay cold.
            val legacyRepo = FakeLegacyRepo(api, sortTypes = setOf("Latest"), allGenres = setOf("Action"))
            val client = RecordingGenericClient(api)
            val dao = StatefulSourcesDao(listOf(enabledRow()))
            val legacy = legacySources(dao, setOf(legacyRepo))
            // The fake dao's insert REPLACES rows (the real Room insert is IGNORE), so the legacy
            // repository's eager disabled-seed clobbers the enabled row — restore it.
            dao.setEnabledByName(api, true)
            val search =
                SearchRepositoryImpl(
                    sourcesRepository = legacy,
                    dispatchers = testDispatchers,
                    sourceRegistry = PilotRegistry(piloted = setOf(api), client = { client }),
                )

            val result = search.searchSource("q", selections("sort" to listOf("Latest")))

            assertTrue(result is AppResult.Success, "expected generic success, got $result")
            assertNull(legacyRepo.lastSearchType, "legacy fetchSearchDataF must never run for a config-backed source")
            assertEquals(selections("sort" to listOf("Latest")), client.lastFilters)
        }

    @Test
    fun legacy_source_selections_translate_onto_the_legacy_search_type() =
        runTest {
            val legacyRepo = FakeLegacyRepo(api, sortTypes = setOf("Latest"), allGenres = setOf("Action", "Drama"))
            val dao = StatefulSourcesDao(listOf(enabledRow()))
            val legacy = legacySources(dao, setOf(legacyRepo))
            dao.setEnabledByName(api, true) // fake-dao insert REPLACES; undo the disabled seed
            legacy.updateActiveByApi(api)
            val search =
                SearchRepositoryImpl(
                    sourcesRepository = legacy,
                    dispatchers = testDispatchers,
                    sourceRegistry = PilotRegistry(piloted = emptySet()), // NOT config-backed
                )

            search.searchSource("q", selections("sort" to listOf("Latest"), "genres" to listOf("Action", "Drama")))
            assertEquals(SearchType.SORT(query = "q", sortType = "Latest", genres = "Action,Drama"), legacyRepo.lastSearchType)

            search.searchSource("q", selections("genres" to listOf("Action")))
            assertEquals(SearchType.GENRES(query = "q", genres = "Action"), legacyRepo.lastSearchType)

            search.searchSource("plain", FilterSelections())
            assertEquals(SearchType.Normal("plain"), legacyRepo.lastSearchType)
        }

    @Test
    fun legacy_source_filters_adapt_into_ordered_generic_descriptors() =
        runTest {
            val legacyRepo = FakeLegacyRepo(api, sortTypes = setOf("Latest", "Views"), allGenres = setOf("Action"))
            val dao = StatefulSourcesDao(listOf(enabledRow()))
            val legacy = legacySources(dao, setOf(legacyRepo))
            dao.setEnabledByName(api, true) // fake-dao insert REPLACES; undo the disabled seed
            legacy.updateActiveByApi(api)
            val home =
                HomeFeedRepositoryImpl(
                    sourcesRepository = legacy,
                    dadosStore = ManhastroDadosStore(),
                    dispatchers = testDispatchers,
                    sourceRegistry = PilotRegistry(piloted = emptySet()),
                )

            val filters = (home.loadSourceFilters() as AppResult.Success).value

            assertEquals(listOf("genres", "sort"), filters.map { it.id }, "pre-generic sheet order: genres, then sort")
            assertTrue(filters.all { it.type == FilterControlType.SELECT })
            assertEquals(listOf(FilterOption("Action", "Action")), filters[0].options)
            assertEquals(listOf("Latest", "Views"), filters[1].options.map { it.value })
        }

    @Test
    fun config_backed_source_filters_come_from_the_descriptor_and_absence_means_plain_search_only() =
        runTest {
            val declared =
                listOf(
                    SourceFilter(
                        id = "genres",
                        label = "Genres",
                        type = FilterControlType.MULTISELECT,
                        options = listOf(FilterOption("action", "Action")),
                    ),
                )

            fun home(descriptors: Map<String, me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor>) =
                HomeFeedRepositoryImpl(
                    sourcesRepository = legacySources(StatefulSourcesDao(listOf(enabledRow()))),
                    dadosStore = ManhastroDadosStore(),
                    dispatchers = testDispatchers,
                    sourceRegistry =
                        PilotRegistry(
                            piloted = setOf(api),
                            descriptors = descriptors,
                            client = { RecordingGenericClient(it) },
                        ),
                )

            val withFilters = home(mapOf(api to fakeDescriptor(api).copy(filters = declared)))
            assertEquals(declared, (withFilters.loadSourceFilters() as AppResult.Success).value)

            val withoutFilters = home(mapOf(api to fakeDescriptor(api)))
            assertEquals(emptyList(), (withoutFilters.loadSourceFilters() as AppResult.Success).value)
        }

    /** Generic client recording the search call; home/featured/details are not exercised. */
    private class RecordingGenericClient(
        override val api: String,
    ) : MangaSourceClient {
        var lastQuery: String? = null
        var lastFilters: FilterSelections? = null

        private fun item() =
            HomeFeedItem(
                api = api,
                language = "(AR)",
                title = "GENERIC",
                url = "https://filter.test/m/1",
                coverUrl = "",
                rating = null,
                genres = emptyList(),
                recentChapters = emptyList(),
            )

        override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> = AppResult.Success(listOf(item()))

        override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> = AppResult.Success(emptyList())

        override suspend fun search(
            query: String,
            page: Int,
            filters: FilterSelections,
        ): AppResult<List<HomeFeedItem>> {
            lastQuery = query
            lastFilters = filters
            return AppResult.Success(listOf(item()))
        }

        override suspend fun details(manga: Manga): AppResult<MangaDetails> = error("not exercised")

        override fun pages(
            manga: Manga,
            chapter: Chapter,
        ): Flow<AppResult<List<Page>>> = flowOf(AppResult.Success(emptyList()))
    }

    /** Minimal legacy repo fake capturing the [SearchType] the routing hands it. */
    private class FakeLegacyRepo(
        private val api: String,
        override val sortTypes: Set<String> = emptySet(),
        override val allGenres: Set<String> = emptySet(),
    ) : BaseMangaRepository() {
        var lastSearchType: SearchType? = null

        override val BASE_URL: String = "https://$api/"
        override val URL_VERSION: Int = 0
        override var baseUrl: String = "https://$api/"
        override var imgBaseUrl: String = "https://$api/img/"
        override var imgUrlVersion: Int = 0
        override val API: String = api
        override val LANGUAGE: String = "(AR)"
        override val ICON: Int = 0
        override val PRIORITY: Int = 0
        override val blackListGenres: Set<String> = emptySet()
        override val defaultHeaders: Map<String, String> = emptyMap()

        override suspend fun fetchSearchDataF(searchType: SearchType): Flow<LegacyState<List<LegacyMangaItem>>> {
            lastSearchType = searchType
            return flowOf(LegacyState.Success(emptyList()))
        }

        override fun fetchMangaHomeF(query: String): Flow<LegacyState<MutableList<LegacyMangaItem>>> =
            flow { emit(LegacyState.Success(mutableListOf())) }

        override suspend fun fetchMangaChaptersF(query: String): Flow<LegacyState<MangaInfo>> = flowOf(LegacyState.Loading)

        override fun fetchChapterDataF(url: String): Flow<LegacyState<List<String>>> = flowOf(LegacyState.Success(emptyList()))

        override fun fetchMoreManga(
            page: Int,
            currentItems: List<LegacyMangaItem>?,
        ): Flow<LegacyState<List<LegacyMangaItem>>> = flowOf(LegacyState.Success(emptyList()))

        override suspend fun fetchPopularManga(baseUrl: String): Flow<LegacyState<List<LegacyPopularManga>>> =
            flowOf(LegacyState.Success(emptyList()))

        override suspend fun refreshHeaders(newHeaders: Map<String, String>) {}

        override suspend fun getBaseUrl(): String = baseUrl

        override suspend fun initSite(): Int = 0
    }
}
