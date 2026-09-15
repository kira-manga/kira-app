package me.manga.kira.sources.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceCatalogSnapshot
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins the generic-only registry: catalog absence can never infer a legacy adapter. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSourceRegistryTest {
    private class FixedUpdateManager(
        document: SourceConfigDocument,
    ) : SourceUpdateManager {
        private val documents = MutableStateFlow(document)
        private val mutableState =
            MutableStateFlow<UpdateState>(
                UpdateState.Active(document.revision, UpdateState.Origin.BUNDLED),
            )
        override val state: StateFlow<UpdateState> = mutableState.asStateFlow()
        override val acceptedDocument: StateFlow<SourceConfigDocument> = documents.asStateFlow()
        var imperativeReads = 0
            private set

        override fun activeDocument(): SourceConfigDocument {
            imperativeReads++
            return documents.value
        }

        override suspend fun refresh(): AppResult<SourceConfigDocument> = AppResult.Success(documents.value)

        fun replace(document: SourceConfigDocument) {
            documents.value = document
            mutableState.value = UpdateState.Active(document.revision, UpdateState.Origin.REMOTE)
        }
    }

    @Test
    fun catalog_observer_projects_each_accepted_document_without_imperative_reads() = runTest {
        val first = SourceConfigDocument(1, revision = 1, sources = listOf(config("first"), config("second")))
        val manager = FixedUpdateManager(first)
        val registry = DefaultSourceRegistry(manager) { MarkerClient(it.api) }
        val observed = mutableListOf<SourceCatalogSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { registry.catalog.toList(observed) }
        val renamed = config("second").copy(displayName = "Renamed", language = "ja", siteState = "STOPPED")
        val hidden = listOf(
            config("disabled").copy(lifecycle = "disabled"),
            config("legacy").copy(engine = "legacy"),
        )
        manager.replace(first.copy(revision = 2, sources = listOf(renamed, config("first")) + hidden))
        assertEquals(listOf(1L, 2L), observed.map { it.revision })
        assertEquals(listOf("second", "first"), observed.last().descriptors.map { it.api })
        val descriptor = observed.last().descriptors.first()
        assertEquals(
            listOf("Renamed", "ja", "STOPPED"),
            listOf(descriptor.displayName, descriptor.language, descriptor.siteState),
        )
        assertEquals(listOf("first", "second"), observed.first().descriptors.map { it.api })
        manager.replace(first.copy(revision = 3, sources = emptyList()))
        assertTrue(observed.last().descriptors.isEmpty())
        assertEquals(3, observed.size)
        assertEquals(0, manager.imperativeReads)
    }

    private class MarkerClient(
        override val api: String,
        private val failing: Boolean = false,
    ) : MangaSourceClient {
        private fun <T> result(value: T): AppResult<T> =
            if (failing) AppResult.Failure(AppError.Network.Http(403)) else AppResult.Success(value)

        override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> =
            result(listOf(HomeFeedItem(api, "x", "GENERIC", "u", "", null, emptyList(), emptyList())))

        override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> = result(emptyList())

        override suspend fun search(
            query: String,
            page: Int,
            filters: FilterSelections,
        ): AppResult<List<HomeFeedItem>> = result(emptyList())

        override suspend fun details(manga: Manga): AppResult<MangaDetails> =
            result(MangaDetails(api, "x", "GENERIC", "u", "", "", "", "", "", emptyList(), emptyList()))

        override fun pages(
            manga: Manga,
            chapter: Chapter,
        ): Flow<AppResult<List<Page>>> = flowOf(result(emptyList()))
    }

    @Test
    fun only_active_working_generic_sources_resolve() {
        val document =
            SourceConfigDocument(
                schemaVersion = 1,
                sources =
                    listOf(
                        config("active"),
                        config("maintenance").copy(siteState = "UNDER_MAINTENANCE"),
                        config("stopped").copy(siteState = "STOPPED"),
                        config("adult").copy(siteState = "ADULT_18_PLUS"),
                        config("disabled").copy(lifecycle = "disabled"),
                        config("legacy").copy(engine = "legacy"),
                    ),
            )
        val registry = registry(document)

        assertEquals("client:active", registry.get("active")?.api)
        assertNull(registry.get("maintenance"))
        assertNull(registry.get("stopped"))
        assertNull(registry.get("adult"))
        assertNull(registry.get("disabled"))
        assertNull(registry.get("legacy"))
        assertNull(registry.get("absent"))
        assertTrue(registry.isConfigBacked("maintenance"))
        assertEquals(
            listOf("active", "maintenance", "stopped", "adult"),
            registry.genericDescriptors().map { it.api },
        )
        assertEquals("UNDER_MAINTENANCE", registry.descriptor("maintenance")?.siteState)
    }

    @Test
    fun descriptor_is_hidden_for_every_non_active_source() {
        val document =
            SourceConfigDocument(
                schemaVersion = 1,
                sources = listOf(config("active"), config("retired").copy(lifecycle = "removed")),
            )
        val registry = registry(document)

        assertEquals("active", registry.descriptor("active")?.api)
        assertNull(registry.descriptor("retired"))
    }

    @Test
    fun generic_failure_is_surfaced_without_fallback() =
        runTest {
            val registry = registry(SourceConfigDocument(1, sources = listOf(config("active"))), failing = true)
            assertTrue(registry.get("active")?.home(1) is AppResult.Failure)
        }

    @Test
    fun empty_or_missing_catalog_returns_no_client() {
        val registry = registry(SourceConfigDocument(schemaVersion = 1))
        assertNull(registry.get("Azora"))
    }

    @Test
    fun operational_mode_changes_take_effect_without_recreating_the_registry() {
        val manager = FixedUpdateManager(SourceConfigDocument(1, revision = 1, sources = listOf(config("source"))))
        val registry =
            DefaultSourceRegistry(
                updateManager = manager,
                genericClientFactory = { MarkerClient("client:${it.api}") },
            )

        assertEquals("client:source", registry.get("source")?.api)

        manager.replace(
            SourceConfigDocument(
                1,
                revision = 2,
                sources = listOf(config("source").copy(siteState = "UNDER_MAINTENANCE")),
            ),
        )
        assertNull(registry.get("source"))
        assertEquals("UNDER_MAINTENANCE", registry.descriptor("source")?.siteState)

        manager.replace(SourceConfigDocument(1, revision = 3, sources = emptyList()))
        assertNull(registry.get("source"))
        assertNull(registry.descriptor("source"))

        manager.replace(SourceConfigDocument(1, revision = 4, sources = listOf(config("source"))))
        assertEquals("client:source", registry.get("source")?.api)
    }

    private fun registry(
        document: SourceConfigDocument,
        failing: Boolean = false,
    ): DefaultSourceRegistry =
        DefaultSourceRegistry(
            updateManager = FixedUpdateManager(document),
            genericClientFactory = { MarkerClient("client:${it.api}", failing) },
        )

    private fun config(api: String): SourceConfig =
        SourceConfig(
            api = api,
            language = "en",
            baseUrl = "https://$api.test",
            engine = "generic",
        )
}
