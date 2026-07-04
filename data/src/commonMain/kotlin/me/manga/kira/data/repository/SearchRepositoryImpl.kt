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
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.states.State as LegacyState
import me.manga.kira.data.mapper.classifyHomeThrowable
import me.manga.kira.data.mapper.searchTypeOf
import me.manga.kira.data.mapper.toAppError
import me.manga.kira.data.mapper.toHomeFeedItem
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SearchMode
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
 * SRP (contract §6): owns ONE rule — "build the legacy `SearchType` from the rework search axes,
 * dispatch it onto the active source's `fetchSearchDataF` for single-source search, and fan the
 * plain-text query out across all enabled sources merging per-repo results for the aggregated
 * tab". Source-routing + the per-source search-URL shape live in the legacy `:shared`
 * [BaseMangaRepository] / [LegacySourcesRepository]; this impl is a thin builder + classifier on top.
 *
 * **Temporary `:data` → `:shared` seam**: same boundary as [HomeFeedRepositoryImpl] —
 * [LegacySourcesRepository], [BaseMangaRepository], [SearchType], [LegacyState], [LegacyMangaItem]
 * all live in `:shared`. The `:domain` [SearchRepository] interface never sees [SearchType]
 * (locked decision H-§87 — the rework [SearchMode] + sort/genres are translated to [SearchType]
 * inside `searchTypeOf`).
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
        mode: SearchMode,
        sort: String?,
        genres: List<String>,
    ): AppResult<List<HomeFeedItem>> = withContext(dispatchers.io) {
        try {
            val repo = activeConfigBackedRepo()
            // Config-backed source → the generic engine (query search, page 1), with its own legacy
            // fallback — but ONLY for plain-text search. The MangaSourceClient contract carries no
            // sort/genre axes, so a SORT/GENRES request (or a NORMAL one that still carries
            // sort/genres) must drop to the legacy `fetchSearchDataF` path, which honours the
            // source's real sortTypes/allGenres support (and naturally yields plain results for
            // sources that expose none) instead of silently discarding the filters.
            val isPlainTextSearch = mode == SearchMode.NORMAL && sort == null && genres.isEmpty()
            if (isPlainTextSearch && sourceRegistry.isConfigBacked(repo.API)) {
                sourceRegistry.get(repo.API)?.let { return@withContext it.search(query, page = 1) }
            }
            val searchType = searchTypeOf(query = query, mode = mode, sort = sort, genres = genres)
            when (val terminal = repo.fetchSearchDataF(searchType).awaitTerminalState()) {
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

    /**
     * The active source, guaranteed config-backed (Sources Migration Phase 5/6). If a persisted active
     * index resolves to a legacy source (e.g. a pre-migration install), fall back to the first
     * config-backed+enabled source so single-source search never targets a legacy source. (The config sync
     * force-disables legacy rows, so in steady state the active repo is already config-backed.)
     */
    private suspend fun activeConfigBackedRepo(): BaseMangaRepository {
        val active = sourcesRepository.activeRepo.first()
        if (sourceRegistry.isConfigBacked(active.API)) return active
        return sourcesRepository.getEnabledRepos().firstOrNull { sourceRegistry.isConfigBacked(it.API) } ?: active
    }

    override fun searchAllRepos(query: String): Flow<Map<String, AppResult<List<HomeFeedItem>>?>> =
        flow {
            // Snapshot enabled repos (cheap — just the registered repo objects; base-URL warming
            // happens below). LinkedHashMap so the per-source sections render in enabled order.
            // Sources Migration Phase 5/6: search-all only fans out over config-backed sources — a
            // legacy source is never searched even if its DB row is enabled.
            val repos = sourcesRepository.getEnabledRepos().filter { sourceRegistry.isConfigBacked(it.API) }

            // Seed every enabled repo as `null` (loading) and emit immediately, so the UI shows a
            // per-source spinner section up front instead of a misleading empty "no results" panel
            // while the fan-out (base-URL warm + initSite + first fetch) is still running.
            val accumulated = linkedMapOf<String, AppResult<List<HomeFeedItem>>?>()
            repos.forEach { accumulated[it.API] = null }
            emit(accumulated.toMap())

            // Warm each repo's base URL up-front (legacy parity: HomeViewModel.fetchAllSearchResults
            // used `.onEach { it.getBaseUrl() }`). A single source throwing during warm-up must not
            // cancel the whole fan-out — guard it (rethrowing CancellationException) so the failing
            // source still surfaces as its own per-api Failure below.
            repos.forEach { repo ->
                try {
                    repo.getBaseUrl()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    // Swallowed here; the per-repo flow below re-attempts and classifies the failure.
                }
            }

            val perRepoFlows = repos.map { repo ->
                val client = if (sourceRegistry.isConfigBacked(repo.API)) sourceRegistry.get(repo.API) else null
                val repoFlow = if (client != null) {
                    // Config-backed source (Azora) → the generic engine (with its own legacy fallback). One
                    // terminal emission per repo, same (api, AppResult) shape as the legacy branch.
                    flow { emit(repo.API to client.search(query, page = 1)) }
                } else {
                    flow {
                        repo.initSite()
                        repo.fetchSearchDataF(SearchType.Normal(query)).collect { state ->
                            // Skip the legacy Loading state: the repo keeps its seeded `null` (loading)
                            // marker until a terminal Success/Error lands, so a still-fetching source
                            // keeps its spinner instead of flashing an empty-success "no results".
                            state.toResultOrNull()?.let { emit(repo.API to it) }
                        }
                    }
                }
                // Independently catch-classify each repo: a throwing source (initSite/fetch/legacy
                // flow that throws instead of emitting State.Error) surfaces as its own per-api
                // Failure row instead of cancelling the whole fan-out (see class KDoc).
                repoFlow
                    .catch { t ->
                        if (t is CancellationException) throw t
                        emit(repo.API to AppResult.Failure(classifyHomeThrowable(t)))
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
