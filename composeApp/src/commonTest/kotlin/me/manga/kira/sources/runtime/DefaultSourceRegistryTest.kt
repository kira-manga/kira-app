package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.states.State
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.sources.config.RemoteSourceConfigManager
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Proves the Stage-0 registry assembly: legacy sources are wrapped + mapped to domain models, api
 * lookup works, and the (off-by-default) generic pilot routing falls back to legacy when no config
 * exists. This is the "how the registry works" demonstration, isolated from the full Koin graph.
 */
class DefaultSourceRegistryTest {

    private fun <T> AppResult<T>.valueOrFail(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> fail("expected success, got $error")
    }

    private class FakeUpdateManager(private val document: SourceConfigDocument) : SourceUpdateManager {
        private val _state = MutableStateFlow<UpdateState>(UpdateState.Active(document.revision, UpdateState.Origin.BUNDLED))
        override val state: StateFlow<UpdateState> = _state.asStateFlow()
        override fun activeDocument(): SourceConfigDocument = document
        override suspend fun refresh(): AppResult<SourceConfigDocument> = AppResult.Success(document)
    }

    /** A marker client so tests can tell a generic-routed client apart from a legacy one. */
    private class MarkerGenericClient(
        override val api: String,
        private val failing: Boolean = false,
    ) : MangaSourceClient {
        private fun <T> result(value: T): AppResult<T> =
            if (failing) AppResult.Failure(me.manga.kira.core.error.AppError.Network.Http(403)) else AppResult.Success(value)

        override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> =
            result(listOf(HomeFeedItem(api, "x", "GENERIC", "u", "", null, emptyList(), emptyList())))
        override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> = result(emptyList())
        override suspend fun search(query: String, page: Int): AppResult<List<HomeFeedItem>> = result(emptyList())
        override suspend fun details(manga: Manga): AppResult<MangaDetails> =
            result(MangaDetails(api, "x", "GENERIC", "u", "", "", "", "", "", emptyList(), emptyList()))
        override fun pages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> =
            flowOf(result(emptyList()))
    }

    private fun registry(
        apis: List<String> = listOf("fake"),
        document: SourceConfigDocument = SourceConfigDocument(schemaVersion = 1),
        genericFailing: Boolean = false,
    ) = DefaultSourceRegistry(
        legacyRepos = apis.map { FakeLegacyRepo(it) }.toSet(),
        updateManager = FakeUpdateManager(document),
        genericClientFactory = { config -> MarkerGenericClient("generic:${config.api}", failing = genericFailing) },
    )

    private fun genericDoc(api: String = "fake") = SourceConfigDocument(
        schemaVersion = 1,
        sources = listOf(SourceConfig(api = api, language = "en", baseUrl = "https://$api.test", engine = "generic")),
    )

    @Test
    fun unknown_api_returns_null() {
        assertNull(registry().get("does-not-exist"))
    }

    @Test
    fun descriptor_projects_the_active_stanza_for_any_engine() {
        val doc = SourceConfigDocument(
            schemaVersion = 1,
            sources = listOf(
                SourceConfig(api = "gen", language = "(AR)", baseUrl = "https://gen.test", engine = "generic"),
                SourceConfig(api = "leg", language = "(EN)", baseUrl = "https://leg.test", engine = "legacy"),
            ),
        )
        val reg = registry(document = doc)
        val generic = reg.descriptor("gen") ?: fail("generic stanza must have a descriptor")
        assertEquals("gen", generic.displayName) // displayName defaults to api
        assertTrue(generic.isGeneric)
        val legacy = reg.descriptor("leg") ?: fail("metadata-only legacy stanza must have a descriptor")
        assertEquals("legacy", legacy.engine)
        assertNull(reg.descriptor("nope"))
    }

    @Test
    fun generic_descriptors_lists_only_generic_stanzas() {
        val doc = SourceConfigDocument(
            schemaVersion = 1,
            sources = listOf(
                SourceConfig(api = "gen", language = "(AR)", baseUrl = "https://gen.test", engine = "generic"),
                SourceConfig(api = "leg", language = "(EN)", baseUrl = "https://leg.test", engine = "legacy"),
            ),
        )
        val reg = registry(document = doc)
        assertEquals(listOf("gen"), reg.genericDescriptors().map { it.api })
    }

    @Test
    fun legacy_source_is_wrapped_and_home_is_mapped_to_domain() = runTest {
        val client = registry().get("fake") ?: fail("expected a client")
        assertEquals("fake", client.api)

        val home = client.home(1).valueOrFail()
        assertEquals(listOf("Home One", "Home Two"), home.map { it.title })
        assertEquals("fake", home[0].api)
        assertEquals(7, home[0].rating)
        assertEquals(listOf("Action"), home[0].genres)
    }

    @Test
    fun legacy_details_and_pages_are_mapped_to_domain() = runTest {
        val client = registry().get("fake")!!
        val details = client.details(Manga("fake", "en", "One Piece", "https://fake.test/manga/op", "", null, emptyList())).valueOrFail()
        assertEquals("One Piece", details.title)
        assertEquals("Oda", details.author)
        assertEquals("9.2", details.rating)
        assertEquals(2, details.chapters.size)
        assertEquals(LocalDate(2024, 1, 15), details.chapters[0].date)

        val pages = client.pages(details.toManga(), details.chapters[0]).first().valueOrFail()
        assertEquals(2, pages.size)
        assertEquals("https://img.fake.test/1.webp", pages[0].url)
        assertEquals("https://fake.test", pages[0].headers["Referer"]) // defaultHeaders flow through
    }

    @Test
    fun config_backed_api_uses_bare_generic_client_no_fallback_wrapper() = runTest {
        // Config-backed → the bare generic client (NOT wrapped in FallbackSourceClient): generic-only.
        val client = registry(document = genericDoc()).get("fake")!!
        assertTrue(client !is FallbackSourceClient)
        assertEquals("generic:fake", client.api)
        assertEquals(listOf("GENERIC"), client.home(1).valueOrFail().map { it.title })
    }

    @Test
    fun config_backed_generic_failure_is_surfaced_not_fallen_back_to_legacy() = runTest {
        // generic fails (e.g. Cloudflare 403) → the FAILURE is surfaced as-is; the legacy adapter's
        // result ("Home One"/"Home Two") is NEVER served — legacy is not executed for a config-backed source.
        val client = registry(document = genericDoc(), genericFailing = true).get("fake")!!
        val result = client.home(1)
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun api_without_a_generic_stanza_gets_the_legacy_adapter() {
        // The active document has no generic stanza for "fake" -> legacy (fail-closed).
        val client = registry().get("fake")!!
        assertEquals("fake", client.api) // the legacy adapter, not the marker/fallback
        assertTrue(client !is FallbackSourceClient)
    }

    private fun MangaDetails.toManga() = Manga(api, language, title, url, coverUrl, null, genres)

    // --- error classification fidelity (mirrors :data mappers) ------------------------------------

    private fun registryWith(repo: FakeLegacyRepo) = DefaultSourceRegistry(
        legacyRepos = setOf(repo),
        updateManager = FakeUpdateManager(SourceConfigDocument(schemaVersion = 1)),
        genericClientFactory = { config -> MarkerGenericClient("generic:${config.api}") },
    )

    private suspend fun homeError(repo: FakeLegacyRepo): AppError {
        val result = registryWith(repo).get(repo.API)!!.home(1)
        return (result as AppResult.Failure).error
    }

    @Test
    fun error_state_with_http_code_maps_to_network_http() = runTest {
        val error = homeError(FakeLegacyRepo("e", homeError = State.Error(503, "service down")))
        assertTrue(error is AppError.Network.Http && error.statusCode == 503)
    }

    @Test
    fun error_state_code0_timeout_maps_to_timeout() = runTest {
        // code 0 is how legacy sources surface connectivity/timeout failures (raw exception message).
        val error = homeError(FakeLegacyRepo("e", homeError = State.Error(0, "Connection timed out")))
        assertTrue(error is AppError.Network.Timeout)
    }

    @Test
    fun thrown_unknown_host_maps_to_no_connectivity() = runTest {
        val error = homeError(FakeLegacyRepo("e", homeThrows = RuntimeException("Unable to resolve host \"x.test\"")))
        assertTrue(error is AppError.Network.NoConnectivity)
    }

    // --- end-to-end with the REAL pilot config + manager + validator -----------------------------

    private fun realManager(bundled: String) = RemoteSourceConfigManager(
        store = BundledSourceConfigStore(bundled),
        verifier = DenyRemoteSignatureVerifier(),
        validator = DefaultSourceConfigValidator(DefaultStrategyRegistry()),
        remote = null,
    )

    private fun realRegistry(bundled: String) = DefaultSourceRegistry(
        legacyRepos = setOf(FakeLegacyRepo("Azora"), FakeLegacyRepo("Other")),
        updateManager = realManager(bundled),
        genericClientFactory = { MarkerGenericClient("generic:${it.api}") },
    )

    @Test
    fun real_bundled_config_routes_azora_through_bare_generic_not_fallback() {
        val reg = realRegistry(CONFIG_BACKED_SOURCES_JSON)
        val azora = reg.get("Azora")!!
        assertTrue(azora !is FallbackSourceClient) // config-backed -> bare generic, NO legacy fallback wrapper
        assertEquals("generic:Azora", azora.api)
        assertTrue(reg.get("Other") !is FallbackSourceClient) // not config-backed -> plain legacy adapter
        assertEquals("Other", reg.get("Other")!!.api)
    }

    @Test
    fun malformed_bundled_config_fails_closed_to_legacy() {
        // Parse/validation failure of the bundled config must NOT route Azora to the engine.
        val reg = realRegistry("{ this is not valid json")
        val client = reg.get("Azora")!!
        assertEquals("Azora", client.api)
        assertTrue(client !is FallbackSourceClient) // generic path not taken
    }
}
