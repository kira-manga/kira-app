package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.states.State
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Sources Migration — Phase 3. Proves the DOWNLOAD routing seam ([RegistryChapterPageProvider]):
 * a config-backed (piloted) source's pages are fetched through the [SourceRegistry] / generic client
 * (NOT the legacy scraper), headers are preserved, and non-config / failed sources return null so the
 * caller keeps its legacy download path.
 */
class RegistryChapterPageProviderTest {

    private val genericPages = listOf(
        Page(url = "https://gcdn.test/p1.webp", headers = mapOf("X-Generic" to "1", "Referer" to "https://azora.test")),
        Page(url = "https://gcdn.test/p2.webp", headers = mapOf("X-Generic" to "1", "Referer" to "https://azora.test")),
    )

    private class FakeUpdateManager(private val document: SourceConfigDocument) : SourceUpdateManager {
        private val _state = MutableStateFlow<UpdateState>(UpdateState.Active(document.revision, UpdateState.Origin.BUNDLED))
        override val state: StateFlow<UpdateState> = _state.asStateFlow()
        override fun activeDocument(): SourceConfigDocument = document
        override suspend fun refresh(): AppResult<SourceConfigDocument> = AppResult.Success(document)
    }

    /** Generic client returning fixed pages (or failing), so a generic-routed result is distinguishable. */
    private class StubGenericPagesClient(
        override val api: String,
        private val pages: List<Page>,
        private val failing: Boolean = false,
    ) : MangaSourceClient {
        private fun <T> result(value: T): AppResult<T> =
            if (failing) AppResult.Failure(AppError.Network.Http(403)) else AppResult.Success(value)
        override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> = result(emptyList())
        override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> = result(emptyList())
        override suspend fun search(query: String, page: Int): AppResult<List<HomeFeedItem>> = result(emptyList())
        override suspend fun details(manga: Manga): AppResult<MangaDetails> = AppResult.Failure(AppError.Network.Http(0))
        override fun pages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> = flowOf(result(pages))
    }

    /** Legacy repo whose page method records calls (and can fail), to prove no fallback when generic wins. */
    private class CountingLegacyRepo(api: String, private val failPages: Boolean = false) : FakeLegacyRepo(api) {
        var pagesCalls = 0
        override fun fetchChapterDataF(url: String): Flow<State<List<String>>> {
            pagesCalls++
            return if (failPages) flowOf(State.Loading, State.Error(403, "down")) else super.fetchChapterDataF(url)
        }
    }

    private fun genericDoc(api: String) = SourceConfigDocument(
        schemaVersion = 1,
        sources = listOf(SourceConfig(api = api, language = "ar", baseUrl = "https://azora.test", engine = "generic")),
    )

    private fun registry(
        legacy: FakeLegacyRepo,
        document: SourceConfigDocument = genericDoc("Azora"),
        genericFailing: Boolean = false,
    ) = DefaultSourceRegistry(
        legacyRepos = setOf(legacy),
        updateManager = FakeUpdateManager(document),
        genericClientFactory = { config -> StubGenericPagesClient(config.api, genericPages, failing = genericFailing) },
    )

    @Test
    fun piloted_download_uses_generic_client_and_does_not_call_legacy_scraper() = runTest {
        val legacy = CountingLegacyRepo("Azora")
        val provider = RegistryChapterPageProvider(registry(legacy))

        val pages = provider.pagesOrNull(
            api = "Azora",
            mangaUrl = "https://azora.test/m/1",
            mangaLanguage = "ar",
            chapterUrl = "https://azora.test/m/1/c/1",
        )

        // Generic page URLs (not the legacy fake's "https://img.Azora.test/*.webp") → generic path used.
        assertEquals(listOf("https://gcdn.test/p1.webp", "https://gcdn.test/p2.webp"), pages?.map { it.url })
        // Legacy scraper was never consulted (generic succeeded, no fallback).
        assertEquals(0, legacy.pagesCalls)
    }

    @Test
    fun piloted_download_preserves_per_page_headers() = runTest {
        val provider = RegistryChapterPageProvider(registry(CountingLegacyRepo("Azora")))

        val pages = provider.pagesOrNull("Azora", "https://azora.test/m/1", "ar", "https://azora.test/m/1/c/1")

        assertEquals(mapOf("X-Generic" to "1", "Referer" to "https://azora.test"), pages?.first()?.headers)
    }

    @Test
    fun non_config_source_returns_null_so_caller_keeps_legacy() = runTest {
        // "Other" has no generic stanza → not config-backed → provider returns null without routing.
        val provider = RegistryChapterPageProvider(registry(CountingLegacyRepo("Other")))

        val pages = provider.pagesOrNull("Other", "https://other.test/m/1", "ar", "https://other.test/m/1/c/1")

        assertNull(pages)
    }

    @Test
    fun config_backed_generic_failure_throws_and_never_calls_legacy() = runTest {
        // config-backed = generic-only: a generic failure makes pagesOrNull THROW (the download worker
        // marks the chapter FAILED — a clear error). The legacy scraper is NEVER consulted.
        val legacy = CountingLegacyRepo("Azora", failPages = true)
        val provider = RegistryChapterPageProvider(registry(legacy, genericFailing = true))

        assertFailsWith<GenericPagesFailedException> {
            provider.pagesOrNull("Azora", "https://azora.test/m/1", "ar", "https://azora.test/m/1/c/1")
        }
        assertEquals(0, legacy.pagesCalls) // legacy never executed for a config-backed source
    }

    @Test
    fun config_backed_empty_generic_pages_throws_and_never_calls_legacy() = runTest {
        // A config-backed generic Success with ZERO pages is a selector failure → throw (clear failure),
        // not a silent blank chapter, and never legacy.
        val legacy = CountingLegacyRepo("Azora")
        val emptyRegistry = DefaultSourceRegistry(
            legacyRepos = setOf(legacy),
            updateManager = FakeUpdateManager(genericDoc("Azora")),
            genericClientFactory = { StubGenericPagesClient(it.api, pages = emptyList()) },
        )
        val provider = RegistryChapterPageProvider(emptyRegistry)

        assertFailsWith<GenericPagesFailedException> {
            provider.pagesOrNull("Azora", "https://azora.test/m/1", "ar", "https://azora.test/m/1/c/1")
        }
        assertEquals(0, legacy.pagesCalls)
    }
}
