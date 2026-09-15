package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.data.mapper.toDomain
import me.manga.kira.domain.model.sources.Source
import me.manga.kira.domain.repository.SourcesRepository
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.model.SourceCatalogSnapshot
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as LegacySourcesRepository

/**
 * [SourcesRepository] projection and serialized enablement over the shared Room source settings.
 *
 * Phase 7.x.sources rework. Translates the legacy Room entity (`SourcesEntity`) into the rework
 * `:domain` model ([Source]) via the mapper file `SourcesMappers.kt`. Enablement writes use
 * [SourcesDao] directly so persistence errors propagate. [LegacySourcesRepository] still owns
 * the read flow and routing surface
 * (`findRepoByHost` / `activeRepoFlow` / `getEnabledRepos`) — same posture as
 * [HistoryRepositoryImpl] / [UpdatesRepositoryImpl] / [ReadingStatisticsRepositoryImpl] /
 * [ReadingSessionRepositoryImpl]. (`repoTaps` / `getUrl` removed in Phase 9.x.repo.component
 * prune.cumulative — Task #415 — orphan-retired after the §243 inter-repository scan.)
 *
 * **SRP (contract §6)**: owns the source-settings projection and enablement commands. SQL and
 * atomic persistence live in [SourcesDao]. The seed (`saveSources`) and routing (`findRepoByHost`,
 * `getEnabledRepos`, `updateActiveByApi`) stay on the legacy facade and the
 * rework deliberately does not duplicate them — see [SourcesRepository] KDoc for the scoped-
 * surface rationale.
 *
 * **DIP (contract §6)**: the data layer depends on the existing legacy read facade and the
 * `:data:local` DAO. The [SourcesRepository] interface in `:domain` exposes neither Room nor entities.
 *
 * **Import-alias note** — both the rework interface and the legacy class share the simple name
 * `SourcesRepository`. The legacy class is imported with the `as LegacySourcesRepository` alias
 * to keep the constructor parameter type unambiguous and to make the strangler-fig boundary
 * visible in source. Same disambiguation trick as [UpdatesRepositoryImpl] /
 * [HistoryRepositoryImpl].
 *
 * **Why `observeSources` maps `allSources` (not `activeRepoFlow`)** — `allSources` returns every
 * row in the `sources` table regardless of enabled/disabled state, which is what the rework
 * screen needs (the screen renders disabled sources too — they're the ones with the `Switch`
 * in the off position). `activeRepoFlow` would only emit the single currently-active repo,
 * useless for a list screen. The rework deliberately depends on the read-only `allSources`
 * property; the DAO enablement writes flow through Room and re-emit on
 * `allSources` so the screen reflects the new state without extra plumbing.
 *
 * **Single-source writes** retain exact-name semantics without a new eligibility filter. Only
 * `isEnabled` changes; no entity round-trip or legacy exception-swallowing facade is involved.
 *
 * **Bulk writes** snapshot accepted catalog metadata before reading persisted rows, select exact
 * case-sensitive descriptor-language matches, then update every selected name in one SQL statement. Primary
 * language selection falls back only when there are no eligible primary rows. Operationally
 * non-working active sources remain eligible; executable-client availability is not a filter.
 *
 * **Admission and cancellation**: all three enablement methods share one mutex, acquired before
 * snapshotting and held through persistence. The last successful admitted conflicting command
 * wins. Cancellation/errors propagate and release admission; a cancellation racing a completed
 * statement can leave the whole change, never a committed prefix of one bulk statement. Catalog
 * publication is a separate boundary; commands do not include sources arriving after the snapshot.
 *
 * **Lifecycle**: `single` in Koin (per [SourcesRepository] KDoc). The upstream legacy
 * [LegacySourcesRepository] is also `single`; repository admission must be shared across callers,
 * including explicit-language and default-language commands from different ViewModels.
 *
 * **Threading**: Room owns query dispatch. The repository mutex defines enablement admission;
 * no ordering guarantee is inferred from Room's writer pool or from ViewModel scheduling.
 *
 * **Load-bearing fixes preserved**: the legacy `findRepoByHost` path used by the Coil image
 * interceptor (MEMORY: `project_yami_okhttp_fetcher`) and the `activeRepoFlow` used by Home /
 * Search / Manga details — ALL UNTOUCHED by this rework. The `:data` impl reaches into the
 * legacy facade for `allSources`; the legacy facade keeps serving
 * everything else verbatim.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster23.staleKdocSweep.cascade,
 * Task #479, 2026-05-28): one fulfilled-forecast citation appears
 * above:
 *  - Lines 73-77 ("the legacy `RepoSettingsViewModel.
 *    setLanguageEnabledDefault`... The rework lifts the mechanism
 *    here so the rework SourcesScreen + a future `Phase
 *    7.x.sources.swap` route can reproduce the auto-seed behavior
 *    verbatim"). FULFILLED — Phase 7.x.sources.swap (§305)
 *    re-pointed the onboarding `Screen.Sources` route to the rework
 *    adapter; Phase 7.x.reposettings.swap (§285) re-pointed
 *    `Screen.RepoSettings` to the rework `SourcesScreen` already;
 *    Phase 9.x.reposettings.legacyui.retire (§353) deleted the legacy
 *    `:shared` `RepoSettingsScreen.kt` UI; Phase
 *    9.x.sources.legacycomponents.retire (§356) dropped unreachable
 *    legacy components. The "future Phase 7.x.sources.swap route can
 *    reproduce the auto-seed behavior verbatim" forecast was
 *    fulfilled by §305 — the rework SourcesScreen now serves all
 *    three convergent route keys (Sources / RepoSettings /
 *    SourcesRework) through `setLanguageEnabledWithFallback`.
 *    HOWEVER — the legacy `:shared` [LegacySourcesRepository] facade
 *    (the `allSources` flow + `enableDisAbleSource` write surface +
 *    the routing surface `findRepoByHost` / `activeRepoFlow` /
 *    `getEnabledRepos` used by Home / Search / MangaDetails / the
 *    Coil interceptor) STILL EXISTS as the cell of truth that this
 *    impl delegates to via `legacy = get()` (verified at the
 *    constructor signature below — `private val legacy:
 *    LegacySourcesRepository`). The strangler-fig backbone holds;
 *    only the legacy consumer-side surfaces were retired across
 *    §§285 + 305 + 353 + 356. Mirror of §478 sources cluster +
 *    §445-477 partially-fulfilled-inversion precedent.
 * The SRP / DIP / import-alias / observeSources-not-activeRepoFlow /
 * setSourceEnabled-verbatim / setLanguageEnabled-snapshot-fan-out /
 * setLanguageEnabledWithFallback-fallback-pass / lifecycle /
 * threading / load-bearing-fixes-preserved sub-sections all stand
 * on their own merits past the §§285 + 305 + 353 + 356 fulfilled
 * landings. The SourcesRepositoryImpl remains LIVE as the canonical
 * strangler-fig delegate for the rework sources surface across all
 * three convergent route keys. Original §253-era prose preserved
 * verbatim per the audit-trail-preservation convention — the
 * citation is historical record of the design lineage including the
 * deferred-route-swap forecast that was subsequently fulfilled
 * across §§285 + 305 + 353 + 356.
 */
class SourcesRepositoryImpl(
    private val legacy: LegacySourcesRepository,
    // Sources Migration Phase 2: the catalog shows ONLY config-backed sources. The registry's
    // isConfigBacked(api) is true exactly for sources served by the generic config engine (a valid
    // engine="generic" stanza in the active document), so legacy-only sources are hidden from the UI. Language
    // bulk-toggles likewise only touch config-backed sources (never enable a hidden legacy source).
    private val sourceRegistry: SourceRegistry,
    // U2 (new-sources badge): the `new_sources_added` cell lives in the shared prefs facade —
    // the What's-New pipeline writes true; the Home tab strip observes; edit-sources clears.
    private val dataStore: DataStoreHelper,
    // (SourceRegistry retirement §2: a stanza with lifecycle="disabled" is HIDDEN from the picker,
    // not just force-disabled every sync — without the hide, a user could re-enable a killed source
    // each session. Since the MangaSource decoupling (2026-07) that read goes through the registry's
    // descriptor projection — the same validated document the catalog sync enforces — so this class
    // no longer takes the SourceUpdateManager directly.)
    // Direct enablement persistence shares the same singleton DAO used by the legacy read facade.
    private val sourcesDao: SourcesDao,
) : SourcesRepository {

    private val enablementMutex = Mutex()

    override fun observeHasNewSources(): Flow<Boolean> = dataStore.newSourcesFlow

    override suspend fun setHasNewSources(value: Boolean) {
        dataStore.setNewSources(value)
    }

    override fun observeSources(): Flow<List<Source>> =
        combine(legacy.allSources, sourceRegistry.catalog) { entities, catalog ->
            projectSources(entities, catalog)
        }

    override suspend fun setSourceEnabled(api: String, enabled: Boolean) {
        enablementMutex.withLock {
            sourcesDao.setEnabledByName(api, enabled)
        }
    }

    override suspend fun setLanguageEnabled(language: String, enabled: Boolean) {
        enablementMutex.withLock {
            val targets = eligibleSourcesSnapshot().filter { it.language == language }
            setSourcesEnabled(targets, enabled)
        }
    }

    override suspend fun setLanguageEnabledWithFallback(
        primary: String,
        fallback: String,
        enabled: Boolean,
    ) {
        enablementMutex.withLock {
            val snapshot = eligibleSourcesSnapshot()
            val targets =
                snapshot.filter { it.language == primary }.ifEmpty {
                    snapshot.filter { it.language == fallback }
                }
            setSourcesEnabled(targets, enabled)
        }
    }

    private suspend fun eligibleSourcesSnapshot(): List<Source> {
        val catalog = sourceRegistry.catalog.first()
        return projectSources(sourcesDao.getAllSourcesOnce(), catalog)
    }

    private fun projectSources(
        entities: List<SourcesEntity>,
        catalog: SourceCatalogSnapshot,
    ): List<Source> {
        val rows = entities.associateBy { it.name }
        // Share the accepted metadata/order projection with the picker; stale Room language must
        // not change a command's targets, even if catalog publication races the persisted-row read.
        return catalog.descriptors.mapIndexedNotNull { order, descriptor ->
            rows[descriptor.api]?.toDomain(descriptor, order)
        }
    }

    private suspend fun setSourcesEnabled(
        targets: List<Source>,
        enabled: Boolean,
    ) {
        if (targets.isNotEmpty()) {
            sourcesDao.setEnabledByNames(targets.map { it.api }, enabled)
        }
    }
}
