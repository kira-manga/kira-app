package me.manga.kira.di

import me.manga.kira.data.repository.ThemeRepositoryImpl
import me.manga.kira.domain.repository.ThemeRepository
import me.manga.kira.domain.usecase.theme.ObserveAppThemeUseCase
import me.manga.kira.domain.usecase.theme.ObservePureBlackUseCase
import me.manga.kira.domain.usecase.theme.SetAppThemeUseCase
import me.manga.kira.domain.usecase.theme.SetPureBlackUseCase
import me.manga.kira.presentation.theme.ThemeViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework Theme slice (Phase 7.x.theme).
 *
 * Scope discipline (mirrors [sourcesReworkModule] / [updatesReworkModule] /
 * [historyReworkModule] / [statisticsReworkModule]):
 *  - Binds ONLY rework types: [ThemeRepository] (`:domain`) → [ThemeRepositoryImpl]
 *    (`:data`), four use cases ([ObserveAppThemeUseCase], [SetAppThemeUseCase],
 *    [ObservePureBlackUseCase], [SetPureBlackUseCase] — all `:domain`), and
 *    [ThemeViewModel] (`:presentation`).
 *  - Legacy `:shared`
 *    [me.manga.kira.presentation.features.settings.domain.SettingsRepository] facade
 *    (the big surface — `darkModeFlow`, `followSystemFlow`, `setDarkMode`,
 *    `setFollowSystem`, plus every other settings pref) stays bound by `SharedModule`;
 *    both graphs coexist until Phase 9.x's user-facing route swap. The legacy onboarding
 *    `Screen.Theme` route + the rework `Screen.ThemeRework` route consume the SAME
 *    underlying `SharedPreferences`-backed pref flows through the legacy facade —
 *    toggling the theme in either route flips the same two booleans
 *    (`darkMode` + `followSystem`), so the two screens stay in sync via the legacy's
 *    flow re-emit.
 *
 * Cross-module dependencies resolved at composition time:
 *  - Legacy
 *    [me.manga.kira.presentation.features.settings.domain.SettingsRepository] (the
 *    constructor dep of [ThemeRepositoryImpl]) is bound `single` by `SharedModule`
 *    already (and is consumed by every legacy screen / VM that reads settings —
 *    SettingsViewModel, MainActivity's theme observer, etc.; the legacy
 *    OnboardingViewModel that previously consumed it was retired in §143).
 *    Strangler-fig posture — see [ThemeRepositoryImpl] KDoc for the boundary rationale.
 *
 * SRP (contract §6): one module = one feature slice.
 *
 * DIP (contract §6): the [ThemeRepository] interface from `:domain` is bound to its
 * `:data` impl at the composition root. Presentation and UI see only the use cases /
 * interface; the legacy `SettingsRepository` shape (two booleans) does not leak.
 *
 * Lifecycle choices:
 *  - [ThemeRepository] → `single`: impl holds no per-call state; the underlying legacy
 *    `SettingsRepository` is already a singleton (it owns the
 *    `SharedPreferences`-backed pref flows). Re-creating the impl per resolution would
 *    mean resubscribing on each consumer — wasteful for a read-mostly surface whose
 *    `combine`-projection of the two boolean flows is shared across the app's lifetime
 *    (the theme picker, plus any future "current theme" observer in a settings dashboard
 *    or themed-icon-set selector).
 *  - Four use cases ([ObserveAppThemeUseCase], [SetAppThemeUseCase],
 *    [ObservePureBlackUseCase], [SetPureBlackUseCase]) → `factory`: stateless thin
 *    pass-throughs, cheap to instantiate; matches the established "use case is a
 *    factory" pattern.
 *  - [ThemeViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding so the screen
 *    survives configuration changes / pop-and-restore navigation. Mirrors
 *    `SourcesViewModel`, `UpdatesViewModel`, `HistoryViewModel`, and `LibraryViewModel`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster20.staleKdocSweep.cascade,
 * Task #476, 2026-05-28): one category of fulfilled-prediction +
 * stale-citation entry appears above:
 *  - Lines 23-32 ("Legacy `:shared`
 *    [me.manga.kira.presentation.features.settings.domain.SettingsRepository]
 *    facade ... stays bound by `SharedModule`; both graphs coexist until
 *    Phase 9.x's user-facing route swap. The legacy onboarding
 *    `Screen.Theme` route + the rework `Screen.ThemeRework` route
 *    consume the SAME underlying `SharedPreferences`-backed pref flows
 *    through the legacy facade — toggling the theme in either route
 *    flips the same two booleans (`darkMode` + `followSystem`), so the
 *    two screens stay in sync via the legacy's flow re-emit").
 *    FACTUALLY INVERTED + STALE — Phase 7.x.theme.swap (§291) re-pointed
 *    `Screen.Theme`'s rendering adapter to the rework `ThemeScreen`
 *    already; the §291 7.x-prefixed swap happened earlier than the
 *    predicted 9.x landing. Both `Screen.Theme` and `Screen.ThemeRework`
 *    route keys still exist but now converge on the same rework
 *    `ThemeScreen` UI through `NavBackStackEntry`-scoped Koin instances.
 *    "Both graphs coexist" framing is moot — only one consumer-side UI
 *    graph survives (the rework one). "Two screens stay in sync via the
 *    legacy's flow re-emit" framing is moot — there is no second screen
 *    to sync with. The legacy `:shared` `SettingsRepository` facade
 *    (lines 35-41) is unaffected — it remains LIVE as the cell-of-truth
 *    that the rework `:data` `ThemeRepositoryImpl` delegates to via
 *    `legacy = get()` (verified at line 66 below). The legacy
 *    `OnboardingViewModel` retire at §143 (cited on line 40) was
 *    correctly recorded inline and remains accurate. Mirror of §445 +
 *    §470 + §471 + §472 + §473 + §474 + §475 fulfilled-deferral-
 *    inversion precedent.
 * The strangler-fig delegation rationale + DIP/SRP rationale +
 * lifecycle-choices (single/factory/viewModel) + `darkMode`+`followSystem`
 * two-boolean shape sub-sections all stand on their own merits past the
 * §291 fulfilled landing. The themeReworkModule remains LIVE as the
 * canonical Koin module for `Screen.Theme` + `Screen.ThemeRework` (both
 * now converge on the rework path post-§291 swap). Original §253-era
 * prose preserved verbatim per the audit-trail-preservation convention —
 * the citation is historical record of the design lineage including the
 * deferred-route-swap forecast that was subsequently fulfilled earlier
 * than predicted across §291.
 */
val themeReworkModule: Module = module {
    single<ThemeRepository> { ThemeRepositoryImpl(legacy = get()) }
    factory { ObserveAppThemeUseCase(get()) }
    factory { ObservePureBlackUseCase(get()) }
    factory { SetAppThemeUseCase(get()) }
    factory { SetPureBlackUseCase(get()) }
    viewModel { ThemeViewModel(get(), get(), get(), get()) }
}
