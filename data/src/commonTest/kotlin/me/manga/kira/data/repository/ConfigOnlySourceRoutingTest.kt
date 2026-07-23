package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.sources.contracts.MangaSourceClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as LegacySourcesRepository

/**
 * THE MangaSource-decoupling invariant, end-to-end at the `:data` layer (2026-07,
 * docs/sources/MANGASOURCE_DECOUPLING_PLAN.md): a CONFIG-ONLY source — an api that exists solely as
 * a validated `engine:"generic"` stanza, with **no compiled `BaseMangaRepository`, no `MangaSource`
 * enum entry, no Kotlin wiring** — is a first-class citizen: it appears as a Home tab (with the
 * stanza's displayName), can become the active source, serves the Home feed through the generic
 * client, and is searched by the search-all fan-out.
 *
 * The legacy [LegacySourcesRepository] is constructed with `repos = emptySet()` so any surviving
 * legacy-object dependency in the Home/Search path would fail these tests exactly the way it failed
 * real config-only sources before the decoupling (tab silently dropped, source unselectable and
 * unsearchable).
 */
class ConfigOnlySourceRoutingTest {
    private val api = "ConfigOnly"

    private fun legacySources(dao: StatefulSourcesDao) =
        LegacySourcesRepository(
            sourcesDao = dao,
            repos = emptySet(), // the point: NO compiled repo exists for this api
            prefs = SharedPrefsHelper(MapSettings()),
            applicationScope = CoroutineScope(Dispatchers.Unconfined),
        )

    private fun registry() =
        PilotRegistry(
            piloted = setOf(api),
            descriptors = mapOf(api to fakeDescriptor(api).copy(displayName = "Config Only Manga")),
            client = { StubGenericClient(it) },
        )

    private fun homeRepo(dao: StatefulSourcesDao) =
        HomeFeedRepositoryImpl(
            sourcesRepository = legacySources(dao),
            dispatchers = testDispatchers,
            sourceRegistry = registry(),
        )

    private fun enabledRow() = sourceRow(api, baseUrl = "https://configonly.test", isEnabled = true)

    @Test
    fun config_only_source_appears_as_a_home_tab_with_the_stanza_display_name() =
        runTest {
            val tabs = homeRepo(StatefulSourcesDao(listOf(enabledRow()))).observeSourceTabs().first()

            val tab = tabs.singleOrNull() ?: fail("config-only source must appear as a tab, got $tabs")
            assertEquals(api, tab.api)
            assertEquals("Config Only Manga", tab.displayName)
        }

    @Test
    fun config_only_source_becomes_active_and_serves_home_through_the_generic_client() =
        runTest {
            val dao = StatefulSourcesDao(listOf(enabledRow()))
            val legacy = legacySources(dao)
            val repo =
                HomeFeedRepositoryImpl(
                    sourcesRepository = legacy,
                    dispatchers = testDispatchers,
                    sourceRegistry = registry(),
                )

            legacy.updateActiveByApi(api)

            assertEquals(api, legacy.activeApiFlow.value, "api string persists as the active identity")
            assertEquals(0, repo.observeActiveTabIndex().first())
            val feed = repo.fetchHome(reset = true)
            val items = (feed as? AppResult.Success)?.value ?: fail("expected generic home success, got $feed")
            assertEquals(listOf("GENERIC"), items.map { it.title })
        }

    @Test
    fun config_only_source_is_searched_by_the_fan_out() =
        runTest {
            val search =
                SearchRepositoryImpl(
                    sourcesRepository = legacySources(StatefulSourcesDao(listOf(enabledRow()))),
                    dispatchers = testDispatchers,
                    sourceRegistry = registry(),
                )

            val terminal =
                search
                    .searchAllRepos("query")
                    .first { snapshot -> snapshot[api] != null }

            val result = terminal[api]
            assertTrue(result is AppResult.Success, "config-only source must be searched, got $result")
            assertEquals(listOf("GENERIC"), result.value.map { it.title })
        }

    /** Minimal generic client: home/search return one marker item; other verbs are not exercised. */
    private class StubGenericClient(
        override val api: String,
    ) : MangaSourceClient {
        private fun item() =
            HomeFeedItem(
                api = api,
                language = "(AR)",
                title = "GENERIC",
                url = "https://configonly.test/m/1",
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
        ): AppResult<List<HomeFeedItem>> = AppResult.Success(listOf(item()))

        override suspend fun details(manga: Manga): AppResult<MangaDetails> = error("not exercised")

        override fun pages(
            manga: Manga,
            chapter: Chapter,
        ): Flow<AppResult<List<Page>>> = flowOf(AppResult.Success(emptyList()))
    }
}
