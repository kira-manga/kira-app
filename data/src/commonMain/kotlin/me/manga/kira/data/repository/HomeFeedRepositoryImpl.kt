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
import me.manga.kira.core.states.State as LegacyState
import me.manga.kira.data.mapper.classifyHomeThrowable
import me.manga.kira.data.mapper.toAppError
import me.manga.kira.data.mapper.toFeatured
import me.manga.kira.data.mapper.toHomeFeedItem
import me.manga.kira.data.mapper.toSiteState
import me.manga.kira.data.mapper.toSourceFilters
import me.manga.kira.data.mapper.toSourceTab
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.domain.repository.HomeFeedRepository
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources_repositry.BaseMangaRepository
import me.manga.kira.sources_repositry.pt.manhastro.ManhastroDadosStore
import me.manga.kira.domain.model.MangaItem as LegacyMangaItem
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as LegacySourcesRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Source-backed [HomeFeedRepository] strangler-fig implementation (Epic H2).
 *
 * SRP (contract §6): owns ONE rule — "route the active source to its legacy `:shared` feed methods
 * (`fetchMangaHomeF` / `fetchMoreManga` / `fetchPopularManga`), project the first terminal
 * `core.states.State` emission into a typed [AppResult], and surface the source tabs / active-index
 * / per-source site-state as [Flow]s". Source-routing itself (the registry of enabled repos, the
 * active-index persistence, the Room-backed site state) lives in the legacy [LegacySourcesRepository]
 * — this impl is a thin classifier + mapper on top of it, the same posture as
 * [MangaDetailsRepositoryImpl].
 *
 * **Temporary `:data` → `:shared` seam**: every collaborator below ([LegacySourcesRepository],
 * [BaseMangaRepository], [ManhastroDadosStore], [LegacyState], [LegacyMangaItem]) lives in `:shared`.
 * This is the strangler-fig boundary — the rework `:data` layer reaches into `:shared` for the
 * source-routing / per-source-parser machinery that hasn't been (and per user direction won't be)
 * ported. The `:domain` [HomeFeedRepository] interface is unaffected; the seam disappears only if
 * `sources_repositry/` ever relocates. Mirror of [SourcesRepositoryImpl] / [MangaDetailsRepositoryImpl].
 *
 * **Legacy method binding** (mirrors `MangaViewModel` — `:shared/.../presentation/common/viewmodel/
 * MangaViewModel.kt`):
 *  - tabs/index/state ← [LegacySourcesRepository.getEnabledRepos] / `activeIndexFlow` /
 *    `getSiteStateFlow` (lines 253, 112, 93).
 *  - select ← `updateActiveIndex` / `updateActiveByApi` (lines 223, 229) + [ManhastroDadosStore.clear]
 *    (replicating `MangaViewModel.onTabSelected`, line 274-280).
 *  - home/more/featured ← the active [BaseMangaRepository]'s `fetchMangaHomeF` / `fetchMoreManga` /
 *    `fetchPopularManga` (lines 49, 52, 53). Only `fetchHome` calls `initSite()` (then `getBaseUrl()`);
 *    `fetchMore` calls neither and `fetchFeatured` calls `getBaseUrl()` only — native parity, where
 *    `MangaViewModel.startHomeFetch` is the sole `initSite()` caller and `getMoreManga` /
 *    `getPopularManga` rely on the home fetch's already-hydrated headers.
 *  - filters ← `sortTypes` / `allGenres` (lines 45, 46), matching `MangaViewModel.refreshSearchSetting`.
 *
 * **`ManhastroDadosStore.clear()` on tab/source switch**: `MangaViewModel.onTabSelected` clears the
 * Manhastro in-memory home cache before reloading so a stale source's cached entries can't bleed
 * into the next source's grid (locked decision H-§77-(3)). Replicated in [selectTab] / [selectSource]
 * here so the behaviour survives the migration even though the rework VM (H3) owns the reload.
 *
 * **Pagination state**: `MangaViewModel` tracked `currentPage` + the accumulated `mangaItems` list
 * on the VM. This impl holds the same two pieces of state so [fetchMore] can pass the accumulated
 * list to the legacy `fetchMoreManga(page, currentItems)` (some sources append to it, some ignore
 * it — legacy `getMoreManga` passed it verbatim). [fetchHome] with `reset = true` clears both.
 *
 * Error classification: reuses the shared `:data` helpers in `HomeMappers.kt`
 * ([LegacyState.Error.toAppError] for `State.Error` emissions; [classifyHomeThrowable] for
 * thrown exceptions) — the same heuristics [MangaDetailsRepositoryImpl] uses, so the surfaced
 * `AppError` buckets line up across the whole rework boundary.
 *
 * Cancellation: [CancellationException] propagates unchanged (structured-concurrency invariant).
 * Unknown active source (the legacy `activeRepo` flow yields `EmptyMangaRepository`, whose feed
 * methods emit empty `State.Success`) surfaces as an empty success — the same observable behaviour
 * the legacy Home screen had for a misconfigured source set.
 */
class HomeFeedRepositoryImpl(
    private val sourcesRepository: LegacySourcesRepository,
    private val dadosStore: ManhastroDadosStore,
    private val dispatchers: DispatcherProvider,
    private val sourceRegistry: SourceRegistry,
) : HomeFeedRepository {

    /** Accumulated feed for the active LEGACY source — fed to `fetchMoreManga(page, currentItems)`. */
    private var accumulated: List<LegacyMangaItem> = emptyList()

    /** Accumulated feed for the active GENERIC (config-backed) source — the engine pages by page number. */
    private var genericAccumulated: List<HomeFeedItem> = emptyList()

    /**
     * Guards every read/write of [accumulated] / [genericAccumulated] / [accumulatorGeneration]
     * (mobile hardening 2026-07-04). The fields are mutated from BOTH the IO pool (fetchHome/
     * fetchMore run under `withContext(dispatchers.io)`) and the caller's dispatcher (selectTab/
     * selectSource clear on main), and the VM launches a fresh coroutine per intent, cancelling the
     * previous fetch WITHOUT joining — so plain vars had no happens-before edges and the
     * check-then-write around the generation token could interleave with a clear (TOCTOU) or with
     * a sibling fetch under the same generation (lost write-back). Discipline: capture generation
     * (+ base snapshot) under the lock, run the NETWORK CALL OUTSIDE the lock (it can take seconds;
     * holding it would freeze tab taps behind a hung fetch), then re-check the generation and merge
     * against the CURRENT accumulator back under the lock.
     */
    private val accumulatorMutex = Mutex()

    /**
     * Monotonic generation token, bumped on every accumulator clear ([selectTab] / [selectSource] /
     * `fetchHome(reset)`). A fetch captures it at the start and only writes its terminal result back
     * if the token is unchanged — so an in-flight fetch for a now-switched-away source can't repopulate
     * the accumulator after the switch cleared it (cross-source feed bleed, the hazard the
     * `ManhastroDadosStore.clear()` comment guards against). All accesses go through
     * [accumulatorMutex]; the check and the write-back happen atomically under it.
     */
    private var accumulatorGeneration: Int = 0

    override fun observeSourceTabs(): Flow<List<SourceTab>> =
        sourcesRepository.allSources.map { entities ->
            // Sources Migration Phase 5/6: only config-backed sources are active/user-facing — a legacy
            // source never appears as a Home tab even if its row is enabled in the DB.
            // MangaSource decoupling (2026-07): the tab is built from the row + config descriptor —
            // a config-only source (no compiled BaseMangaRepository) appears like any other.
            entities
                .filter { it.isEnabled && sourceRegistry.isConfigBacked(it.name) }
                .sortedBy { it.priority }
                .map { entity -> entity.toSourceTab(descriptor = sourceRegistry.descriptor(entity.name)) }
        }

    // Index-space alignment (2026-07 audit): the tab strip is the FILTERED space (enabled ∧
    // config-backed) while the legacy activeIndexFlow/activeRepoFlow index over ALL enabled rows.
    // Any enabled non-config row (pre-sync install, offline first boot) shifted the spaces — tab N
    // highlighted one source while the fetch used another. Both directions are now keyed by API:
    // the highlight is the ACTIVE repo's position in the filtered tab list (falling back to 0,
    // which matches activeRepo()'s first-config-backed substitution), and a tap resolves ITS tab's
    // api before persisting (updateActiveByApi stores the legacy-space index for that api, so the
    // persisted format is unchanged). When the spaces align — the steady state after the config
    // sync's force-disable — both paths behave exactly as before.
    override fun observeActiveTabIndex(): Flow<Int> =
        combine(observeSourceTabs(), sourcesRepository.activeApiFlow) { tabs, activeApi ->
            // Falling back to 0 matches [activeSource]'s first-config-backed substitution, so the
            // highlighted pill is always the source the feed actually fetches from.
            tabs.indexOfFirst { it.api == activeApi }.takeIf { it >= 0 } ?: 0
        }

    override fun observeSiteState(api: String): Flow<SiteState> =
        sourcesRepository.getSiteStateFlow(api).map { it.toSiteState() }

    override suspend fun selectTab(index: Int) {
        val api = observeSourceTabs().first().getOrNull(index)?.api
        if (api != null) {
            sourcesRepository.updateActiveByApi(api)
        } else {
            // No tab at that index (transient empty strip) — keep the legacy clamp behavior.
            sourcesRepository.updateActiveIndex(index)
        }
        dadosStore.clear()
        clearAccumulators()
    }

    override suspend fun selectSource(api: String) {
        sourcesRepository.updateActiveByApi(api)
        dadosStore.clear()
        clearAccumulators()
    }

    private suspend fun clearAccumulators() = accumulatorMutex.withLock { clearAccumulatorsLocked() }

    /** Must be called with [accumulatorMutex] held — keeps the triple write + bump atomic as a group. */
    private fun clearAccumulatorsLocked() {
        accumulated = emptyList()
        genericAccumulated = emptyList()
        accumulatorGeneration++
    }

    override suspend fun fetchHome(reset: Boolean): AppResult<List<HomeFeedItem>> =
        withContext(dispatchers.io) {
            // Reset + generation capture are ONE atomic step (see accumulatorMutex KDoc).
            val generation =
                accumulatorMutex.withLock {
                    if (reset) clearAccumulatorsLocked()
                    accumulatorGeneration
                }
            val active = when (val r = activeSourceResult()) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return@withContext r // classify a thrown active-source resolution
            }
            // Config-backed source → the generic engine (page 1), which returns rich HomeFeedItems
            // incl. recentChapters. Else → unchanged legacy path.
            if (sourceRegistry.isConfigBacked(active.api)) {
                sourceRegistry.get(active.api)?.let { client ->
                    val result = client.home(1)
                    // Discard the write-back if the accumulator was cleared mid-fetch (source switch /
                    // refresh) — check and write are atomic under the lock (2026-07 audit TOCTOU).
                    if (result is AppResult.Success) {
                        accumulatorMutex.withLock {
                            if (generation == accumulatorGeneration) genericAccumulated = result.value
                        }
                    }
                    return@withContext result
                }
            }
            val repo = when (val r = active.legacyRepoOrFailure()) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return@withContext r
            }
            collectTerminal {
                repo.initSite()
                repo.fetchMangaHomeF(repo.getBaseUrl())
            }.also { result ->
                if (result is AppResult.Success) {
                    accumulatorMutex.withLock {
                        if (generation == accumulatorGeneration) accumulated = result.value.toLegacySnapshot()
                    }
                }
            }
        }

    override suspend fun fetchMore(page: Int): AppResult<List<HomeFeedItem>> =
        withContext(dispatchers.io) {
            // Capture the generation AND the base snapshot together, atomically (the base feeds the
            // legacy source's fetchMoreManga and the stale-path return view).
            val (generation, base) =
                accumulatorMutex.withLock { accumulatorGeneration to genericAccumulated }
            val active = when (val r = activeSourceResult()) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return@withContext r
            }
            // Config-backed source: the engine pages by page number; accumulate so the contract still returns
            // the full running list (matching the legacy fetchMoreManga(page, currentItems) shape).
            if (sourceRegistry.isConfigBacked(active.api)) {
                sourceRegistry.get(active.api)?.let { client ->
                    return@withContext when (val result = client.home(page)) {
                        is AppResult.Success -> {
                            // Dedup by url: a no-op for properly-paginating sources, but it keeps a source
                            // whose "home" endpoint isn't paginated (e.g. Mangabuddy's fixed latest feed)
                            // from accumulating duplicate rows when the UI requests further pages.
                            val merged =
                                accumulatorMutex.withLock {
                                    if (generation == accumulatorGeneration) {
                                        // Merge against the CURRENT accumulator (not the pre-fetch
                                        // capture): a sibling page under the same generation may
                                        // have committed while this fetch was on the network —
                                        // merging the stale base would silently drop its pages
                                        // (2026-07 audit lost-write-back).
                                        val m = (genericAccumulated + result.value).distinctBy { it.url }
                                        genericAccumulated = m
                                        m
                                    } else {
                                        // Cleared mid-fetch (source switch / refresh): don't write
                                        // back — but still return this page's merged view to the
                                        // caller that asked (pre-existing contract).
                                        (base + result.value).distinctBy { it.url }
                                    }
                                }
                            AppResult.Success(merged)
                        }
                        is AppResult.Failure -> result
                    }
                }
            }
            val repo = when (val r = active.legacyRepoOrFailure()) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return@withContext r
            }
            val snapshot = accumulatorMutex.withLock { accumulated }
            collectTerminal {
                repo.fetchMoreManga(page, snapshot)
            }.also { result ->
                // Write the source's returned (full accumulated) list back, mirroring fetchHome, so
                // the NEXT page is handed the correct running snapshot. Legacy sources implement
                // fetchMoreManga(page, currentItems) as `currentItems + newItems` (the full list), so
                // without this write-back page 3+ kept re-merging from the stale page-1 snapshot.
                // Discard if the accumulator was cleared mid-fetch (cross-source bleed).
                if (result is AppResult.Success) {
                    accumulatorMutex.withLock {
                        if (generation == accumulatorGeneration) accumulated = result.value.toLegacySnapshot()
                    }
                }
            }
        }

    override suspend fun fetchFeatured(): AppResult<List<FeaturedManga>> =
        withContext(dispatchers.io) {
            val active = when (val r = activeSourceResult()) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return@withContext r
            }
            if (sourceRegistry.isConfigBacked(active.api)) {
                sourceRegistry.get(active.api)?.let { return@withContext it.featured(1) }
            }
            val repo = when (val r = active.legacyRepoOrFailure()) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return@withContext r
            }
            try {
                val terminal = repo.fetchPopularManga(repo.getBaseUrl())
                    .awaitTerminalState()
                when (terminal) {
                    is LegacyState.Success -> AppResult.Success(terminal.data.map { it.toFeatured() })
                    is LegacyState.Error -> AppResult.Failure(terminal.toAppError())
                    LegacyState.Loading -> error("Filtered above")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppResult.Failure(classifyHomeThrowable(t))
            }
        }

    override suspend fun loadSourceFilters(): AppResult<List<SourceFilter>> = withContext(dispatchers.io) {
        val active = when (val r = activeSourceResult()) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return@withContext r
        }
        // Config-driven filters (2026-07): a config-backed source's filters come from its VALIDATED
        // stanza (descriptor projection, ordered as authored) — never from a legacy repo, even when
        // a compiled one still ships beside the config. A legacy source adapts sortTypes/allGenres
        // into the same shape. Never a failure — an empty filter set is the honest answer.
        AppResult.Success(
            if (sourceRegistry.isConfigBacked(active.api)) {
                sourceRegistry.descriptor(active.api)?.filters.orEmpty()
            } else {
                active.legacyRepo?.toSourceFilters().orEmpty()
            },
        )
    }

    /**
     * The resolved active source: its stable [api] plus the compiled legacy repo when one exists
     * ([legacyRepo] is null for a config-only source — every generic-path consumer keys on [api];
     * the legacy fetch paths guard on the repo).
     */
    private data class ActiveSource(
        val api: String,
        val legacyRepo: BaseMangaRepository?,
    )

    private data class ActiveSourceLookup(
        val source: ActiveSource?,
        val hasConfigBackedSources: Boolean,
    )

    /**
     * Resolve the currently active source (MangaSource decoupling, 2026-07 — api-string space).
     *
     * Sources Migration Phase 5/6: the active source must be config-backed. The persisted api wins
     * when its row is enabled ∧ config-backed; otherwise substitute the first config-backed+enabled
     * row (priority order — tab 0) so the home feed never fetches from a legacy source. (The config
     * sync force-disables legacy rows, so in steady state the persisted api is already config-only;
     * this is the defensive floor.) Catastrophic-floor parity: when NO config-backed source exists
     * at all (bundled document rejected), fall back to the legacy active repo exactly as before.
     */
    private suspend fun activeSource(): ActiveSourceLookup {
        val persisted = sourcesRepository.activeApiFlow.value
        val sourceRows = sourcesRepository.allSources.first()
        val configBackedRows = sourceRows
            .filter { sourceRegistry.isConfigBacked(it.name) }
            .sortedBy { it.priority }
        val enabledRows = configBackedRows.filter { it.isEnabled }
        val chosen = enabledRows.firstOrNull { it.name == persisted } ?: enabledRows.firstOrNull()
        if (chosen != null) {
            return ActiveSourceLookup(
                source = ActiveSource(
                    api = chosen.name,
                    legacyRepo = sourcesRepository.getOrRepoByName(chosen.name),
                ),
                hasConfigBackedSources = true,
            )
        }
        if (configBackedRows.isNotEmpty()) {
            return ActiveSourceLookup(source = null, hasConfigBackedSources = true)
        }
        val legacyActive = sourcesRepository.activeRepo.first()
        val fallback = if (legacyActive.API.isNotBlank()) {
            ActiveSource(api = legacyActive.API, legacyRepo = legacyActive)
        } else {
            null
        }
        return ActiveSourceLookup(
            source = fallback,
            hasConfigBackedSources = configBackedRows.isNotEmpty(),
        )
    }

    /**
     * [activeRepo] wrapped so a thrown active-source resolution (e.g. a Room read error) is classified
     * into an [AppResult.Failure] rather than escaping raw — preserving the pre-flip behavior, where
     * the `activeRepo()` call sat inside each method's error-classifying try/catch.
     * [CancellationException] still propagates.
     *
     * When config-backed rows exist but none is enabled, resolution is surfaced as a typed
     * [AppResult.Failure] instead of letting the [EmptyMangaRepository] null-object render a silent
     * blank Home. A missing/unusable catalog remains an unexpected failure so the activation UI
     * cannot mask configuration faults.
     */
    private suspend fun activeSourceResult(): AppResult<ActiveSource> = try {
        val lookup = activeSource()
        if (lookup.source != null) {
            AppResult.Success(lookup.source)
        } else if (lookup.hasConfigBackedSources) {
            AppResult.Failure(
                AppError.Validation.NoEnabledSources(),
            )
        } else {
            AppResult.Failure(
                AppError.Unexpected("No usable source configuration is available"),
            )
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        AppResult.Failure(classifyHomeThrowable(t))
    }

    /** The legacy fetch paths need a compiled repo; a config-only api can never reach them. */
    private fun ActiveSource.legacyRepoOrFailure(): AppResult<BaseMangaRepository> =
        legacyRepo?.let { AppResult.Success(it) }
            ?: AppResult.Failure(AppError.Unexpected("Unknown source api=$api (no compiled legacy repo)"))

    /**
     * Run a legacy `Flow<State<List<MangaItem>>>`-producing block, drop the `Loading` emissions,
     * and project the first terminal `State` into an [AppResult] of mapped [HomeFeedItem]s.
     */
    private suspend inline fun collectTerminal(
        block: () -> Flow<LegacyState<List<LegacyMangaItem>>>,
    ): AppResult<List<HomeFeedItem>> =
        try {
            when (val terminal = block().awaitTerminalState()) {
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
 * Reconstruct a minimal legacy [LegacyMangaItem] snapshot from the mapped [HomeFeedItem]s, so the
 * accumulated list can be handed back to the legacy `fetchMoreManga(page, currentItems)`. Only the
 * fields the sources actually inspect for de-dup/append are reproduced (api/language/title/url/
 * imageUrl/rating/genres); chapter chips and bookmark state are not part of the more-page contract.
 */
private fun List<HomeFeedItem>.toLegacySnapshot(): List<LegacyMangaItem> = map { item ->
    LegacyMangaItem(
        api = item.api,
        language = item.language,
        title = item.title,
        url = item.url,
        imageUrl = item.coverUrl,
        rating = item.rating,
        chapters = null,
        genres = item.genres,
    )
}
