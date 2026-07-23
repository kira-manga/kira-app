package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
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
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins the generic-only registry: catalog absence can never infer a legacy adapter. */
class DefaultSourceRegistryTest {
    private class FixedUpdateManager(
        private val document: SourceConfigDocument,
    ) : SourceUpdateManager {
        private val mutableState =
            MutableStateFlow<UpdateState>(
                UpdateState.Active(document.revision, UpdateState.Origin.BUNDLED),
            )
        override val state: StateFlow<UpdateState> = mutableState.asStateFlow()

        override fun activeDocument(): SourceConfigDocument = document

        override suspend fun refresh(): AppResult<SourceConfigDocument> = AppResult.Success(document)
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
    fun only_active_generic_sources_resolve() {
        val document =
            SourceConfigDocument(
                schemaVersion = 1,
                sources =
                    listOf(
                        config("active"),
                        config("disabled").copy(lifecycle = "disabled"),
                        config("legacy").copy(engine = "legacy"),
                    ),
            )
        val registry = registry(document)

        assertEquals("client:active", registry.get("active")?.api)
        assertNull(registry.get("disabled"))
        assertNull(registry.get("legacy"))
        assertNull(registry.get("absent"))
        assertEquals(listOf("active"), registry.genericDescriptors().map { it.api })
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
