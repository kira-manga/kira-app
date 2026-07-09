package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.MangaInfo
import me.manga.kira.domain.model.MangaItem
import me.manga.kira.domain.model.PopularManga
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeChapterRef
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.presentation.features.repo_settings.domain.SourceState
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources_repositry.BaseMangaRepository
import me.manga.kira.sources_repositry.pt.manhastro.ManhastroDadosStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.manga.kira.core.states.State as LegacyState

/**
 * Stage-1 Home/Search flip verification: proves Home + Search route through the registry/generic path
 * ONLY for the piloted source (Azora), every other source stays on the legacy [SourcesRepository]
 * path, the rich Home data (recentChapters) is preserved, and a registry failure is surfaced through
 * `:data`. The real generic→legacy fallback is exercised end-to-end at the composeApp integration
 * level; here the registry is faked so the `:data` routing decision is asserted in isolation.
 */
class AzoraHomeSearchRoutingTest {
    private val testDispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
        }

    // A generic Home item the stub registry returns, distinct from the legacy sentinel, carrying a
    // recent chapter so the test can assert the rich Home data survives the generic path.
    private val genericHomeItem =
        HomeFeedItem(
            api = "Azora",
            language = "(AR)",
            title = "GENERIC",
            url = "g-url",
            coverUrl = "",
            rating = 7,
            genres = listOf("Action"),
            recentChapters = listOf(HomeChapterRef(number = "Chapter 9", url = "g-chapter-url", isDownloaded = false)),
        )

    private fun CoroutineScope.homeRepo(
        active: FakeMangaRepo,
        registry: SourceRegistry,
    ) = HomeFeedRepositoryImpl(
        sourcesRepository = legacySources(listOf(active), this),
        dadosStore = ManhastroDadosStore(),
        dispatchers = testDispatchers,
        sourceRegistry = registry,
    )

    private fun CoroutineScope.searchRepo(
        active: FakeMangaRepo,
        registry: SourceRegistry,
    ) = SearchRepositoryImpl(
        sourcesRepository = legacySources(listOf(active), this),
        dispatchers = testDispatchers,
        sourceRegistry = registry,
    )

    @Test
    fun azora_home_routes_through_registry_and_preserves_recent_chapters() =
        runTest {
            val registry = FakeRegistry(piloted = setOf("Azora")) { StubClient(it, home = { AppResult.Success(listOf(genericHomeItem)) }) }
            val repo = homeRepo(FakeMangaRepo("Azora", home = legacyHome("LEGACY")), registry)

            val result = repo.fetchHome(reset = true).valueOrFail()
            assertEquals(listOf("GENERIC"), result.map { it.title }) // generic used, not legacy "LEGACY"
            assertEquals(listOf("Chapter 9"), result[0].recentChapters.map { it.number }) // rich Home data preserved
            assertEquals(listOf("Azora"), registry.getCalls)
        }

    @Test
    fun azora_search_routes_through_registry() =
        runTest {
            val registry =
                FakeRegistry(piloted = setOf("Azora")) {
                    StubClient(it, search = { AppResult.Success(listOf(genericHomeItem)) })
                }
            val repo = searchRepo(FakeMangaRepo("Azora", search = legacySearch("LEGACY")), registry)

            val result = repo.searchSource("one piece", me.manga.kira.domain.model.home.SearchMode.NORMAL, null, emptyList()).valueOrFail()
            assertEquals(listOf("GENERIC"), result.map { it.title })
            assertEquals(listOf("Azora"), registry.getCalls)
        }

    @Test
    fun non_azora_home_uses_legacy_not_registry() =
        runTest {
            val registry = FakeRegistry(piloted = setOf("Azora")) { error("registry must not serve a non-pilot") }
            val repo = homeRepo(FakeMangaRepo("Other", home = legacyHome("LEGACY")), registry)

            val result = repo.fetchHome(reset = true).valueOrFail()
            assertEquals(listOf("LEGACY"), result.map { it.title }) // legacy path
            assertEquals(emptyList(), registry.getCalls) // registry NOT consulted
        }

    @Test
    fun non_azora_search_uses_legacy_not_registry() =
        runTest {
            val registry = FakeRegistry(piloted = setOf("Azora")) { error("registry must not serve a non-pilot") }
            val repo = searchRepo(FakeMangaRepo("Other", search = legacySearch("LEGACY")), registry)

            val result = repo.searchSource("q", me.manga.kira.domain.model.home.SearchMode.NORMAL, null, emptyList()).valueOrFail()
            assertEquals(listOf("LEGACY"), result.map { it.title })
            assertEquals(emptyList(), registry.getCalls)
        }

    @Test
    fun azora_home_registry_failure_is_surfaced_through_data_without_legacy() =
        runTest {
            // config-backed = generic-only: a generic failure is surfaced; the legacy scraper is NOT executed.
            val registry =
                FakeRegistry(piloted = setOf("Azora")) {
                    StubClient(it, home = { AppResult.Failure(AppError.Network.Http(403)) })
                }
            val legacyAzora = FakeMangaRepo("Azora", home = legacyHome("LEGACY"))
            val repo = homeRepo(legacyAzora, registry)

            val result = repo.fetchHome(reset = true)
            assertTrue(result is AppResult.Failure && (result.error as? AppError.Network.Http)?.statusCode == 403)
            assertEquals(0, legacyAzora.homeCalls) // legacy Home scraper never invoked for a config-backed source
        }

    @Test
    fun azora_search_registry_failure_is_surfaced_through_data_without_legacy() =
        runTest {
            val registry =
                FakeRegistry(piloted = setOf("Azora")) {
                    StubClient(it, search = { AppResult.Failure(AppError.Network.Http(403)) })
                }
            val legacyAzora = FakeMangaRepo("Azora", search = legacySearch("LEGACY"))
            val repo = searchRepo(legacyAzora, registry)

            val result = repo.searchSource("q", me.manga.kira.domain.model.home.SearchMode.NORMAL, null, emptyList())
            assertTrue(result is AppResult.Failure && (result.error as? AppError.Network.Http)?.statusCode == 403)
            assertEquals(0, legacyAzora.searchCalls) // legacy Search scraper never invoked for a config-backed source
        }

    @Test
    fun thrown_active_source_resolution_is_classified_not_raw() =
        runTest {
            // Regression guard: a Room/IO error resolving the active source must surface as a classified
            // AppResult.Failure (pre-flip behavior), not escape as a raw throwable.
            val registry = FakeRegistry(piloted = setOf("Azora")) { error("unused") }
            val repo =
                HomeFeedRepositoryImpl(
                    sourcesRepository =
                        SourcesRepository(
                            sourcesDao = ThrowingSourcesDao,
                            repos = emptySet(),
                            prefs = SharedPrefsHelper(MapSettings()),
                            applicationScope = this,
                        ),
                    dadosStore = ManhastroDadosStore(),
                    dispatchers = testDispatchers,
                    sourceRegistry = registry,
                )
            val result = repo.fetchHome(reset = true)
            assertTrue(result is AppResult.Failure) // classified, not thrown
            assertEquals(emptyList(), registry.getCalls) // never reached the routing decision
        }

    @Test
    fun zero_enabled_sources_surfaces_a_failure_instead_of_a_silent_empty_home() =
        runTest {
            // 2026-07 source-lifecycle hardening: with NO enabled source rows at all (bundled config
            // rejected wholesale, or the user disabled every source), the active flow resolves to the
            // EmptyMangaRepository null-object whose empty-Success used to render a silent blank Home.
            // The resolution must now surface a typed Failure so Home shows its error pane.
            val registry = FakeRegistry(piloted = emptySet()) { error("no sources to serve") }
            val repo =
                HomeFeedRepositoryImpl(
                    sourcesRepository = legacySources(emptyList(), this),
                    dadosStore = ManhastroDadosStore(),
                    dispatchers = testDispatchers,
                    sourceRegistry = registry,
                )

            val result = repo.fetchHome(reset = true)

            assertTrue(result is AppResult.Failure, "expected a typed Failure, got $result")
            assertEquals(emptyList(), registry.getCalls) // nothing was fetched
        }

    // --- Phase 5/6: legacy isolation from the active flow ----------------------------------------

    @Test
    fun home_tabs_exclude_enabled_legacy_sources() =
        runTest {
            // Both sources are enabled in the DB, but only the piloted one is config-backed → the Home tab
            // strip surfaces ONLY the piloted source.
            val registry = FakeRegistry(piloted = setOf("Azora")) { StubClient(it) }
            val repo =
                HomeFeedRepositoryImpl(
                    sourcesRepository = legacySources(listOf(FakeMangaRepo("Azora"), FakeMangaRepo("Other")), this),
                    dadosStore = ManhastroDadosStore(),
                    dispatchers = testDispatchers,
                    sourceRegistry = registry,
                )

            val tabs = repo.observeSourceTabs().first()

            assertEquals(listOf("Azora"), tabs.map { it.api }) // legacy "Other" hidden though enabled
        }

    @Test
    fun home_active_source_falls_back_to_piloted_when_persisted_active_is_legacy() =
        runTest {
            // "Other" (legacy) is at index 0 — the default active index — and "Azora" (piloted) at index 1.
            // The active-source guard must resolve to the piloted source, not fetch the legacy one.
            val registry = FakeRegistry(piloted = setOf("Azora")) { StubClient(it, home = { AppResult.Success(listOf(genericHomeItem)) }) }
            val repo =
                HomeFeedRepositoryImpl(
                    sourcesRepository =
                        legacySources(
                            listOf(
                                FakeMangaRepo("Other", home = legacyHome("LEGACY")),
                                FakeMangaRepo("Azora", home = legacyHome("LEGACY_AZORA")),
                            ),
                            this,
                        ),
                    dadosStore = ManhastroDadosStore(),
                    dispatchers = testDispatchers,
                    sourceRegistry = registry,
                )

            val result = repo.fetchHome(reset = true).valueOrFail()

            assertEquals(listOf("GENERIC"), result.map { it.title }) // resolved to piloted Azora's generic feed
            assertEquals(listOf("Azora"), registry.getCalls) // legacy "Other" never fetched
        }

    @Test
    fun tab_taps_and_highlight_use_the_filtered_space_even_when_legacy_rows_shift_the_enabled_list() =
        runTest {
            // 2026-07 audit: "Other" (legacy, enabled) occupies legacy-space index 0 while the
            // filtered tab strip holds only "Azora" — the two index spaces diverge. Tapping tab 0
            // must activate the TAPPED tab's source (api-keyed), and the reported active index must
            // be that source's position in the FILTERED space. Pre-fix, the tap persisted legacy
            // index 0 ("Other") and highlight/fetch disagreed with the tap.
            val registry = FakeRegistry(piloted = setOf("Azora")) { StubClient(it) }
            val sources = legacySources(listOf(FakeMangaRepo("Other"), FakeMangaRepo("Azora")), this)
            val repo =
                HomeFeedRepositoryImpl(
                    sourcesRepository = sources,
                    dadosStore = ManhastroDadosStore(),
                    dispatchers = testDispatchers,
                    sourceRegistry = registry,
                )

            repo.selectTab(0)

            assertEquals("Azora", sources.activeRepo.first().API) // the tapped (filtered) tab's source
            assertEquals(0, repo.observeActiveTabIndex().first()) // highlight agrees, in filtered space
        }

    @Test
    fun search_all_repos_only_fans_out_to_piloted_sources() =
        runTest {
            // Both enabled; the legacy "Other" must never be searched (not even appear in the result map).
            val registry =
                FakeRegistry(piloted = setOf("Azora")) {
                    StubClient(it, search = { AppResult.Success(listOf(genericHomeItem)) })
                }
            val repo =
                SearchRepositoryImpl(
                    sourcesRepository =
                        legacySources(
                            listOf(
                                FakeMangaRepo("Azora"),
                                FakeMangaRepo("Other", search = legacySearch("LEGACY_OTHER")),
                            ),
                            this,
                        ),
                    dispatchers = testDispatchers,
                    sourceRegistry = registry,
                )

            val emissions = mutableListOf<Map<String, AppResult<List<HomeFeedItem>>?>>()
            repo.searchAllRepos("q").collect { emissions += it }
            val finalMap = emissions.last()

            assertEquals(setOf("Azora"), finalMap.keys) // legacy "Other" never fanned out
            assertEquals(listOf("GENERIC"), (finalMap.getValue("Azora") as AppResult.Success).value.map { it.title })
        }

    // --- 2026-07 mobile hardening: accumulator concurrency (accumulatorMutex) ---------------------

    @Test
    fun staleFetchMore_writeBack_isDiscarded_after_reset() =
        runTest {
            // A fetch-more parked on the network while a refresh resets the accumulator must NOT
            // write its stale merge back (its captured generation is outdated) — the probe page
            // sees only the fresh feed, with no pre-refresh rows and no duplicates.
            val gate = CompletableDeferred<Unit>()
            val client =
                GatedClient("Azora") { page ->
                    when (page) {
                        1 -> AppResult.Success(listOf(feedItem("fresh-1")))
                        2 -> {
                            gate.await()
                            AppResult.Success(listOf(feedItem("stale-2")))
                        }
                        else -> AppResult.Success(emptyList())
                    }
                }
            val repo = homeRepo(FakeMangaRepo("Azora"), FakeRegistry(piloted = setOf("Azora")) { client })
            repo.fetchHome(reset = true)
            // UNDISPATCHED: run inline up to the gate so the fetch is genuinely in flight (generation
            // captured) BEFORE the reset below — a plain launch would not start until the join.
            val zombie = launch(start = CoroutineStart.UNDISPATCHED) { repo.fetchMore(2) }
            repo.fetchHome(reset = true) // clear + bump + fresh write-back
            gate.complete(Unit)
            zombie.join()

            val probe = (repo.fetchMore(99) as AppResult.Success).value
            assertEquals(listOf("fresh-1"), probe.map { it.title })
        }

    @Test
    fun overlapping_fetchMores_underOneGeneration_bothPagesSurvive() =
        runTest {
            // Two fetch-mores under the SAME generation completing out of order: the later commit
            // must merge against the CURRENT accumulator (not its pre-fetch capture), or the first
            // commit's page is silently lost (the audit's lost-write-back). Order forced by gates:
            // page 3 commits first, then page 2.
            val gate2 = CompletableDeferred<Unit>()
            val gate3 = CompletableDeferred<Unit>()
            val client =
                GatedClient("Azora") { page ->
                    when (page) {
                        1 -> AppResult.Success(listOf(feedItem("a")))
                        2 -> {
                            gate2.await()
                            AppResult.Success(listOf(feedItem("b")))
                        }
                        3 -> {
                            gate3.await()
                            AppResult.Success(listOf(feedItem("c")))
                        }
                        else -> AppResult.Success(emptyList())
                    }
                }
            val repo = homeRepo(FakeMangaRepo("Azora"), FakeRegistry(piloted = setOf("Azora")) { client })
            repo.fetchHome(reset = true)
            val fetch2 = launch(start = CoroutineStart.UNDISPATCHED) { repo.fetchMore(2) }
            val fetch3 = launch(start = CoroutineStart.UNDISPATCHED) { repo.fetchMore(3) }
            gate3.complete(Unit)
            fetch3.join() // commits [a, c]
            gate2.complete(Unit)
            fetch2.join() // must see c and produce [a, c, b] — not [a, b]

            val probe = (repo.fetchMore(99) as AppResult.Success).value
            assertEquals(listOf("a", "c", "b"), probe.map { it.title })
        }

    @Test
    fun concurrent_fetches_and_resets_neverMixDuplicateOrLeakAcrossRounds() =
        runTest {
            // Real-parallelism stress for the TOCTOU / lost-increment / visibility class the
            // deterministic tests above can't interleave single-threaded: each round runs a reset,
            // three fetch-mores and a concurrent second reset on Dispatchers.Default, with
            // round-scoped titles. Invariants after each round: exactly one fresh row, zero items
            // from any previous round, zero duplicates.
            var round = 0 // written before the round's launches; coroutine dispatch gives the HB edge
            val client =
                GatedClient("Azora") { page ->
                    when (page) {
                        1 -> AppResult.Success(listOf(feedItem("r$round-fresh")))
                        99 -> AppResult.Success(emptyList())
                        else -> AppResult.Success(listOf(feedItem("r$round-p$page")))
                    }
                }
            val repo = homeRepo(FakeMangaRepo("Azora"), FakeRegistry(piloted = setOf("Azora")) { client })
            withContext(Dispatchers.Default) {
                repeat(100) { r ->
                    round = r
                    repo.fetchHome(reset = true)
                    val fetches = (2..4).map { p -> launch { repo.fetchMore(p) } }
                    val racingReset = launch { repo.fetchHome(reset = true) }
                    fetches.forEach { it.join() }
                    racingReset.join()

                    val probe = (repo.fetchMore(99) as AppResult.Success).value
                    val titles = probe.map { it.title }
                    assertEquals(titles.distinct(), titles, "round $r: duplicated rows: $titles")
                    assertTrue(
                        titles.all { it.startsWith("r$r-") },
                        "round $r: stale rows from a previous round leaked: $titles",
                    )
                    val freshCount = titles.count { it == "r$r-fresh" }
                    assertEquals(1, freshCount, "round $r: fresh row missing/duplicated: $titles")
                }
            }
        }

    // --- fakes -----------------------------------------------------------------------------------

    private fun <T> AppResult<T>.valueOrFail(): T = (this as AppResult.Success).value

    private fun legacyHome(title: String) = LegacyState.Success(mutableListOf(legacyItem(title)))

    private fun legacySearch(title: String) = LegacyState.Success(listOf(legacyItem(title)))

    private fun legacyItem(title: String) = MangaItem("Azora", "(AR)", title, "l-url", "", null, null, emptyList())

    private fun CoroutineScope.legacySources(
        repos: List<BaseMangaRepository>,
        scope: CoroutineScope,
    ) = SourcesRepository(
        sourcesDao = SeededSourcesDao(repos),
        repos = repos.toSet(),
        prefs = SharedPrefsHelper(MapSettings()),
        applicationScope = scope,
    )

    private class FakeRegistry(
        private val piloted: Set<String>,
        private val client: (String) -> MangaSourceClient?,
    ) : SourceRegistry {
        val getCalls = mutableListOf<String>()

        override fun get(api: String): MangaSourceClient? {
            getCalls += api
            return client(api)
        }

        override fun availableApis(): List<String> = piloted.toList()

        override fun isConfigBacked(api: String): Boolean = api in piloted
    }

    private class StubClient(
        override val api: String,
        private val home: () -> AppResult<List<HomeFeedItem>> = { AppResult.Success(emptyList()) },
        private val search: () -> AppResult<List<HomeFeedItem>> = { AppResult.Success(emptyList()) },
    ) : MangaSourceClient {
        override suspend fun home(page: Int) = home()

        override suspend fun featured(page: Int) = AppResult.Success(emptyList<FeaturedManga>())

        override suspend fun search(
            query: String,
            page: Int,
        ) = search()

        override suspend fun details(manga: Manga): AppResult<MangaDetails> = error("not used")

        override fun pages(
            manga: Manga,
            chapter: Chapter,
        ): Flow<AppResult<List<Page>>> = error("not used")
    }

    /** [StubClient] twin whose home() SUSPENDS via the page-keyed lambda — lets a test park a fetch mid-flight. */
    private class GatedClient(
        override val api: String,
        private val homeByPage: suspend (Int) -> AppResult<List<HomeFeedItem>>,
    ) : MangaSourceClient {
        override suspend fun home(page: Int) = homeByPage(page)

        override suspend fun featured(page: Int) = AppResult.Success(emptyList<FeaturedManga>())

        override suspend fun search(
            query: String,
            page: Int,
        ) = AppResult.Success(emptyList<HomeFeedItem>())

        override suspend fun details(manga: Manga): AppResult<MangaDetails> = error("not used")

        override fun pages(
            manga: Manga,
            chapter: Chapter,
        ): Flow<AppResult<List<Page>>> = error("not used")
    }

    /** Minimal distinct-by-url feed row for the accumulator-concurrency tests. */
    private fun feedItem(title: String) =
        HomeFeedItem(
            api = "Azora",
            language = "(AR)",
            title = title,
            url = "u/$title",
            coverUrl = "",
            rating = 7,
            genres = emptyList(),
            recentChapters = emptyList(),
        )

    private class FakeMangaRepo(
        override val API: String,
        private val home: LegacyState<MutableList<MangaItem>> = LegacyState.Success(mutableListOf()),
        private val search: LegacyState<List<MangaItem>> = LegacyState.Success(emptyList()),
    ) : BaseMangaRepository() {
        // Phase: legacy-execution guards — record whether the legacy scraper verbs were invoked, so a
        // config-backed source's failure path can be asserted to NEVER touch the legacy repo.
        var homeCalls = 0
        var searchCalls = 0
        override val BASE_URL: String = "https://$API.test/"
        override val URL_VERSION: Int = 1
        override var baseUrl: String = BASE_URL
        override var imgBaseUrl: String = BASE_URL
        override var imgUrlVersion: Int = 0
        override val LANGUAGE: String = "(AR)"
        override val ICON: Int = 0
        override val PRIORITY: Int = 0
        override val blackListGenres: Set<String> = emptySet()
        override val sortTypes: Set<String> = emptySet()
        override val allGenres: Set<String> = emptySet()
        override val defaultHeaders: Map<String, String> = emptyMap()

        override fun fetchMangaHomeF(query: String): Flow<LegacyState<MutableList<MangaItem>>> {
            homeCalls++
            return flowOf(home)
        }

        override suspend fun fetchSearchDataF(searchType: SearchType): Flow<LegacyState<List<MangaItem>>> {
            searchCalls++
            return flowOf(search)
        }

        override suspend fun fetchPopularManga(baseUrl: String): Flow<LegacyState<List<PopularManga>>> =
            flowOf(LegacyState.Success(emptyList()))

        override fun fetchMoreManga(
            page: Int,
            currentItems: List<MangaItem>?,
        ): Flow<LegacyState<List<MangaItem>>> = flowOf(LegacyState.Success(emptyList()))

        override suspend fun fetchMangaChaptersF(query: String): Flow<LegacyState<MangaInfo>> = flowOf(LegacyState.Loading)

        override fun fetchChapterDataF(url: String): Flow<LegacyState<List<String>>> = flowOf(LegacyState.Success(emptyList()))

        override suspend fun refreshHeaders(newHeaders: Map<String, String>) = Unit

        override suspend fun getBaseUrl(): String = baseUrl
    }

    /** A dao whose source-list flow throws when collected — simulates a Room/IO error. */
    private object ThrowingSourcesDao : SourcesDao {
        override fun getAllSources(): Flow<List<SourcesEntity>> = kotlinx.coroutines.flow.flow { throw RuntimeException("db read error") }

        override suspend fun insert(source: SourcesEntity): Long = 1L

        override suspend fun setEnabledByName(
            name: String,
            enabled: Boolean,
        ): Int = 0

        override suspend fun getBaseUrlFor(name: String): String? = null

        override fun getSiteStateByName(name: String): Flow<SourceState?> = flowOf(null)

        override suspend fun getSiteStateByNameSync(name: String): SourceState? = null

        override suspend fun updateBaseUrlAndVersionByName(
            name: String,
            baseUrl: String,
            version: Int,
        ): Int = 0

        override suspend fun updateImageBaseUrlAndVersionByName(
            apiName: String,
            newImageBaseUrl: String,
            newImageVersion: Int,
        ): Int = 0

        override suspend fun updateSiteStateByName(
            name: String,
            siteState: SourceState,
        ): Int = 0

        override suspend fun deleteSourceByName(name: String): Int = 0
    }

    private class SeededSourcesDao(
        repos: List<BaseMangaRepository>,
    ) : SourcesDao {
        private val rows =
            repos.mapIndexed { i, repo ->
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

        override suspend fun setEnabledByName(
            name: String,
            enabled: Boolean,
        ): Int = 0

        override suspend fun getBaseUrlFor(name: String): String? = rows.firstOrNull { it.name == name }?.baseUrl

        override fun getSiteStateByName(name: String): Flow<SourceState?> = flowOf(rows.firstOrNull { it.name == name }?.siteState)

        override suspend fun getSiteStateByNameSync(name: String): SourceState? = rows.firstOrNull { it.name == name }?.siteState

        override suspend fun updateBaseUrlAndVersionByName(
            name: String,
            baseUrl: String,
            version: Int,
        ): Int = 0

        override suspend fun updateImageBaseUrlAndVersionByName(
            apiName: String,
            newImageBaseUrl: String,
            newImageVersion: Int,
        ): Int = 0

        override suspend fun updateSiteStateByName(
            name: String,
            siteState: SourceState,
        ): Int = 0

        override suspend fun deleteSourceByName(name: String): Int = 0
    }
}
