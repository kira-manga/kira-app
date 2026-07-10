package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.sources.contracts.model.EndpointSpec
import me.manga.kira.sources.contracts.model.FilterDefinition
import me.manga.kira.sources.contracts.model.FilterOptionSpec
import me.manga.kira.sources.contracts.model.FilterRequestSpec
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
            // The full standard-filter convention set + one custom filter, declared by JSON alone
            // (config-driven filters, 2026-07 — CONFIG_DRIVEN_FILTERS_PLAN.md §8 items 1/17).
            filters =
                listOf(
                    FilterDefinition(
                        id = "genres",
                        label = "Genres",
                        type = "multiselect",
                        options = listOf(FilterOptionSpec("action"), FilterOptionSpec("drama")),
                        request = FilterRequestSpec(target = "query", param = "genre[]", encode = "repeat"),
                    ),
                    FilterDefinition(
                        id = "sort",
                        label = "Sort",
                        type = "select",
                        options = listOf(FilterOptionSpec("latest", "Latest"), FilterOptionSpec("views", "Views")),
                        default = "latest",
                        request = FilterRequestSpec(target = "query", param = "orderby"),
                    ),
                    FilterDefinition(
                        id = "status",
                        label = "Status",
                        type = "select",
                        options = listOf(FilterOptionSpec("ongoing"), FilterOptionSpec("completed")),
                        request = FilterRequestSpec(target = "query", param = "status"),
                    ),
                    FilterDefinition(
                        id = "language",
                        label = "Language",
                        type = "select",
                        options = listOf(FilterOptionSpec("en"), FilterOptionSpec("ar")),
                        request = FilterRequestSpec(target = "query", param = "lang"),
                    ),
                    FilterDefinition(
                        id = "type",
                        label = "Type",
                        type = "multiselect",
                        options = listOf(FilterOptionSpec("manga"), FilterOptionSpec("manhwa")),
                        request = FilterRequestSpec(target = "query", param = "type", encode = "csv"),
                    ),
                    FilterDefinition(
                        id = "min_rating",
                        label = "Minimum rating",
                        type = "number",
                        request = FilterRequestSpec(target = "query", param = "min_rating"),
                    ),
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

    @Test
    fun a_json_only_stanza_exposes_its_full_ordered_filter_set_through_the_descriptor() {
        // Items 1 + 17 of the filters test matrix: genres/sort/status/language/type + a CUSTOM
        // filter, all declared by the stanza alone, surface as ordered render-ready descriptors —
        // this list is exactly what SearchState.filters holds and the sheet renders. No enum entry,
        // no Kotlin filter class, no when(api) anywhere (the request side is pinned by the engine's
        // FilterRequestComposer / GenericSourceClientFilterRequestTest suites).
        val registry =
            DefaultSourceRegistry(
                legacyRepos = emptySet(),
                updateManager = FixedManager(documentWithSynthetic()),
                genericClientFactory = { config -> MarkerPagesClient("generic:${config.api}") },
            )

        val filters = registry.descriptor(syntheticApi)?.filters ?: fail("descriptor must resolve")
        assertEquals(
            listOf("genres", "sort", "status", "language", "type", "min_rating"),
            filters.map { it.id },
            "declaration order is render order",
        )
        assertEquals(FilterControlType.MULTISELECT, filters[0].type)
        assertEquals(listOf("action", "drama"), filters[0].options.map { it.value })
        assertEquals(listOf("latest"), filters[1].defaultValues)
        assertEquals("Minimum rating", filters[5].label)
        assertEquals(FilterControlType.NUMBER, filters[5].type)
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
