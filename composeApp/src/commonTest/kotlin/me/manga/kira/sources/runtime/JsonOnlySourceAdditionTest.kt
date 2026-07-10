package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.EndpointSpec
import me.manga.kira.sources.contracts.model.IconSpec
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry
import me.manga.kira.sources_repositry.data.MangaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The MangaSource-decoupling authoring invariant at the ASSEMBLY level (2026-07,
 * docs/sources/MANGASOURCE_DECOUPLING_PLAN.md): a brand-new source that exists ONLY as a JSON
 * stanza — an api that provably has NO [MangaSource] enum entry, no compiled repo, no Kotlin wiring
 * of any kind — passes the shipping validator alongside the real bundled document and is fully
 * discovered by the real registry: `isConfigBacked`, a generic client from `get()`, and a complete
 * descriptor (displayName + remote icon) for the UI.
 */
class JsonOnlySourceAdditionTest {
    private val syntheticApi = "Synthetic JSON-Only Source"

    private val synthetic =
        SourceConfig(
            api = syntheticApi,
            language = "(EN)",
            displayName = "Synthetic Scans",
            baseUrl = "https://synthetic.example",
            engine = "generic",
            icon = IconSpec(remoteUrl = "https://synthetic.example/icon.png"),
            endpoints =
                mapOf(
                    "home" to EndpointSpec(url = "{baseUrl}/latest?page={page}", listSelector = "div.item"),
                    "search" to EndpointSpec(url = "{baseUrl}/search?q={queryEncoded}", listSelector = "div.item"),
                    "details" to EndpointSpec(url = "{itemUrl}", listSelector = "div.detail"),
                    "pages" to EndpointSpec(url = "{chapterUrl}", listSelector = "img.page"),
                ),
        )

    private fun documentWithSynthetic(): SourceConfigDocument {
        val real =
            when (val parsed = SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON)) {
                is AppResult.Success -> parsed.value
                is AppResult.Failure -> fail("bundled document must parse: ${parsed.error}")
            }
        return real.copy(sources = real.sources + synthetic)
    }

    @Test
    fun the_synthetic_api_has_no_enum_entry_no_compiled_wiring() {
        // The precondition that makes this test meaningful: nothing in the Kotlin world knows this api.
        assertTrue(MangaSource.entries.none { it.API == syntheticApi })
    }

    @Test
    fun a_json_only_stanza_validates_alongside_the_real_document() {
        val result = DefaultSourceConfigValidator(DefaultStrategyRegistry()).validate(documentWithSynthetic())
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun a_json_only_stanza_is_fully_discovered_by_the_real_registry() {
        val registry =
            DefaultSourceRegistry(
                legacyRepos = emptySet(), // no compiled repo anywhere
                updateManager = FixedManager(documentWithSynthetic()),
                genericClientFactory = { config -> MarkerPagesClient("generic:${config.api}") },
            )

        assertTrue(registry.isConfigBacked(syntheticApi))
        assertEquals("generic:$syntheticApi", registry.get(syntheticApi)?.api)

        val descriptor = registry.descriptor(syntheticApi) ?: fail("descriptor must resolve")
        assertEquals("Synthetic Scans", descriptor.displayName)
        assertEquals("https://synthetic.example/icon.png", descriptor.iconRemoteUrl)
        assertTrue(registry.genericDescriptors().any { it.api == syntheticApi })
    }

    /** Never exercised — the test only inspects [me.manga.kira.sources.contracts.MangaSourceClient.api]. */
    private class MarkerPagesClient(
        override val api: String,
    ) : me.manga.kira.sources.contracts.MangaSourceClient {
        override suspend fun home(page: Int) = error("not exercised")

        override suspend fun featured(page: Int) = error("not exercised")

        override suspend fun search(
            query: String,
            page: Int,
            filters: FilterSelections,
        ) = error("not exercised")

        override suspend fun details(manga: me.manga.kira.domain.model.Manga) = error("not exercised")

        override fun pages(
            manga: me.manga.kira.domain.model.Manga,
            chapter: me.manga.kira.domain.model.Chapter,
        ) = error("not exercised")
    }

    private class FixedManager(
        private val document: SourceConfigDocument,
    ) : SourceUpdateManager {
        private val _state =
            MutableStateFlow<UpdateState>(UpdateState.Active(document.revision, UpdateState.Origin.BUNDLED))
        override val state: StateFlow<UpdateState> = _state.asStateFlow()

        override fun activeDocument(): SourceConfigDocument = document

        override suspend fun refresh(): AppResult<SourceConfigDocument> = AppResult.Success(document)
    }
}
