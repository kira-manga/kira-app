package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.mapper.classifyHomeThrowable
import me.manga.kira.data.mapper.toSiteState
import me.manga.kira.data.mapper.toSourceTab
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.domain.repository.HomeFeedRepository
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as SourceSelectionStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Home-feed adapter for the authoritative generic source catalog.
 *
 * The Room-backed source-selection store remains responsible for enablement, ordering, and the
 * selected API. Network access is resolved exclusively through [SourceRegistry]. An API missing
 * from the active catalog has no client and is never routed to a Kotlin scraper.
 */
class HomeFeedRepositoryImpl(
    private val sourcesRepository: SourceSelectionStore,
    private val dispatchers: DispatcherProvider,
    private val sourceRegistry: SourceRegistry,
) : HomeFeedRepository {

    private var accumulated: List<HomeFeedItem> = emptyList()
    private val accumulatorMutex = Mutex()
    private var accumulatorGeneration: Int = 0

    override fun observeSourceTabs(): Flow<List<SourceTab>> =
        sourcesRepository.allSources.map { entities ->
            entities
                .filter { it.isEnabled && sourceRegistry.isConfigBacked(it.name) }
                .sortedBy { it.priority }
                .mapNotNull { entity ->
                    sourceRegistry.descriptor(entity.name)?.let { descriptor ->
                        entity.toSourceTab(descriptor)
                    }
                }
        }

    override fun observeActiveTabIndex(): Flow<Int> =
        combine(observeSourceTabs(), sourcesRepository.activeApiFlow) { tabs, activeApi ->
            tabs.indexOfFirst { it.api == activeApi }.takeIf { it >= 0 } ?: 0
        }

    override fun observeSiteState(api: String): Flow<SiteState> =
        sourcesRepository.getSiteStateFlow(api).map { it.toSiteState() }

    override suspend fun selectTab(index: Int) {
        val api = observeSourceTabs().first().getOrNull(index)?.api ?: return
        sourcesRepository.updateActiveByApi(api)
        clearAccumulator()
    }

    override suspend fun selectSource(api: String) {
        if (sourceRegistry.get(api) == null) return
        sourcesRepository.updateActiveByApi(api)
        clearAccumulator()
    }

    private suspend fun clearAccumulator() =
        accumulatorMutex.withLock {
            accumulated = emptyList()
            accumulatorGeneration++
        }

    override suspend fun fetchHome(reset: Boolean): AppResult<List<HomeFeedItem>> =
        withContext(dispatchers.io) {
            val generation =
                accumulatorMutex.withLock {
                    if (reset) {
                        accumulated = emptyList()
                        accumulatorGeneration++
                    }
                    accumulatorGeneration
                }
            val client = when (val result = activeClientResult()) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return@withContext result
            }
            client.home(1).also { result ->
                if (result is AppResult.Success) {
                    accumulatorMutex.withLock {
                        if (generation == accumulatorGeneration) accumulated = result.value
                    }
                }
            }
        }

    override suspend fun fetchMore(page: Int): AppResult<List<HomeFeedItem>> =
        withContext(dispatchers.io) {
            val (generation, base) =
                accumulatorMutex.withLock { accumulatorGeneration to accumulated }
            val client = when (val result = activeClientResult()) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return@withContext result
            }
            when (val result = client.home(page)) {
                is AppResult.Success -> {
                    val merged =
                        accumulatorMutex.withLock {
                            if (generation == accumulatorGeneration) {
                                (accumulated + result.value)
                                    .distinctBy { it.url }
                                    .also { accumulated = it }
                            } else {
                                (base + result.value).distinctBy { it.url }
                            }
                        }
                    AppResult.Success(merged)
                }
                is AppResult.Failure -> result
            }
        }

    override suspend fun fetchFeatured(): AppResult<List<FeaturedManga>> =
        withContext(dispatchers.io) {
            when (val result = activeClientResult()) {
                is AppResult.Success -> result.value.featured(1)
                is AppResult.Failure -> result
            }
        }

    override suspend fun loadSourceFilters(): AppResult<List<SourceFilter>> =
        withContext(dispatchers.io) {
            val api = when (val result = activeApiResult()) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> return@withContext result
            }
            val descriptor = sourceRegistry.descriptor(api)
                ?: return@withContext sourceUnavailable(api)
            AppResult.Success(descriptor.filters)
        }

    private suspend fun activeApiResult(): AppResult<String> =
        try {
            val persisted = sourcesRepository.activeApiFlow.value
            val enabledApis =
                sourcesRepository.allSources
                    .first()
                    .asSequence()
                    .filter { it.isEnabled && sourceRegistry.isConfigBacked(it.name) }
                    .sortedBy { it.priority }
                    .map { it.name }
                    .toList()
            val selected = enabledApis.firstOrNull { it == persisted } ?: enabledApis.firstOrNull()
            selected?.let { AppResult.Success(it) }
                ?: AppResult.Failure(AppError.Validation.NoEnabledSources())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppResult.Failure(classifyHomeThrowable(t))
        }

    private suspend fun activeClientResult(): AppResult<MangaSourceClient> =
        when (val result = activeApiResult()) {
            is AppResult.Success ->
                sourceRegistry.get(result.value)?.let { AppResult.Success(it) }
                    ?: sourceUnavailable(result.value)
            is AppResult.Failure -> result
        }

    private fun <T> sourceUnavailable(api: String): AppResult<T> =
        AppResult.Failure(
            AppError.Validation.SourceUnavailable(api = api),
        )
}
