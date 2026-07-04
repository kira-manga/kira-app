package me.manga.kira.di

import me.manga.kira.data.repository.AboutRepositoryImpl
import me.manga.kira.domain.repository.AboutRepository
import me.manga.kira.domain.usecase.about.GetAppMetadataUseCase
import me.manga.kira.presentation.about.AboutViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework About slice (Phase 7.x.about).
 *
 * Scope discipline (mirrors [themeReworkModule] / [sourcesReworkModule] / [updatesReworkModule] /
 * [historyReworkModule] / [statisticsReworkModule]):
 *  - Binds ONLY rework types: [AboutRepository] (`:domain`) → [AboutRepositoryImpl]
 *    (`:data`), one use case ([GetAppMetadataUseCase] — `:domain`), and [AboutViewModel]
 *    (`:presentation`).
 *  - Legacy `:shared` [me.manga.kira.core.platform.AppVersionProvider] expect class (the
 *    cross-cutting facade that reads `versionName` + `packageName` per platform) stays bound
 *    by `PlatformModule.{android,ios,desktop}`'s
 *    `single { AppVersionProvider(androidContext()) }` / equivalent. The rework `:data` impl
 *    consumes that singleton via `legacy = get()` at composition time — strangler-fig posture
 *    documented in [AboutRepositoryImpl] KDoc.
 *  - Legacy `:shared` [me.manga.kira.core.platform.IntentLauncher] is also bound `single` by
 *    `PlatformModule.*` (same shape) and is consumed by the route adapter
 *    ([me.manga.kira.navigation.routes.AboutReworkScreenRoute]) — NOT here. The VM has zero
 *    Android/iOS/Desktop dependencies; only the route bridges effects to the launcher.
 *
 * Cross-module dependencies resolved at composition time:
 *  - Legacy [me.manga.kira.core.platform.AppVersionProvider] (the constructor dep of
 *    [AboutRepositoryImpl]) is bound `single` by `PlatformModule.*` already (consumed by the
 *    legacy About screen's `koinInject<AppVersionProvider>()` and by `IntentLauncher`'s own
 *    `openPlayStorePage` callers). Strangler-fig posture — see [AboutRepositoryImpl] KDoc.
 *
 * SRP (contract §6): one module = one feature slice.
 *
 * DIP (contract §6): the [AboutRepository] interface from `:domain` is bound to its `:data` impl
 * at the composition root. Presentation and UI see only the use case / interface; the legacy
 * `AppVersionProvider` shape (two String properties) does not leak.
 *
 * Lifecycle choices:
 *  - [AboutRepository] → `single`: impl holds no per-call state; the underlying legacy
 *    `AppVersionProvider` is already a singleton (it caches the `PackageManager` /
 *    `NSBundle.mainBundle` / hardcoded-Desktop reads on first access). Re-creating the impl per
 *    resolution would be wasteful for a metadata read that the screen does once per visit.
 *  - [GetAppMetadataUseCase] → `factory`: stateless thin pass-through, cheap to instantiate;
 *    matches the established "use case is a factory" pattern.
 *  - [AboutViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding so the screen
 *    survives configuration changes / pop-and-restore navigation. Mirrors `ThemeViewModel`,
 *    `SourcesViewModel`, `UpdatesViewModel`, `HistoryViewModel`, and `LibraryViewModel`.
 */
val aboutReworkModule: Module = module {
    single<AboutRepository> { AboutRepositoryImpl(legacy = get()) }
    factory { GetAppMetadataUseCase(get()) }
    viewModel { AboutViewModel(get()) }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster150.staleKdocSweep.cascade,
 * Task #606, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eighty-third sibling of the cluster57-149
 * sweep — second file of the wave-26 :composeApp/di rework Koin module
 * closing 4-leaf batch alongside ReaderReworkModule plus UpdatesReworkModule
 * plus ReworkModules aggregator):
 *  (a) "Koin-bindings-for-the-rework-About-slice-Phase-7.x.about + Scope-
 *  discipline-mirrors-themeReworkModule-sourcesReworkModule-updates
 *  ReworkModule-historyReworkModule-statisticsReworkModule + Binds-ONLY
 *  -rework-types-AboutRepository-:domain-AboutRepositoryImpl-:data-one-
 *  use-case-GetAppMetadataUseCase-:domain-and-AboutViewModel-:presentation
 *  + Legacy-:shared-AppVersionProvider-expect-class-the-cross-cutting-
 *  facade-that-reads-versionName-packageName-per-platform-stays-bound-by-
 *  PlatformModule.android-ios-desktop-s-single-AppVersionProvider-android
 *  Context-equivalent + The-rework-:data-impl-consumes-that-singleton-via
 *  -legacy-get-at-composition-time-strangler-fig-posture-documented-in-
 *  AboutRepositoryImpl-KDoc + Legacy-:shared-IntentLauncher-is-also-bound
 *  -single-by-PlatformModule-same-shape-and-is-consumed-by-the-route-
 *  adapter-AboutReworkScreenRoute-NOT-here + The-VM-has-zero-Android-iOS-
 *  Desktop-dependencies-only-the-route-bridges-effects-to-the-launcher"
 *  — LIVE-NOT-STALE plus PARTIALLY-FULFILLED-FORECAST. Verified:
 *  aboutReworkModule binds 3 types (AboutRepository + GetAppMetadata
 *  UseCase + AboutViewModel). Legacy :shared AppVersionProvider expect-
 *  class still LIVE (per cluster149 sibling 181 PARTIALLY-FULFILLED-
 *  FORECAST classification; the rework :platform tier AppVersionProvider
 *  interface coexists with the legacy expect-class behind Task #422
 *  BLOCKER section 250). The "no Android/iOS/Desktop dependencies on
 *  the VM" stance is honored — AboutViewModel's single ctor dep is
 *  GetAppMetadataUseCase which itself depends only on the pure :domain
 *  AboutRepository abstraction.
 *  (b) "Cross-module-dependencies-resolved-at-composition-time + Legacy-
 *  AppVersionProvider-the-constructor-dep-of-AboutRepositoryImpl-is-
 *  bound-single-by-PlatformModule-already-consumed-by-the-legacy-About-
 *  screen-s-koinInject-AppVersionProvider-and-by-IntentLauncher-s-own-
 *  openPlayStorePage-callers + Strangler-fig-posture-see-AboutRepository
 *  Impl-KDoc" — LIVE-NOT-STALE plus PARTIALLY-FULFILLED-FORECAST.
 *  Verified: legacy AppVersionProvider stays bound by PlatformModule.*
 *  per the rework strangler-fig convention; AboutRepositoryImpl consumes
 *  it via the `legacy = get()` ctor param. The koinInject-AppVersion
 *  Provider call site in the legacy About screen is RETIRED (legacy
 *  About screen was retired in §287/Task #287 route swap + §354 retire);
 *  the IntentLauncher.openPlayStorePage call site survives in the legacy
 *  :shared graph for now (deferred behind Task #422 section 250).
 *  (c) "SRP-contract-section-6-one-module-one-feature-slice + DIP-
 *  contract-section-6-the-AboutRepository-interface-from-:domain-is-
 *  bound-to-its-:data-impl-at-the-composition-root + Presentation-and-
 *  UI-see-only-the-use-case-interface + the-legacy-AppVersionProvider-
 *  shape-two-String-properties-does-not-leak + Lifecycle-choices +
 *  AboutRepository-single-impl-holds-no-per-call-state-the-underlying-
 *  legacy-AppVersionProvider-is-already-a-singleton-it-caches-the-
 *  PackageManager-NSBundle.mainBundle-hardcoded-Desktop-reads-on-first-
 *  access + GetAppMetadataUseCase-factory-stateless-thin-pass-through +
 *  AboutViewModel-viewModel-Koin-s-ViewModelStore-aware-binding-Mirrors-
 *  ThemeViewModel-SourcesViewModel-UpdatesViewModel-HistoryViewModel-
 *  LibraryViewModel" — LIVE-NOT-STALE. Verified: 1 single + 1 factory
 *  + 1 viewModel binding. The DIP-discipline-no-leakage stance is
 *  honored — the two-String-properties shape of AppVersionProvider is
 *  encapsulated by AboutRepositoryImpl + mapped to a :domain
 *  AppMetadata data class consumed by GetAppMetadataUseCase. Presentation
 *  sees AppMetadata, never AppVersionProvider directly.
 *  Three classifications STAND on their own merits. Original Phase 7.x
 *  .about (Task #244) module-binding prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
