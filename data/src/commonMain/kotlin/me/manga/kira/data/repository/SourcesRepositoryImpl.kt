package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.manga.kira.data.mapper.toDomain
import me.manga.kira.domain.model.sources.Source
import me.manga.kira.domain.repository.SourcesRepository
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository as LegacySourcesRepository

/**
 * [SourcesRepository] strangler-fig delegate over the legacy `:shared` [LegacySourcesRepository].
 *
 * Phase 7.x.sources rework. Translates the legacy Room entity (`SourcesEntity`) into the rework
 * `:domain` model ([Source]) via the mapper file `SourcesMappers.kt`, then forwards the call to
 * the underlying legacy facade. The legacy [LegacySourcesRepository] remains the cell of truth
 * for the Room queries + transaction boundaries + the routing surface
 * (`findRepoByHost` / `activeRepoFlow` / `getEnabledRepos`) — same posture as
 * [HistoryRepositoryImpl] / [UpdatesRepositoryImpl] / [ReadingStatisticsRepositoryImpl] /
 * [ReadingSessionRepositoryImpl]. (`repoTaps` / `getUrl` removed in Phase 9.x.repo.component
 * prune.cumulative — Task #415 — orphan-retired after the §243 inter-repository scan.)
 *
 * **SRP (contract §6)**: owns ONE rule — "translate between rework [Source] and the legacy
 * `SourcesEntity` Room entity, then forward the call to the legacy [LegacySourcesRepository]".
 * Query semantics (the DAO's `SELECT * FROM sources` for `allSources`,
 * `UPDATE sources SET isEnabled = :enabled WHERE name = :name` for `setEnabledByName`) live in
 * the legacy DAO. The seed (`saveSources`) and the routing surface (`findRepoByHost`,
 * `getEnabledRepos`, `updateActiveByApi`) stay on the legacy facade and the
 * rework deliberately does not duplicate them — see [SourcesRepository] KDoc for the scoped-
 * surface rationale.
 *
 * **DIP (contract §6)**: depends on the legacy [LegacySourcesRepository] type because it's the
 * only vendor for the `sources` table reads/writes today. The dependency is structurally at the
 * strangler-fig boundary — the rework `:data` layer is allowed to reach into `:shared` for
 * cross-cutting persistence that hasn't been ported yet. The [SourcesRepository] interface in
 * `:domain` is unaffected either way.
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
 * property; the write-side toggle (`enableDisAbleSource`) flows through Room and re-emits on
 * `allSources` so the screen reflects the new state without extra plumbing.
 *
 * **Why `setSourceEnabled` forwards verbatim (no entity round-trip)** — the legacy
 * `enableDisAbleSource(name: String, enabled: Boolean)` takes the API string and the target
 * value directly; the rework's [Source.api] equals the entity's `name` column (the legacy
 * `saveSources` seeds the row with `name = repo.API`). No mapping needed, no entity to
 * reconstruct.
 *
 * **Why `setLanguageEnabled` snapshots via `.first()` and fan-outs through `setSourceEnabled`**
 * — the legacy facade exposes no language-bulk method (the legacy onboarding's
 * `RepoSettingsViewModel.toggleLanguage` does this same fan-out on the VM side, iterating over
 * the per-language source set). The rework lifts that fan-out to the `:data` layer so the VM
 * stays free of repository-shape leakage. The snapshot is a one-shot `.first()` on the
 * `allSources` flow — cheap (Room caches the query) and correct (the upstream flow re-emits
 * after each per-source write so the screen converges on the bulk result). `language` matching
 * is a case-sensitive `==` against the entity's `language` column, matching the legacy's
 * convention (the legacy filter is also `==` on the same column).
 *
 * **Why `setLanguageEnabledWithFallback` mirrors `setLanguageEnabled` plus a fallback pass**
 * — Phase 7.x.sources.onboardingseed. The legacy onboarding step 3
 * (`composeApp/.../onboarding/sources/SourcesScreen.kt:124-127`) fires a
 * `LaunchedEffect(userLanguageCode) { repoSettingsViewModel.setLanguageEnabledDefault
 * ("($tag)", true) }` whose body lives at the legacy `RepoSettingsViewModel.
 * setLanguageEnabledDefault` (snapshot, filter primary, fallback-filter on `"(EN)"` when
 * primary is empty, fan out). The rework lifts the mechanism here so the rework SourcesScreen
 * + a future `Phase 7.x.sources.swap` route can reproduce the auto-seed behavior verbatim.
 * The fallback is a method parameter (not a hard-coded EN constant) so the use case owns the
 * policy and the data layer stays neutral.
 *
 * **Lifecycle**: `single` in Koin (per [SourcesRepository] KDoc). The upstream legacy
 * [LegacySourcesRepository] is `single` (declared by `SharedModule`); a `factory` here would
 * resubscribe `allSources` on each resolution — wasteful for a read-mostly surface shared
 * across the app's lifetime.
 *
 * **Threading**: no explicit dispatcher pinning. The legacy `SourcesDao` Room methods emit /
 * suspend on the IO context (per the legacy facade's `.flowOn(IODispatcher)` on `allSources`);
 * the rework's `map`/`first`/`toDomain` operators are pure transforms on whatever dispatcher
 * the upstream emits on. `setLanguageEnabled`'s per-source fan-out runs on the caller's
 * coroutine (the VM's `viewModelScope.launch`); Room serialises the writes internally.
 *
 * **Load-bearing fixes preserved**: the legacy `findRepoByHost` path used by the Coil image
 * interceptor (MEMORY: `project_yami_okhttp_fetcher`) and the `activeRepoFlow` used by Home /
 * Search / Manga details — ALL UNTOUCHED by this rework. The `:data` impl reaches into the
 * legacy facade for ONLY `allSources` + `enableDisAbleSource`; the legacy facade keeps serving
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
    // SourceRegistry retirement §2 (completed by the 2026-07 audit): a stanza with
    // lifecycle="disabled" is HIDDEN from the picker, not just force-disabled every sync — without
    // the hide, a user could re-enable a killed source each session. The active config document is
    // the authority (same one the catalog sync enforces).
    private val updateManager: SourceUpdateManager,
) : SourcesRepository {

    /** apis whose active config stanza declares `lifecycle="disabled"` — hidden and never bulk-toggled. */
    private fun lifecycleDisabledApis(): Set<String> =
        updateManager
            .activeDocument()
            .sources
            .filter { it.lifecycle == "disabled" }
            .mapTo(mutableSetOf()) { it.api }

    override fun observeHasNewSources(): Flow<Boolean> = dataStore.newSourcesFlow

    override suspend fun setHasNewSources(value: Boolean) {
        dataStore.setNewSources(value)
    }

    override fun observeSources(): Flow<List<Source>> =
        legacy.allSources.map { entities ->
            val hidden = lifecycleDisabledApis()
            entities
                .filter { sourceRegistry.isConfigBacked(it.name) && it.name !in hidden }
                .map { it.toDomain() }
        }

    override suspend fun setSourceEnabled(api: String, enabled: Boolean) {
        legacy.enableDisAbleSource(api, enabled)
    }

    override suspend fun setLanguageEnabled(language: String, enabled: Boolean) {
        val hidden = lifecycleDisabledApis()
        val snapshot = legacy.allSources.first()
        snapshot
            .filter { it.language == language && sourceRegistry.isConfigBacked(it.name) && it.name !in hidden }
            .forEach { legacy.enableDisAbleSource(it.name, enabled) }
    }

    override suspend fun setLanguageEnabledWithFallback(
        primary: String,
        fallback: String,
        enabled: Boolean,
    ) {
        val hidden = lifecycleDisabledApis()
        val snapshot =
            legacy.allSources.first().filter { sourceRegistry.isConfigBacked(it.name) && it.name !in hidden }
        val primaryHits = snapshot.filter { it.language == primary }
        val targets = if (primaryHits.isNotEmpty()) primaryHits else {
            snapshot.filter { it.language == fallback }
        }
        targets.forEach { legacy.enableDisAbleSource(it.name, enabled) }
    }
}
