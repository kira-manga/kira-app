package me.manga.kira.di

import me.manga.kira.data.repository.SourceCatalogSyncRepositoryImpl
import me.manga.kira.data.repository.SourceUrlMigrator
import me.manga.kira.data.repository.SourcesRepositoryImpl
import me.manga.kira.domain.repository.SourceCatalogSyncRepository
import me.manga.kira.domain.repository.SourcesRepository
import me.manga.kira.domain.usecase.sources.EnableDefaultLanguageSourcesUseCase
import me.manga.kira.domain.usecase.sources.ClearNewSourcesBadgeUseCase
import me.manga.kira.domain.usecase.sources.ObserveNewSourcesBadgeUseCase
import me.manga.kira.domain.usecase.sources.ObserveSourcesUseCase
import me.manga.kira.domain.usecase.sources.SetLanguageEnabledUseCase
import me.manga.kira.domain.usecase.sources.SetSourceEnabledUseCase
import me.manga.kira.domain.usecase.sources.SyncSourceCatalogUseCase
import me.manga.kira.presentation.sources.SourcesViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework Sources slice (Phase 7.x.sources).
 *
 * Scope discipline (mirrors [updatesReworkModule] / [historyReworkModule] /
 * [statisticsReworkModule]):
 *  - Binds ONLY rework types: [SourcesRepository] (`:domain`) → [SourcesRepositoryImpl]
 *    (`:data`), three use cases (`:domain`), and [SourcesViewModel] (`:presentation`).
 *  - Legacy `:shared`
 *    [me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository] facade
 *    (the big surface — `allSources`, `enableDisAbleSource`, `findRepoByHost`, `activeRepoFlow`,
 *    `getEnabledRepos`, etc.) stays bound by `SharedModule`; both graphs coexist until
 *    Phase 9.x's user-facing route swap. The legacy onboarding `Screen.Sources` route + the
 *    rework `Screen.SourcesRework` route consume the SAME underlying Room `sources` table
 *    through the legacy facade — toggling a source in either route flips the same row, so the
 *    two screens stay in sync. (`repoTaps` / `getUrl` removed in
 *    Phase 9.x.repo.componentprune.cumulative — Task #415.)
 *
 * Cross-module dependencies resolved at composition time:
 *  - Legacy
 *    [me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository] (the
 *    constructor dep of [SourcesRepositoryImpl]) is bound `single` by `SharedModule` already
 *    (and is consumed by every legacy screen that needs source identity — Home / Search /
 *    MangaDetails / Reader / the Coil interceptor for per-source header injection). Strangler-
 *    fig posture — see [SourcesRepositoryImpl] KDoc for the boundary rationale.
 *
 * SRP (contract §6): one module = one feature slice.
 *
 * DIP (contract §6): the [SourcesRepository] interface from `:domain` is bound to its `:data`
 * impl at the composition root. Presentation and UI see only the use cases / interface.
 *
 * Lifecycle choices:
 *  - [SourcesRepository] → `single`: impl holds no per-call state; the underlying legacy
 *    `SourcesRepository` is already a singleton (it owns the `SourcesDao` and re-emits the
 *    `allSources` flow on every write). Re-creating the impl per resolution would mean
 *    resubscribing on each consumer — wasteful for a read-mostly surface shared across the
 *    app's lifetime.
 *  - Three use cases ([ObserveSourcesUseCase], [SetSourceEnabledUseCase],
 *    [SetLanguageEnabledUseCase]) → `factory`: stateless thin pass-throughs, cheap; matches
 *    the established "use case is a factory" pattern.
 *  - [SourcesViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding so the screen
 *    survives configuration changes / pop-and-restore navigation. Mirrors `UpdatesViewModel`,
 *    `HistoryViewModel`, and `LibraryViewModel`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster22.staleKdocSweep.cascade,
 * Task #478, 2026-05-28): one category of fulfilled-prediction +
 * stale-citation entry appears above:
 *  - Lines 22-30 ("Legacy `:shared`
 *    [me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository]
 *    facade (the big surface — `allSources`, `enableDisAbleSource`,
 *    `findRepoByHost`, `activeRepoFlow`, `getEnabledRepos`, etc.) stays
 *    bound by `SharedModule`; both graphs coexist until Phase 9.x's
 *    user-facing route swap. The legacy onboarding `Screen.Sources`
 *    route + the rework `Screen.SourcesRework` route consume the SAME
 *    underlying Room `sources` table through the legacy facade —
 *    toggling a source in either route flips the same row, so the two
 *    screens stay in sync"). FACTUALLY INVERTED + STALE — Phase
 *    7.x.reposettings.swap (§285) re-pointed `Screen.RepoSettings` to
 *    the rework `SourcesScreen` already; Phase 7.x.sources.swap (§305)
 *    re-pointed the onboarding `Screen.Sources` route to the rework
 *    adapter; Phase 9.x.reposettings.legacyui.retire (§353) deleted the
 *    legacy `:shared` `RepoSettingsScreen.kt` UI; Phase
 *    9.x.sources.legacycomponents.retire (§356) dropped unreachable
 *    legacy components. "Both graphs coexist" + "two screens stay in
 *    sync" framings are moot — only the rework consumer-side graph
 *    survives. Both `Screen.Sources` + `Screen.RepoSettings` +
 *    `Screen.SourcesRework` route keys still exist but converge on the
 *    same rework `SourcesScreen` UI through `NavBackStackEntry`-scoped
 *    Koin instances. The legacy `:shared` `SourcesRepository` facade
 *    (lines 33-38) remains LIVE as the cell-of-truth that the rework
 *    `:data` `SourcesRepositoryImpl` delegates to via `legacy = get()`
 *    (verified at line 59 below). The strangler-fig backbone holds;
 *    only the legacy consumer-side surfaces were retired across §353 +
 *    §356. The "Phase 9.x user-facing route swap" forecast happened
 *    earlier as a §285 + §305 7.x-prefixed swap chain. The inline
 *    Task #415 cite at line 30 (`repoTaps` / `getUrl` removed in
 *    Phase 9.x.repo.componentprune.cumulative) is accurate and remains
 *    LIVE-correct. Mirror of §445 + §470 + §471 + §472 + §473 + §474 +
 *    §475 + §476 + §477 fulfilled-deferral-inversion precedent.
 * The DI scope-discipline + DIP/SRP rationale + lifecycle-choices
 * (single/factory/viewModel) + Phase 7.x.sources.onboardingseed
 * `EnableDefaultLanguageSourcesUseCase` (§304) + Phase
 * 7.x.sources.complaint `SubmitFeedbackUseCase` cross-module-reuse
 * (§282) sub-sections all stand on their own merits past the §§285 +
 * 305 + 353 + 356 fulfilled landings. The sourcesReworkModule remains
 * LIVE as the canonical Koin module for all three convergent route
 * keys. Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citation is historical
 * record of the design lineage including the deferred-route-swap
 * forecast that was subsequently fulfilled earlier than predicted
 * across §285 + §305.
 */
val sourcesReworkModule: Module = module {
    // Sources Migration Phase 2: the catalog shows only config-backed sources — SourcesRepositoryImpl
    // filters the legacy `sources` rows by SourceRegistry.isConfigBacked (bound in sourcesGenericModule,
    // resolved cross-module by Koin's single graph).
    single<SourcesRepository> {
        SourcesRepositoryImpl(legacy = get(), sourceRegistry = get(), dataStore = get(), updateManager = get())
    }
    factory { ObserveSourcesUseCase(get()) }
    factory { SetSourceEnabledUseCase(get()) }
    factory { SetLanguageEnabledUseCase(get()) }
    // U2 (new-sources badge): Home tab strip observes; edit-tabs clears.
    factory { ObserveNewSourcesBadgeUseCase(get()) }
    factory { ClearNewSourcesBadgeUseCase(get()) }
    // Sources Migration Phase 2 / SourceRegistry retirement Phase 6: the per-table URL-migration
    // logic is the shared SourceUrlMigrator, consumed solely by the config-driven catalog sync
    // below (config.baseUrl assertion + previousHosts alias sweep). Its other historical consumer
    // — the remote-endpoint refresh (P0-SRCSEED, `SourceRegistryRefreshRepositoryImpl`) — was
    // retired in Phase 6: the bundled config document is the single source authority
    // (SOURCE_REGISTRY_RETIREMENT_PLAN.md). The 4 entity DAOs are bound `single` per platform by
    // the legacy PlatformModule.
    single { SourceUrlMigrator(mangaDao = get(), chapterDao = get(), historyDao = get(), notificationDao = get()) }
    // Sources Migration Phase 2: config-driven catalog sync — seeds config-backed sources into the
    // `sources` table and migrates stored URLs when config.baseUrl (the trusted value) changed.
    // Reads the active config via SourceUpdateManager (bound in sourcesGenericModule). Fired
    // fire-and-forget from App()'s startup LaunchedEffect.
    single<SourceCatalogSyncRepository> {
        SourceCatalogSyncRepositoryImpl(
            updateManager = get(),
            sourcesDao = get(),
            migrator = get(),
            dispatchers = get(),
        )
    }
    factory { SyncSourceCatalogUseCase(get()) }
    // Added in Phase 7.x.sources.onboardingseed — backs SourcesViewModel's 5th ctor dep
    // and SourcesIntent.OnSeedDefaultLanguage. The use case owns the tag-format + EN-
    // fallback policy; the repository owns the snapshot + fan-out mechanism.
    factory { EnableDefaultLanguageSourcesUseCase(get()) }
    // [SubmitFeedbackUseCase] is bound `factory` by the settings rework module
    // (see [settingsReworkModule] / `feedbackReworkModule`); we resolve via `get()` —
    // Koin's container is global across all `:composeApp` rework modules.
    // Added in Phase 7.x.sources.complaint to back the SourcesViewModel's new ctor dep.
    viewModel { SourcesViewModel(get(), get(), get(), get(), get()) }
}
