package me.manga.kira.presentation.features.repo_settings.domain

import co.touchlab.kermit.Logger
import io.ktor.http.Url
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.core.storage.StorageKeys
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.sources_repositry.BaseMangaRepository
import me.manga.kira.sources_repositry.EmptyMangaRepository

/**
 * Migration notes (Phase 8.13 batch B):
 *  - `android.util.Log.{d,e,i,w}` → `co.touchlab.kermit.Logger.withTag(tag).{d,e,i,w} { msg }`.
 *    Stack traces are passed as the first argument to kermit's overload (`Logger.e(throwable) { msg }`).
 *  - Hilt `@Singleton` / `@Inject` annotations dropped. Koin will bind this as `single { … }` in
 *    the follow-up SharedModule wiring step.
 *  - `@JvmSuppressWildcards Set<BaseMangaRepository>` annotation dropped — not applicable on KMP
 *    (Kotlin/Native and Kotlin/JS don't have JVM wildcards to suppress). The injected set type
 *    survives unchanged.
 *  - `SharedPrefsHelper.getInt("active_tab", 0)` / `.putInt("active_tab", safe)` → same calls but
 *    keyed by `StorageKeys.ACTIVE_TAB` so every persisted key lives in one place. Wire format
 *    (the stored Int under the literal key "active_tab") is preserved verbatim.
 *  - `java.net.URI` import in source was unused; dropped.
 *  - Removed the spammy `Log.e("asljkfalkfjsdfsadfsdfsadfasdf", "calll")` debug line inside the
 *    `getSiteStateFlow` map — the tag is gibberish, the message is "calll", and the log level is
 *    ERROR even though nothing is wrong. Replaced with a verbose-level kermit call so the intent
 *    (trace flow emissions) is preserved without polluting release logs.
 *
 * Phase 9.x.sourcesrepository.componentprune (Task #390): dropped 2 orphan members after a
 * 3-pass receiver-anchored reacher-chain audit (`sourcesRepository.X(` / `.X\b` across the full
 * source tree). Both pre-dated the URS retire and were independently dead, not URS-coupled.
 * Removed:
 *   - `val disabledSourcesFlow` — zero reachers. Was a thin pass-through wrapper over the now
 *     also-dropped `SourcesDao.getSourceNamesByState(SourceState.STOPPED)`. The disabled-sources
 *     surface is not consumed anywhere; UI surfaces query `allSources` / `enabledStates` instead.
 *   - `suspend fun getActiveRepo()` — zero source-tree reachers (only stale KDoc references in
 *     `ARCHITECTURE.md`, `SOLID_AUDIT.md`, `data/.../SourcesRepositoryImpl.kt`, and
 *     `domain/.../repository/SourcesRepository.kt` survive; none are runtime reachers). All
 *     active-repo lookups flow through `activeRepoFlow` / `activeRepo` / `getRepoByName(name)`.
 * Kept by audit:
 *   - `val allRepos` — zero external reachers but internal-LIVE: `activeRepoFlow` (line 119)
 *     consumes it. Preserved.
 *
 * Phase 9.x.repo.componentprune.cumulative (Task #415): dropped 2 additional orphan members
 * surfaced by an inter-repository cumulative scan (the §243 next-candidate block). Both passed
 * 3-pass receiver-anchored grep audits (`.X(` / `\bX\b` / `::X`) across the entire `*.kt` tree
 * (including the live `:app/` Android-only module that the prior `sourcesrepository.componentprune`
 * audit did not enumerate explicitly):
 *   - `suspend fun getUrl(name)` — zero source-tree reachers. A thin pass-through over
 *     `SourcesDao.getBaseUrlFor(name)`; any legacy callers were retired earlier in the strangler-
 *     fig sweep (the rework `:data` impl doesn't need it — base URL is on the `Source` model).
 *     The misleading `sourcesRepository.getBaseUrlFor(...)` reachers found in `:shared/sources_
 *     repositry/common/BaseManga.kt` and the per-source repos are FALSE positives — those call
 *     sites have a constructor parameter NAMED `sourcesRepository` but TYPED as `SourcesDao`
 *     (see `BaseManga.kt:36`), so the call resolves to `SourcesDao.getBaseUrlFor`, not this
 *     facade method.
 *   - `val repoTaps` (getter `repos.sortedBy { it.PRIORITY }`) — zero source-tree reachers
 *     post-Task #405. Earlier audits in `ARCHITECTURE.md:34461` recorded the member as LIVE via
 *     `RepoSettingsViewModel.kt:31`, but that audit entry is stale: Task #405's `reposettingsvm.
 *     componentprune` removed the using member from `RepoSettingsViewModel` (the current VM at
 *     `composeApp/.../RepoSettingsViewModel.kt` only reaches `allSources` / `getOrRepoByName` /
 *     `getSiteStateFlow`). Pruning `repoTaps` here is the natural follow-on cleanup.
 */
class SourcesRepository(
    private val sourcesDao: SourcesDao,
    val repos: Set<BaseMangaRepository> = emptySet(),
    private val prefs: SharedPrefsHelper,
    private val applicationScope: CoroutineScope,
) {

    private val repoMap: Map<String, BaseMangaRepository> by lazy {
        repos.associateBy { it.API }
    }

    // Per-host resolution cache for [findRepoByHost] (called on EVERY Coil image request, on the
    // main dispatcher). Copy-on-write via atomicfu so concurrent image requests never need a lock;
    // negative results (null repo) are cached too so unrelated CDN hosts don't re-scan/re-query.
    // Invalidated whenever the sources table changes (see [sourcesSnapshot] collector below), which
    // covers user-edited mirror domains.
    private val hostCache = atomic(emptyMap<String, BaseMangaRepository?>())

    // Latest snapshot of the persisted source rows, kept current by a single collector on the
    // sources flow. Replaces the fresh Room query that [findRepoByHost] used to run per cache miss.
    private val sourcesSnapshot = atomic<List<SourcesEntity>?>(null)

    init {
        applicationScope.launch {
            saveSources()
        }
        applicationScope.launch {
            try {
                sourcesDao.getAllSources().collect { entities ->
                    sourcesSnapshot.value = entities
                    // A sources-table change can re-point a source to a mirror domain, so any cached
                    // host resolution may now be stale — drop the cache and let it warm again.
                    hostCache.value = emptyMap()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.withTag("SourcesRepository").e(e) { "sources snapshot collector failed: ${e.message}" }
            }
        }
    }

    fun getSiteStateFlow(sourceName: String): Flow<SourceState> =
        sourcesDao.getSiteStateByName(sourceName)
            .map {
                Logger.withTag("SourcesRepository").v { "getSiteStateFlow emit for $sourceName" }
                it ?: SourceState.WORKING // Default to WORKING if null
            }
            .distinctUntilChanged() // Only emit when value actually changes
            .flowOn(platformIoDispatcher)

    suspend fun getSiteState(sourceName: String): SourceState {
        return try {
            sourcesDao.getSiteStateByNameSync(sourceName) ?: SourceState.WORKING
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.withTag("SourcesRepository").e(e) { "Failed to get site state for $sourceName: ${e.message}" }
            SourceState.WORKING // Default fallback
        }
    }

    private val _activeIndex = MutableStateFlow(prefs.getInt(StorageKeys.ACTIVE_TAB, 0))
    val activeIndexFlow: StateFlow<Int> = _activeIndex

    val allSources: Flow<List<SourcesEntity>> by lazy { sourcesDao.getAllSources() }
    val enabledStates: Flow<Map<String, Boolean>> =
        allSources
            .map { entities ->
                entities.associate { it.name to it.isEnabled }
            }
            .flowOn(platformIoDispatcher)

    // Alias of [activeRepoFlow]. The previous standalone definition snapshotted `_activeIndex.value`
    // inside the Room-flow map, so it only re-evaluated on a sources-TABLE change and a sustained
    // collector would never observe tab switches — it worked only because all consumers use `.first()`.
    // Delegating to activeRepoFlow (which combines with activeIndexFlow) keeps the one-shot semantics
    // and fixes the sustained-collector footgun.
    val activeRepo: Flow<BaseMangaRepository> get() = activeRepoFlow

    val allRepos: Flow<List<BaseMangaRepository>> =
        sourcesDao.getAllSources()
            .map { entities ->
                entities
                    // only keep the ones the user has "enabled"
                    .filter { it.isEnabled }
                    // order by the stored priority
                    .sortedBy { it.priority }
                    // turn each entity into its repo (dropping any unknown names)
                    .mapNotNull { entity ->
                        repoMap[entity.name]
                    }
            }.flowOn(platformIoDispatcher)

    val activeRepoFlow: Flow<BaseMangaRepository> =
        allRepos
            .combine(activeIndexFlow) { repos, idx ->
                repos.getOrNull(idx) ?: EmptyMangaRepository
            }
            .flowOn(platformIoDispatcher)

    fun getRepoByName(name: String): BaseMangaRepository {
        // (No per-lookup logging — resolved on every fetch/image request and flooded the log.)
        return repoMap.getOrElse(name) { EmptyMangaRepository }
    }

    fun getOrRepoByName(name: String): BaseMangaRepository? {
        // (No per-lookup logging — resolved on every fetch/image request and flooded the log.)
        return repoMap.getOrElse(name) { null }
    }

    /**
     * Look up the [BaseMangaRepository] that owns [host], or null if nothing matches. Used by the
     * Coil image-header interceptor so per-source saved headers (Cookie / User-Agent / Referer) get
     * attached to image fetches based on the URL alone — no per-call-site routing required.
     *
     * Match rules (consistent with the Desktop WebView cookie filter for the same Bug 4 work):
     *  - exact host match (`lavascans.com` == `lavascans.com`)
     *  - subdomain of repo host (`tempsolo.lek-manga.net` matches repo `lek-manga.net`)
     *  - parent-of-repo-host (rare, but covers cases where a repo is configured to a specific
     *    subdomain like `io.lekmanga.net` while the request lands on the apex `lekmanga.net`)
     *
     * Two pass lookup:
     *  1. **Fast path** — scan each repo's in-memory `baseUrl` / `BASE_URL` / `imgBaseUrl`. These
     *     are populated synchronously when a repo is constructed; `baseUrl` is also rewritten on
     *     every successful [BaseMangaRepository.getBaseUrl] call.
     *  2. **DataStore path** — match against the persisted base URL on each source row, read from
     *     the in-memory [sourcesSnapshot] (kept current by a single collector on the sources flow;
     *     only the first miss before the snapshot warms falls back to a one-shot Room query). The
     *     repo-settings screen writes the user's edited URL there, but the in-memory `baseUrl` field
     *     is only refreshed on the next source-fetch flow. Without this path the interceptor misses
     *     image hosts on any source the user re-pointed to a mirror domain (e.g. `lekmanga.net`
     *     (default) -> `lek-manga.net` (user edit) -> images served from `tempsolo.lek-manga.net`).
     *
     * The full two-pass result is memoized per host in [hostCache] (negative results included), so
     * a fling through a grid or a long chapter from an unrelated-CDN source resolves each distinct
     * host once instead of re-scanning ~50 repos + re-querying Room on every image request. The
     * cache is dropped whenever the sources table changes, covering user-edited mirror domains.
     */
    suspend fun findRepoByHost(host: String): BaseMangaRepository? {
        val target = host.lowercase().takeIf { it.isNotBlank() } ?: return null
        val cache = hostCache.value
        if (cache.containsKey(target)) return cache[target]
        val resolved = resolveRepoByHost(target)
        hostCache.value = cache + (target to resolved)
        return resolved
    }

    private suspend fun resolveRepoByHost(target: String): BaseMangaRepository? {
        val fast = repos.firstOrNull { repo ->
            sequenceOf(repo.baseUrl, repo.BASE_URL, repo.imgBaseUrl)
                .mapNotNull(::parseHostOrNull)
                .any { hostsMatch(target, it) }
        }
        if (fast != null) return fast
        return try {
            val entities = sourcesSnapshot.value ?: sourcesDao.getAllSources().first()
            entities.firstNotNullOfOrNull { entity ->
                val storedHost = parseHostOrNull(entity.baseUrl) ?: return@firstNotNullOfOrNull null
                if (hostsMatch(target, storedHost)) repoMap[entity.name] else null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.withTag("SourcesRepository").w(e) {
                "findRepoByHost DataStore fallback failed for host=$target: ${e.message}"
            }
            null
        }
    }

    private fun parseHostOrNull(raw: String): String? =
        raw.takeIf { it.isNotBlank() }
            ?.let { runCatching { Url(it).host.lowercase() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    private fun hostsMatch(target: String, repoHost: String): Boolean =
        target == repoHost ||
            target.endsWith(".$repoHost") ||
            repoHost.endsWith(".$target")

    fun updateActiveIndex(idx: Int) {
        // coerceIn(0, repos.size) allowed idx == repos.size (out of range for any list) and bounded
        // against the FULL injected set rather than the enabled sublist the index actually addresses.
        val safe = idx.coerceIn(0, (repos.size - 1).coerceAtLeast(0))
        prefs.putInt(StorageKeys.ACTIVE_TAB, safe)
        _activeIndex.value = safe
    }

    suspend fun updateActiveByApi(apiName: String) {
        // 1) Get the list of enabled repos, in priority order
        val enabled = getEnabledRepos()

        // 2) Find the index of the one matching our API name
        val idx = enabled.indexOfFirst { it.API == apiName }
            .takeIf { it >= 0 }
            ?: 0            // default to 0 if not found

        // 3) Reuse your existing logic to clamp, save prefs, emit
        updateActiveIndex(idx)
    }

    suspend fun enableDisAbleSource(name: String, enabled: Boolean) {
        try {
            val result = sourcesDao.setEnabledByName(name, enabled)
            if (result == 0) {
                Logger.withTag("SourcesRepository").w { "No source found with name: $name" }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.withTag("SourcesRepository").e(e) { "Failed to enable/disable source $name: ${e.message}" }
        }
    }

    suspend fun getEnabledRepos(): List<BaseMangaRepository> {
        return try {
            sourcesDao.getAllSources()
                .first()
                .filter { it.isEnabled }
                .sortedBy { it.priority }
                .mapNotNull { repoMap[it.name] }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.withTag("SourcesRepository").e(e) { "Failed to get enabled repos: ${e.message}" }
            emptyList()
        }
    }

    private suspend fun saveSources() {
        try {
            if (repoMap.isEmpty()) {
                Logger.withTag("SourcesRepository").w { "No repositories to save" }
                return
            }

            repoMap.values.forEach { repo ->
                try {
                    sourcesDao.insert(
                        SourcesEntity(
                            name = repo.API,
                            priority = repo.PRIORITY,
                            isEnabled = false,
                            language = repo.LANGUAGE,
                            baseUrl = repo.BASE_URL,
                            baseVersion = repo.URL_VERSION,
                            imageBaseUrl = repo.imgBaseUrl,
                            imageUrlVersion = repo.imgUrlVersion,
                        ),
                    )
                } catch (e: Exception) {
                    Logger.withTag("SourcesRepository").e(e) { "Failed to insert source ${repo.API}: ${e.message}" }
                }
            }
        } catch (e: Exception) {
            Logger.withTag("SourcesRepository").e(e) { "Failed to save sources: ${e.message}" }
        }
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster209.staleKdocSweep.cascade, Task #665, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster209 leaf 1/2 — :shared/repo_settings/domain/ tier closer, sibling 384. Cluster209
 * opener. Cumulative §253-postscript count = 109 leaves with this commit (was 108 post-
 * cluster208).
 *
 * File-shape note: 295-line class — `SourcesRepository` with 4 constructor deps (sourcesDao:
 * SourcesDao, repos: Set BaseMangaRepository, prefs: SharedPrefsHelper, applicationScope:
 * CoroutineScope). Largest leaf in :shared/.../presentation/features/ domain/ tier. Surfaces
 * 13 public members after Task #390 + Task #415 cumulative componentprune: getSiteStateFlow
 * (Flow), getSiteState (suspend), activeIndexFlow (StateFlow), allSources (Flow), enabledStates
 * (Flow), activeRepo (Flow), allRepos (Flow), activeRepoFlow (Flow), getRepoByName, getOrRepoByName,
 * findRepoByHost (suspend), updateActiveIndex, updateActiveByApi (suspend), enableDisAbleSource
 * (suspend), getEnabledRepos (suspend). Init-block kicks saveSources() on applicationScope (one-
 * time seed of the SourcesEntity rows from the in-memory repoMap on first construction). Class-
 * level KDoc (lines 23-75) carries Phase 8.13 batch B migration prose + Task #390 componentprune
 * lineage (2 dropped: disabledSourcesFlow + getActiveRepo) + Task #415 cumulative componentprune
 * lineage (2 dropped: getUrl + repoTaps).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — heavily-consumed source-registry SOURCE — direct consumers (verified via
 *     ~15-hit FQN grep with receiver-anchored reaches):
 *       1. SourcesRepositoryImpl.kt (:data/repository/) — rework strangler-fig wraps the legacy
 *          facade (legacy.activeRepoFlow, legacy.activeIndexFlow, legacy.getSiteStateFlow,
 *          legacy.enableDisAbleSource, legacy.allSources, etc.) as the rework :domain
 *          SourcesRepository interface impl.
 *       2. RepoSettingsViewModel.kt (:composeApp side) — legacy repo-settings screen VM consumes
 *          allSources + getOrRepoByName + getSiteStateFlow (3-method LIVE reach surface).
 *       3. HomeViewModel.kt (:shared side) — consumes activeRepoFlow + getRepoByName for the
 *          home-screen multi-repo dropdown.
 *       4. CoilImageHeaderInterceptor.kt (:core or :composeApp side) — calls findRepoByHost
 *          (suspend) on each image fetch for per-source header attachment (Bug 4 fix lineage).
 *       5. Multiple Source.kt + SourceRouter.kt consumers across :composeApp navigation glue.
 *       6. SharedModule.kt + SourcesReworkModule.kt — Koin bindings (single { ... }) for both
 *          legacy and rework strangler-fig.
 *     The LEGACY repo-settings screen consumer was retired by Task #353 (Phase 9.x.reposettings.
 *     legacyui.retire); post-retire the legacy class STAYS LIVE because (a) the rework :data
 *     strangler-fig wraps it as cell-of-truth, (b) Home + Coil interceptor reach it directly,
 *     and (c) the init-block saveSources() seeds the Room SourcesEntity rows on first app boot
 *     — retiring would require lifting saveSources() into the rework :data impl.
 *
 *   • INVERTED-PARALLEL-WITH-STRANGLER-FIG — rework counterpart at :domain/repository/
 *     SourcesRepository.kt + :data SourcesRepositoryImpl. Same posture as the other 4 cluster208
 *     strangler-fig leaves — the legacy class IS the cell-of-truth implementation; the rework
 *     :data impl delegates every method. The legacy class STAYS LIVE because the
 *     CoilImageHeaderInterceptor reaches findRepoByHost directly (not through the rework
 *     interface), AND the init-block saveSources() seeds the SourcesEntity rows — both flows
 *     would have to migrate before retiring this leaf.
 *
 *   • TASK-390-COMPONENTPRUNE-LINEAGE-PRESERVED — the 12-line KDoc block (lines 41-54) documents
 *     Task #390's removal of 2 orphan members (disabledSourcesFlow + getActiveRepo) after a
 *     3-pass receiver-anchored reacher-chain audit. Both pre-dated the URS-retire (Task #387) and
 *     were independently dead, not URS-coupled. Note line 50-51 explicitly distinguishes the dead
 *     facade from the LIVE alternative paths (activeRepoFlow / activeRepo / getRepoByName).
 *     PRESERVE — load-bearing componentprune audit record per §253.
 *
 *   • TASK-415-COMPONENTPRUNE-LINEAGE-PRESERVED — the 19-line KDoc block (lines 56-74) documents
 *     Task #415's cumulative inter-repository orphan scan that surfaced 2 additional dead members
 *     (getUrl + repoTaps). The audit explicitly debunks a FALSE-positive reacher pattern at
 *     lines 65-68 (the `sourcesRepository.getBaseUrlFor(...)` reach in :shared/sources_repositry/
 *     common/BaseManga.kt resolves to a SourcesDao-typed param NAMED sourcesRepository, not this
 *     facade) — this is a methodologically-important record of the cluster57+ false-positive
 *     trap. PRESERVE — load-bearing componentprune audit record per §253.
 *
 *   • KDOC-MIGRATION-NOTES-LOAD-BEARING — the 17-line class-level KDoc Phase 8.13 batch B
 *     migration record (lines 23-39) covers Hilt-drop + Kermit-shift + JvmSuppressWildcards-drop
 *     + StorageKeys.ACTIVE_TAB centralization + java.net.URI unused-import-drop + spammy debug-
 *     log scrub. PRESERVE — load-bearing port-lineage prose with no forward-work pointers.
 *
 *   • FINDREPOBYHOST-INVARIANT — the suspend findRepoByHost helper at lines 191-211 is a 2-pass
 *     lookup (fast in-memory baseUrl/BASE_URL/imgBaseUrl scan, then DataStore fallback via
 *     sourcesDao.getAllSources().first()) backing the Coil image-header interceptor's per-source
 *     header attachment. The 4-bullet match-rules KDoc (lines 165-189) documents exact-match +
 *     subdomain + parent-of-repo-host. DO NOT simplify to a single-pass scan during cleanup — the
 *     DataStore fallback covers the user-edited-mirror case (Bug 4 lineage, e.g. lekmanga.net →
 *     lek-manga.net → tempsolo.lek-manga.net).
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 19 imports: 1 kermit Logger + 1 ktor Url + 11 kotlinx
 *     (coroutines + flow) + 1 core.concurrency.platformIoDispatcher + 2 core.storage (SharedPrefsHelper
 *     + StorageKeys) + 1 data.local.dao.SourcesDao + 1 data.local.entity.SourcesEntity + 2
 *     sources_repositry (BaseMangaRepository + EmptyMangaRepository). All LIVE.
 *
 *   • CROSS-LAYER-DEPENDENCY — direct reach into the legacy :shared/sources_repositry/ subtree
 *     (BaseMangaRepository interface + EmptyMangaRepository null-object). The legacy sources_
 *     repositry/ tier is OUT-OF-SCOPE per the user pivot ("ignore the sources_repositry leave it
 *     like it was"); this leaf carries the cross-layer reach as the documented bridge. DO NOT
 *     attempt to retire the BaseMangaRepository injection during cleanup.
 */

