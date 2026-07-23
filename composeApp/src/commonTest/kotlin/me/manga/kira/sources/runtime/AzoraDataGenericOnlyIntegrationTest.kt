package me.manga.kira.sources.runtime

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.data.repository.HomeFeedRepositoryImpl
import me.manga.kira.data.repository.MangaDetailsRepositoryImpl
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.presentation.features.repo_settings.domain.SourceState
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Go-live proof: the FULL chain through the real `:data` repository → [DefaultSourceRegistry] → generic
 * client, with real components (not a faked registry). Confirms (a) `:data` routes the config-backed
 * Azora through the registry's generic client, and (b) **config-backed = generic-ONLY**: when the
 * generic engine fails, `:data` surfaces a clear `Failure` and the legacy scraper is NOT executed
 * (the legacy-fallback wrapper was removed; see [DefaultSourceRegistry]).
 */
class AzoraDataGenericOnlyIntegrationTest {
    private val dispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
        }

    private val azoraGenericDoc =
        SourceConfigDocument(
            schemaVersion = 1,
            sources =
                listOf(
                    SourceConfig(
                        api = "Azora",
                        language = "(AR)",
                        baseUrl = "https://api.azoramoon.com",
                        engine = "generic",
                    ),
                ),
        )

    /** A real registry where the only available Azora client is generic and always fails. */
    private fun registryWithFailingGeneric() =
        DefaultSourceRegistry(
            updateManager = FixedUpdateManager(azoraGenericDoc),
            genericClientFactory = { FailingGenericClient(it.api) },
        )

    private fun registryWith(
        factory: (me.manga.kira.sources.contracts.model.SourceConfig) -> MangaSourceClient,
    ) =
        DefaultSourceRegistry(
            updateManager = FixedUpdateManager(azoraGenericDoc),
            genericClientFactory = factory,
        )

    /** A selection store with one catalog-projected row and no executable scraper repositories. */
    private fun azoraActiveSources() =
        SourcesRepository(
            sourcesDao =
                SeededSourcesDao(
                    listOf(
                        SourcesEntity(
                            name = "Azora",
                            isEnabled = true,
                            priority = 0,
                            language = "(AR)",
                            siteState = SourceState.WORKING,
                            baseUrl = "https://api.azoramoon.com",
                            baseVersion = 1,
                            imageBaseUrl = "",
                            imageUrlVersion = 0,
                        ),
                    ),
                ),
            repos = emptySet(),
            prefs = SharedPrefsHelper(MapSettings()),
            applicationScope = CoroutineScope(Dispatchers.Unconfined),
        )

    @Test
    fun data_surfaces_failure_for_azora_details_when_generic_fails_no_legacy() = runTest {
        val repo = MangaDetailsRepositoryImpl(
            dispatchers = dispatchers,
            sourceRegistry = registryWithFailingGeneric(),
        )

        val manga =
            Manga(
                "Azora",
                "(AR)",
                "t",
                "https://api.azoramoon.com/api/post/?postId=1",
                "",
                null,
                emptyList(),
            )
        val result = repo.fetchDetails(manga)

        // config-backed = generic-only: the generic 403 is surfaced as-is. The legacy AzoraRepo
        // ("One Piece"/"Oda") is NEVER executed — a fallback would have returned Success("One Piece").
        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.Network.Http && error.statusCode == 403)
    }

    @Test
    fun data_routes_azora_to_generic_when_it_succeeds() = runTest {
        // Same wiring but the generic client SUCCEEDS → its result is used (not the legacy fallback).
        val registry = DefaultSourceRegistry(
            updateManager = FixedUpdateManager(azoraGenericDoc),
            genericClientFactory = { GenericOk(it.api) },
        )
        val repo = MangaDetailsRepositoryImpl(dispatchers, registry)

        val manga =
            Manga(
                "Azora",
                "(AR)",
                "t",
                "https://api.azoramoon.com/api/post/?postId=1",
                "",
                null,
                emptyList(),
            )
        val details = (repo.fetchDetails(manga) as AppResult.Success).value
        assertEquals("GENERIC ENGINE", details.title) // from the generic client, not legacy "One Piece"
    }

    @Test
    fun data_surfaces_failure_for_azora_home_when_generic_fails_no_legacy() = runTest {
        // Full chain for HOME: :data HomeFeedRepositoryImpl → registry → generic (no fallback).
        val repo = HomeFeedRepositoryImpl(
            sourcesRepository = azoraActiveSources(),
            dispatchers = dispatchers,
            sourceRegistry = registryWith { FailingGenericClient(it.api) },
        )
        val result = repo.fetchHome(reset = true)
        // Generic failure is surfaced; no alternate source implementation exists.
        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.Network.Http && error.statusCode == 403)
    }

    @Test
    fun data_rejects_source_absent_from_catalog() = runTest {
        val repo = MangaDetailsRepositoryImpl(dispatchers, registryWithFailingGeneric())
        val manga = Manga("Other", "(EN)", "t", "u", "", null, emptyList())
        val result = repo.fetchDetails(manga)
        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.Validation.SourceUnavailable && error.api == "Other")
    }

    // --- fakes -----------------------------------------------------------------------------------

    private class FixedUpdateManager(private val doc: SourceConfigDocument) : SourceUpdateManager {
        private val mutableState =
            MutableStateFlow<UpdateState>(
                UpdateState.Active(doc.revision, UpdateState.Origin.BUNDLED),
            )
        override val state: StateFlow<UpdateState> = mutableState.asStateFlow()

        override fun activeDocument(): SourceConfigDocument = doc

        override suspend fun refresh(): AppResult<SourceConfigDocument> = AppResult.Success(doc)
    }

    private class FailingGenericClient(override val api: String) : MangaSourceClient {
        override suspend fun home(page: Int) = AppResult.Failure(AppError.Network.Http(403))
        override suspend fun featured(page: Int) = AppResult.Failure(AppError.Network.Http(403))
        override suspend fun search(
            query: String,
            page: Int,
            filters: FilterSelections,
        ) = AppResult.Failure(AppError.Network.Http(403))

        override suspend fun details(manga: Manga) = AppResult.Failure(AppError.Network.Http(403))

        override fun pages(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> =
            flowOf(AppResult.Failure(AppError.Network.Http(403)))
    }

    private class GenericOk(override val api: String) : MangaSourceClient {
        private val details =
            MangaDetails(
                api,
                "(AR)",
                "GENERIC ENGINE",
                "u",
                "",
                "",
                "",
                "",
                "",
                emptyList(),
                emptyList(),
            )

        override suspend fun home(page: Int) = AppResult.Success(emptyList<HomeFeedItem>())

        override suspend fun featured(page: Int) = AppResult.Success(emptyList<FeaturedManga>())

        override suspend fun search(
            query: String,
            page: Int,
            filters: FilterSelections,
        ) = AppResult.Success(emptyList<HomeFeedItem>())

        override suspend fun details(manga: Manga) = AppResult.Success(details)

        override fun pages(
            manga: Manga,
            chapter: Chapter,
        ): Flow<AppResult<List<Page>>> = flowOf(AppResult.Success(emptyList()))
    }

    private class SeededSourcesDao(
        private val rows: List<SourcesEntity>,
    ) : SourcesDao {

        override fun getAllSources(): Flow<List<SourcesEntity>> = flowOf(rows)

        override suspend fun getAllSourcesOnce(): List<SourcesEntity> = rows

        override suspend fun insert(source: SourcesEntity): Long = 1L

        override suspend fun setEnabledByName(name: String, enabled: Boolean): Int = 0

        override suspend fun getBaseUrlFor(name: String): String? = rows.firstOrNull { it.name == name }?.baseUrl

        override fun getSiteStateByName(name: String): Flow<SourceState?> =
            flowOf(rows.firstOrNull { it.name == name }?.siteState)

        override suspend fun getSiteStateByNameSync(name: String): SourceState? =
            rows.firstOrNull { it.name == name }?.siteState

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

        override suspend fun disableOutsideCatalog(activeApis: List<String>): Int = 0

        override suspend fun deleteOutsideCatalog(activeApis: List<String>): Int = 0

        override suspend fun updateCatalogMetadata(
            api: String,
            priority: Int,
            language: String,
            siteState: SourceState,
        ): Int = 0
    }
}
