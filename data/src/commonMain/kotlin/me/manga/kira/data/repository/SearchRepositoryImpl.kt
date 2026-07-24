package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.mapper.classifyHomeThrowable
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.repository.SearchRepository
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as SourceSelectionStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Searches only sources present in the authoritative active generic catalog.
 *
 * The local source store selects and orders sources, while [SourceRegistry] is the sole client
 * resolver. Missing catalog entries produce a typed unavailable failure and never reach a Kotlin
 * scraper.
 */
class SearchRepositoryImpl(
    private val sourcesRepository: SourceSelectionStore,
    private val dispatchers: DispatcherProvider,
    private val sourceRegistry: SourceRegistry,
) : SearchRepository {

    override suspend fun searchSource(
        query: String,
        selections: FilterSelections,
    ): AppResult<List<HomeFeedItem>> =
        withContext(dispatchers.io) {
            try {
                val api = activeApi()
                    ?: return@withContext AppResult.Failure(AppError.Validation.NoEnabledSources())
                val client = sourceRegistry.get(api)
                    ?: return@withContext sourceUnavailable(api)
                client.search(query, page = 1, filters = selections)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppResult.Failure(classifyHomeThrowable(t))
            }
        }

    private suspend fun activeApi(): String? {
        val persisted = sourcesRepository.activeApiFlow.value
        val enabledApis =
            sourcesRepository.allSources
                .first()
                .asSequence()
                .filter { it.isEnabled && sourceRegistry.isConfigBacked(it.name) }
                .sortedBy { it.priority }
                .map { it.name }
                .toList()
        return enabledApis.firstOrNull { it == persisted } ?: enabledApis.firstOrNull()
    }

    override fun searchAllRepos(query: String): Flow<Map<String, AppResult<List<HomeFeedItem>>?>> =
        flow {
            val apis =
                sourcesRepository.allSources
                    .first()
                    .asSequence()
                    .filter { it.isEnabled && sourceRegistry.isConfigBacked(it.name) }
                    .sortedBy { it.priority }
                    .map { it.name }
                    .toList()

            val accumulated = linkedMapOf<String, AppResult<List<HomeFeedItem>>?>()
            apis.forEach { accumulated[it] = null }
            emit(accumulated.toMap())

            val sourceFlows =
                apis.map { api ->
                    flow {
                        val result =
                            sourceRegistry.get(api)?.search(query, page = 1)
                                ?: sourceUnavailable(api)
                        emit(api to result)
                    }.catch { t ->
                        if (t is CancellationException) throw t
                        emit(api to AppResult.Failure(classifyHomeThrowable(t)))
                    }.flowOn(dispatchers.io)
                }

            merge(*sourceFlows.toTypedArray()).collect { (api, result) ->
                accumulated[api] = result
                emit(accumulated.toMap())
            }
        }.flowOn(dispatchers.io)

    private fun <T> sourceUnavailable(api: String): AppResult<T> =
        AppResult.Failure(
            AppError.Validation.SourceUnavailable(api = api),
        )
}
