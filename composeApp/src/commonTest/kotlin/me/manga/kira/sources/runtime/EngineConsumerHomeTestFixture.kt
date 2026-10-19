package me.manga.kira.sources.runtime

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.data.repository.HomeFeedRepositoryImpl
import me.manga.kira.data.repository.MangaDetailsRepositoryImpl
import me.manga.kira.domain.model.sources.Source
import me.manga.kira.domain.repository.HomeFeedRepository
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.repository.SourcesRepository
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.domain.usecase.home.FetchFeaturedUseCase
import me.manga.kira.domain.usecase.home.FetchHomeFeedUseCase
import me.manga.kira.domain.usecase.home.FetchMoreHomeFeedUseCase
import me.manga.kira.domain.usecase.home.ObserveActiveTabIndexUseCase
import me.manga.kira.domain.usecase.home.ObserveSiteStateUseCase
import me.manga.kira.domain.usecase.home.ObserveSourceTabsUseCase
import me.manga.kira.domain.usecase.home.SelectSourceTabUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.domain.usecase.library.ToggleInLibraryUseCase
import me.manga.kira.domain.usecase.sources.ClearNewSourcesBadgeUseCase
import me.manga.kira.domain.usecase.sources.ObserveNewSourcesBadgeUseCase
import me.manga.kira.presentation.features.repo_settings.domain.SourceState
import me.manga.kira.presentation.home.HomeViewModel
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as SourceSelectionStore

/** Real source selection and Home composition, with explicit ownership of all long-lived collectors. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.withEngineConsumerHome(
    registry: DefaultSourceRegistry,
    block: suspend (HomeViewModel) -> Unit,
) {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    val store = ViewModelStore()
    var owner: Job? = null
    try {
        val viewModel = createConsumerHome(registry, dispatcher)
        owner = viewModel.viewModelScope.coroutineContext.job
        store.put("engine-consumer-home", viewModel)
        block(viewModel)
    } finally {
        try {
            store.clear()
            withContext(NonCancellable + Dispatchers.Default) { withTimeout(5_000) { owner?.join() } }
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private fun TestScope.createConsumerHome(
    registry: DefaultSourceRegistry,
    dispatcher: CoroutineDispatcher,
): HomeViewModel {
    val selection =
        SourceSelectionStore(
            sourcesDao = ConsumerSourcesDao(),
            repos = emptySet(),
            prefs = SharedPrefsHelper(MapSettings()),
            applicationScope = backgroundScope,
        )
    val dispatchers = engineConsumerDispatchers(dispatcher)
    return consumerHomeViewModel(
        HomeFeedRepositoryImpl(selection, dispatchers, registry),
        MangaDetailsRepositoryImpl(dispatchers, registry),
    )
}

private fun consumerHomeViewModel(
    home: HomeFeedRepository,
    details: MangaDetailsRepository,
): HomeViewModel =
    HomeViewModel(
        observeSourceTabs = ObserveSourceTabsUseCase(home),
        observeActiveTabIndex = ObserveActiveTabIndexUseCase(home),
        observeSiteState = ObserveSiteStateUseCase(home),
        selectSourceTab = SelectSourceTabUseCase(home),
        fetchHomeFeed = FetchHomeFeedUseCase(home),
        fetchMoreHomeFeed = FetchMoreHomeFeedUseCase(home),
        fetchFeatured = FetchFeaturedUseCase(home),
        observeLibrary = ObserveLibraryUseCase(EngineConsumerEmptyLibrary),
        toggleInLibrary = ToggleInLibraryUseCase(EngineConsumerEmptyLibrary),
        fetchDetails = FetchMangaDetailsUseCase(details),
        observeNewSourcesBadge = ObserveNewSourcesBadgeUseCase(ConsumerNoBadge),
        clearNewSourcesBadge = ClearNewSourcesBadgeUseCase(ConsumerNoBadge),
    )

private class ConsumerSourcesDao : SourcesDao {
    private val row =
        SourcesEntity(
            name = EngineConsumerTestFixtures.API,
            isEnabled = true,
            priority = 0,
            language = "en",
            siteState = SourceState.WORKING,
            baseUrl = EngineConsumerTestFixtures.BASE,
            imageBaseUrl = "",
            imageUrlVersion = 0,
        )

    override fun getAllSources(): Flow<List<SourcesEntity>> = flowOf(listOf(row))

    override suspend fun getAllSourcesOnce(): List<SourcesEntity> = listOf(row)

    override suspend fun getBaseUrlFor(name: String): String? = row.takeIf { it.name == name }?.baseUrl

    override fun getSiteStateByName(name: String): Flow<SourceState?> =
        flowOf(row.takeIf { it.name == name }?.siteState)

    override suspend fun getSiteStateByNameSync(name: String): SourceState? = row.takeIf { it.name == name }?.siteState

    override suspend fun insert(source: SourcesEntity): Long = unusedMutation()

    override suspend fun setEnabledByName(name: String, enabled: Boolean): Int = unusedMutation()

    override suspend fun setEnabledByNames(names: List<String>, enabled: Boolean): Int = unusedMutation()

    override suspend fun updateBaseUrlAndVersionByName(
        name: String,
        baseUrl: String,
        version: Int,
    ): Int = unusedMutation()

    override suspend fun updateImageBaseUrlAndVersionByName(
        apiName: String,
        newImageBaseUrl: String,
        newImageVersion: Int,
    ): Int = unusedMutation()

    override suspend fun updateSiteStateByName(name: String, siteState: SourceState): Int = unusedMutation()

    override suspend fun deleteSourceByName(name: String): Int = unusedMutation()

    override suspend fun disableOutsideCatalog(activeApis: List<String>): Int = unusedMutation()

    override suspend fun deleteOutsideCatalog(activeApis: List<String>): Int = unusedMutation()

    override suspend fun updateCatalogMetadata(
        api: String,
        priority: Int,
        language: String,
        siteState: SourceState,
    ): Int = unusedMutation()
}

private object ConsumerNoBadge : SourcesRepository {
    override fun observeHasNewSources(): Flow<Boolean> = flowOf(false)

    override fun observeSources(): Flow<List<Source>> = unusedMutation()

    override suspend fun setSourceEnabled(api: String, enabled: Boolean): Unit = unusedMutation()

    override suspend fun setHasNewSources(value: Boolean): Unit = unusedMutation()

    override suspend fun setLanguageEnabled(language: String, enabled: Boolean): Unit = unusedMutation()

    override suspend fun setLanguageEnabledWithFallback(primary: String, fallback: String, enabled: Boolean): Unit =
        unusedMutation()
}

private fun unusedMutation(): Nothing = error("Unexpected operation outside the Home source-consumer fixture")
