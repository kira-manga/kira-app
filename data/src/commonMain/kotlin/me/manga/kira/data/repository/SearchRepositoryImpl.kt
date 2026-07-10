package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.states.State as LegacyState
import me.manga.kira.data.mapper.classifyHomeThrowable
import me.manga.kira.data.mapper.legacySearchTypeOf
import me.manga.kira.data.mapper.toAppError
import me.manga.kira.data.mapper.toHomeFeedItem
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.repository.SearchRepository
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources_repositry.BaseMangaRepository
import me.manga.kira.domain.model.MangaItem as LegacyMangaItem
import me.manga.kira.presentation.features.home.data.SearchType
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as LegacySourcesRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Source-backed [SearchRepository] strangler-fig implementation (Epic H2).
 *
 * SRP (contract §6): owns ONE rule — "route a single-source search (query + generic filter
 * selections) onto the right engine — the generic client for a config-backed source, the legacy
 * `fetchSearchDataF` (selections translated to `SearchType`) otherwise — and fan the plain-text
 * query out across all enabled sources merging per-repo results for the aggregated tab".
 * Source-routing + the per-source search-URL shape live in the generic engine / the legacy
 * [BaseMangaRepository]; this impl is a thin router + classifier on top.
 *
 * **Temporary `:data` → `:shared` seam**: same boundary as [HomeFeedRepositoryImpl] —
 * [LegacySourcesRepository], [BaseMangaRepository], [SearchType], [LegacyState], [LegacyMangaItem]
 * all live in `:sources:legacy`. The `:domain` [SearchRepository] interface never sees
 * [SearchType] (locked decision H-§87 — generic filter selections are translated to [SearchType]
 * inside `legacySearchTypeOf` for legacy sources only).
 *
 * **Legacy method binding**:
 *  - [searchSource] ← the active [BaseMangaRepository.fetchSearchDataF] (BaseMangaRepository.kt:48),
 *    matching `MangaViewModel.startSearch` (MangaViewModel.kt:260).
 *  - [searchAllRepos] ← [LegacySourcesRepository.getEnabledRepos] (SourcesRepository.kt:253) +
 *    per-repo `initSite()` + `fetchSearchDataF(SearchType.Normal(query))`, mirroring
 *    `HomeViewModel.fetchAllSearchResults` (HomeViewModel.kt:130-155).
 *
 * **Multi-repo fan-out** (replicates `HomeViewModel.fetchAllSearchResults`): snapshot the enabled
 * repos (warming each one's base URL via `getBaseUrl()` exactly as the legacy VM did with
 * `.onEach { it.getBaseUrl() }`), turn each into a `Flow<Pair<api, AppResult>>`, [merge] them so a
 * partial map is emitted the instant any single repo resolves, and accumulate into an immutable
 * snapshot map. One repo failing yields an [AppResult.Failure] under its own `api` key — it does
 * NOT sink the others (each repo's flow is independently `catch`-classified). The whole flow is
 * cold + cancel-on-collector-cancel, so a new query collecting cancels the prior fan-out (locked
 * decision H-§77-(4)) — the cancellation lives at the collector (the H3 ViewModel's
 * single-flight job), keeping this layer free of mutable job state.
 *
 * Error classification: reuses the shared `:data` helpers in `HomeMappers.kt`
 * ([LegacyState.Error.toAppError] + [classifyHomeThrowable]) — same buckets as
 * [MangaDetailsRepositoryImpl] / [HomeFeedRepositoryImpl].
 *
 * Cancellation: [CancellationException] propagates unchanged (structured-concurrency invariant).
 */
class SearchRepositoryImpl(
    private val sourcesRepository: LegacySourcesRepository,
    private val dispatchers: DispatcherProvider,
    private val sourceRegistry: SourceRegistry,
) : SearchRepository {

    override suspend fun searchSource(
        query: String,
        selections: FilterSelections,
    ): AppResult<List<HomeFeedItem>> = withContext(dispatchers.io) {
        try {
            val active = activeConfigBackedSource()
            if (sourceRegistry.isConfigBacked(active.api)) {
                // Config-backed → ALWAYS the generic engine, filtered or not (config-driven
                // filters, 2026-07). There is deliberately NO drop-to-legacy here: the source's
                // validated stanza is the only filter authority, and selections it doesn't declare
                // are ignored by the request composer. Falling back to the legacy scraper for an
                // "incomplete" filter config would silently apply DIFFERENT filter semantics — the
                // forbidden wrong-but-Success mode.
                val client = sourceRegistry.get(active.api)
                    ?: return@withContext AppResult.Failure(
                        AppError.Unexpected("Config-backed source '${active.api}' resolved no client"),
                    )
                return@withContext client.search(query, page = 1, filters = selections)
            }
            // Legacy floor: translate the standard sort/genres selections onto the legacy
            // SearchType (sort > genres precedence, CSV genres — pre-generic semantics unchanged).
            val repo = active.legacyRepo
                ?: return@withContext AppResult.Failure(
                    AppError.Unexpected("Source '${active.api}' has no legacy search path"),
                )
            when (val terminal = repo.fetchSearchDataF(legacySearchTypeOf(query, selections)).awaitTerminalState()) {
                is LegacyState.Success -> AppResult.Success(terminal.data.map { it.toHomeFeedItem() })
                is LegacyState.Error -> AppResult.Failure(terminal.toAppError())
                LegacyState.Loading -> error("Filtered above")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppResult.Failure(classifyHomeThrowable(t))
        }
    }

    /** The resolved active search source: stable [api] + the compiled legacy repo when one exists. */
    private data class ActiveSearchSource(
        val api: String,
        val legacyRepo: BaseMangaRepository?,
    )

    /**
     * The active source, guaranteed config-backed (Sources Migration Phase 5/6), resolved in
     * api-string space (MangaSource decoupling, 2026-07) so a config-only source is searchable.
     * The persisted api wins when its row is enabled ∧ config-backed; otherwise the first
     * config-backed+enabled row substitutes — same resolution as Home's activeSource, so search
     * always targets the source the feed shows. Catastrophic floor (no config-backed source at
     * all): the legacy active repo, exactly as before.
     */
    private suspend fun activeConfigBackedSource(): ActiveSearchSource {
        val persisted = sourcesRepository.activeApiFlow.value
        val configBackedRows = sourcesRepository.allSources.first()
            .filter { it.isEnabled && sourceRegistry.isConfigBacked(it.name) }
            .sortedBy { it.priority }
        val chosen = configBackedRows.firstOrNull { it.name == persisted } ?: configBackedRows.firstOrNull()
        if (chosen != null) {
            return ActiveSearchSource(api = chosen.name, legacyRepo = sourcesRepository.getOrRepoByName(chosen.name))
        }
        val active = sourcesRepository.activeRepo.first()
        return ActiveSearchSource(api = active.API, legacyRepo = active.takeIf { it.API.isNotBlank() })
    }

    override fun searchAllRepos(query: String): Flow<Map<String, AppResult<List<HomeFeedItem>>?>> =
        flow {
            // Snapshot enabled ∧ config-backed rows in priority order (MangaSource decoupling,
            // 2026-07: api strings from the sources table — a config-only source with no compiled
            // repo is searched like any other). LinkedHashMap so the per-source sections render in
            // enabled order. Sources Migration Phase 5/6: search-all only fans out over
            // config-backed sources — a legacy source is never searched even if its row is enabled.
            val apis = sourcesRepository.allSources.first()
                .filter { it.isEnabled && sourceRegistry.isConfigBacked(it.name) }
                .sortedBy { it.priority }
                .map { it.name }

            // Seed every source as `null` (loading) and emit immediately, so the UI shows a
            // per-source spinner section up front instead of a misleading empty "no results" panel
            // while the fan-out is still running. (The old legacy base-URL warm is gone with the
            // repo objects — the generic engine resolves its live base URL per request through
            // SourceBaseUrlProvider, so there is nothing to warm on an all-generic fan-out.)
            val accumulated = linkedMapOf<String, AppResult<List<HomeFeedItem>>?>()
            apis.forEach { accumulated[it] = null }
            emit(accumulated.toMap())

            val perRepoFlows = apis.map { api ->
                val client = sourceRegistry.get(api)
                val repoFlow = if (client != null && sourceRegistry.isConfigBacked(api)) {
                    // Config-backed source → the generic engine. One terminal emission per source,
                    // same (api, AppResult) shape as the legacy branch.
                    flow { emit(api to client.search(query, page = 1)) }
                } else {
                    // Defensive floor — unreachable while the fan-out filter is config-backed-only,
                    // kept for a compiled legacy repo should the filter ever widen.
                    val repo = sourcesRepository.getOrRepoByName(api)
                    if (repo == null) {
                        flow {
                            emit(api to AppResult.Failure(AppError.Unexpected("Unknown source api=$api")))
                        }
                    } else {
                        flow {
                            repo.initSite()
                            repo.fetchSearchDataF(SearchType.Normal(query)).collect { state ->
                                // Skip the legacy Loading state: the source keeps its seeded `null`
                                // (loading) marker until a terminal Success/Error lands, so a
                                // still-fetching source keeps its spinner instead of flashing an
                                // empty-success "no results".
                                state.toResultOrNull()?.let { emit(api to it) }
                            }
                        }
                    }
                }
                // Independently catch-classify each source: a throwing source (initSite/fetch/legacy
                // flow that throws instead of emitting State.Error) surfaces as its own per-api
                // Failure row instead of cancelling the whole fan-out (see class KDoc).
                repoFlow
                    .catch { t ->
                        if (t is CancellationException) throw t
                        emit(api to AppResult.Failure(classifyHomeThrowable(t)))
                    }
                    .flowOn(dispatchers.io)
            }

            merge(*perRepoFlows.toTypedArray()).collect { (api, result) ->
                accumulated[api] = result
                emit(accumulated.toMap())
            }
        }.flowOn(dispatchers.io)

    /** Project a single legacy search `State` into the rework [AppResult], or `null` while Loading. */
    private fun LegacyState<List<LegacyMangaItem>>.toResultOrNull(): AppResult<List<HomeFeedItem>>? =
        when (this) {
            is LegacyState.Success -> AppResult.Success(data.map { it.toHomeFeedItem() })
            is LegacyState.Error -> AppResult.Failure(toAppError())
            LegacyState.Loading -> null
        }
}
