package me.manga.kira.di

import me.manga.kira.data.repository.WhatsNewRepositoryImpl
import me.manga.kira.domain.repository.WhatsNewRepository
import me.manga.kira.domain.usecase.whatsnew.GetWhatsNewFeaturesUseCase
import me.manga.kira.domain.usecase.whatsnew.MarkWhatsNewSeenUseCase
import me.manga.kira.presentation.whatsnew.WhatsNewViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework WhatsNew slice (Phase 7.x.whatsnew foundation).
 *
 * Scope discipline (mirrors [aboutReworkModule] / [themeReworkModule] / [historyReworkModule]):
 *  - Binds ONLY rework types: [WhatsNewRepository] (`:domain`) → [WhatsNewRepositoryImpl]
 *    (`:data`), two use cases ([GetWhatsNewFeaturesUseCase] + [MarkWhatsNewSeenUseCase] —
 *    `:domain`), and [WhatsNewViewModel] (`:presentation`).
 *  - The legacy `:shared` `WhatsNewViewModel` + its `WhatsNewRemoteDataSource` /
 *    `SharedPrefsHelper` / `AppVersionProvider` collaborators stay bound by `SharedModule` /
 *    `PlatformModule.*`. Both graphs coexist until Phase 9.x route-swap.
 *
 * Cross-module dependencies resolved at composition time (highest fan-out of any rework slice;
 * three `:shared` reaches — same posture as [aboutReworkModule]'s one but tripled by the
 * additional remote-fetch + prefs surfaces):
 *  - [me.manga.kira.presentation.features.whatsnew.data.WhatsNewRemoteDataSource] — Ktor JSON
 *    fetcher; `single` in `SharedModule.kt`. Consumed by [WhatsNewRepositoryImpl] for
 *    `fetchWhatsNewFeatures(...)` + `getLocalizedFeature(...)`.
 *  - [me.manga.kira.core.storage.SharedPrefsHelper] — multiplatform-settings prefs helper;
 *    `single` in `SharedModule.kt`. Consumed for the two mark-seen prefs writes (legacy key
 *    names preserved verbatim — same posture as the legacy `WhatsNewViewModel.markSeen()`).
 *  - [me.manga.kira.core.platform.AppVersionProvider] — `single` in `PlatformModule.*`. The
 *    same singleton consumed by [aboutReworkModule]'s [me.manga.kira.data.repository.AboutRepositoryImpl];
 *    no new binding, no scope leak.
 *
 * SRP (contract §6): one module = one feature slice.
 *
 * DIP (contract §6): the [WhatsNewRepository] interface from `:domain` is bound to its `:data`
 * impl at the composition root. Presentation and UI see only the use cases / interface; the
 * three-collaborator fan-out in the impl is invisible from `:presentation` upwards.
 *
 * Lifecycle choices:
 *  - [WhatsNewRepository] → `single`: impl holds no per-call state. All three constructor deps
 *    are `single` upstream; re-creating the impl per resolution would pointlessly re-wrap the
 *    same providers each time. The remote fetch is one-shot per screen-mount; the prefs write
 *    is one-shot per mark-seen. Singleton suffices.
 *  - [GetWhatsNewFeaturesUseCase] / [MarkWhatsNewSeenUseCase] → `factory`: stateless thin
 *    pass-throughs, cheap to instantiate; matches the "use case is a factory" pattern shared by
 *    every other rework module ([aboutReworkModule], [statisticsReworkModule], etc.).
 *  - [WhatsNewViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding so the screen
 *    survives configuration changes / pop-and-restore navigation. Mirrors [AboutViewModel] +
 *    [me.manga.kira.presentation.statistics.StatisticsViewModel].
 *
 * **Audit-trail postscript** (Phase 9.x.cluster19.staleKdocSweep.cascade,
 * Task #475, 2026-05-28): one category of fulfilled-prediction +
 * half-fulfilled inversion citation appears above:
 *  - Lines 19-21 ("The legacy `:shared` `WhatsNewViewModel` + its
 *    `WhatsNewRemoteDataSource` / `SharedPrefsHelper` / `AppVersionProvider`
 *    collaborators stay bound by `SharedModule` / `PlatformModule.*`. Both
 *    graphs coexist until Phase 9.x route-swap"). HALF-FULFILLED +
 *    PARTIALLY INVERTED — Phase 7.x.whatsnew.swap re-pointed
 *    `Screen.WhatsNew`'s rendering adapter to the rework `WhatsNewScreen`
 *    already; Phase 9.x.whatsnew.legacyui.retire (§351) deleted the
 *    legacy `:shared` `WhatsNewScreen.kt` UI. However, the legacy
 *    `:shared` `WhatsNewViewModel` was DELIBERATELY RETAINED as a
 *    strangler-fig seam — it is still consumed by
 *    `LibraryScreenRoute.kt:14` for the first-launch-redirect gate
 *    orchestration (the `shouldShowWhatsNew` flow drives whether the
 *    fresh-install user gets bounced to `Screen.WhatsNew` on Library
 *    mount). The three collaborators (`WhatsNewRemoteDataSource` +
 *    `SharedPrefsHelper` + `AppVersionProvider`) likewise STILL EXIST in
 *    `SharedModule` / `PlatformModule.*` to back the surviving legacy
 *    VM. "Both graphs coexist" framing is half-stale: only one UI graph
 *    survives (the rework one), but two consumer graphs coexist (the
 *    rework VM consumed by the rework UI for the WhatsNew display
 *    screen; the legacy VM consumed by `LibraryScreenRoute` for the
 *    first-launch gate). Future `Phase 7.x.library.firstlaunch.rework`
 *    slice would lift the gate into a `:domain`
 *    `ObserveShouldShowWhatsNewUseCase` and complete the legacy VM
 *    retire — at which point the "Both graphs coexist" prediction would
 *    be fully fulfilled (one VM, one UI, one Koin module). Mirror of
 *    §474 `WhatsNewScreenRoute.kt` half-fulfilled-retire precedent.
 * The Koin scope-discipline + DIP/SRP rationale + three-collaborator
 * fan-out + lifecycle-choices (single/factory/viewModel) sub-sections
 * all stand on their own merits past the §351 fulfilled landing. The
 * whatsNewReworkModule remains LIVE as the canonical Koin module for
 * the rework `WhatsNewScreen` display surface. Original §253-era prose
 * preserved verbatim per the audit-trail-preservation convention — the
 * citations are historical record of the design lineage including the
 * deferred-route-swap forecast that was subsequently half-fulfilled
 * across §351, with the legacy VM intentionally retained for the
 * cross-screen first-launch-gate seam.
 */
val whatsNewReworkModule: Module = module {
    single<WhatsNewRepository> {
        WhatsNewRepositoryImpl(
            remoteDataSource = get(),
            prefs = get(),
            appVersionProvider = get(),
            dataStore = get(),
        )
    }
    factory { GetWhatsNewFeaturesUseCase(get()) }
    factory { MarkWhatsNewSeenUseCase(get()) }
    viewModel { WhatsNewViewModel(get(), get()) }
}
