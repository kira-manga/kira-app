package me.manga.kira.di

import me.manga.kira.data.repository.FeedbackRepositoryImpl
import me.manga.kira.data.repository.LanguageRepositoryImpl
import me.manga.kira.domain.repository.FeedbackRepository
import me.manga.kira.domain.repository.LanguageRepository
import me.manga.kira.domain.usecase.feedback.SendLanguageRequestUseCase
import me.manga.kira.domain.usecase.language.GetSupportedLanguagesUseCase
import me.manga.kira.domain.usecase.language.ObserveSelectedLanguageUseCase
import me.manga.kira.domain.usecase.language.SetLanguageUseCase
import me.manga.kira.presentation.language.LanguageViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework Language slice (Phase 7.x.language foundation +
 * Phase 7.x.language.request extension).
 *
 * Scope discipline (mirrors [themeReworkModule] / [aboutReworkModule] / [whatsNewReworkModule]):
 *  - Binds rework types:
 *    - [LanguageRepository] (`:domain`) → [LanguageRepositoryImpl] (`:data`)
 *    - [FeedbackRepository] (`:domain`) → [FeedbackRepositoryImpl] (`:data`) — Phase
 *      7.x.language.request
 *    - Three language use cases ([GetSupportedLanguagesUseCase], [ObserveSelectedLanguageUseCase],
 *      [SetLanguageUseCase] — all `:domain`)
 *    - One feedback use case ([SendLanguageRequestUseCase] — `:domain`) — Phase 7.x.language.request
 *    - [LanguageViewModel] (`:presentation`) — constructor now takes 4 use cases (3 language +
 *      1 feedback)
 *  - Legacy `:shared` collaborators stay bound by `SharedModule` and are resolved transitively:
 *    - [me.manga.kira.presentation.features.settings.domain.SettingsRepository] — consumed
 *      by [LanguageRepositoryImpl] for the DataStore-backed language pref
 *    - [me.manga.kira.presentation.features.complaint.usecase.SendComplaintUseCase] —
 *      consumed by [FeedbackRepositoryImpl] for the Firestore-bound complaint write
 *    - [me.manga.kira.domain.auth.UserIdProvider] — consumed by [FeedbackRepositoryImpl]
 *      for `Complaint.userId`
 *    - [me.manga.kira.domain.device.DeviceInfoProvider] — consumed by [FeedbackRepositoryImpl]
 *      for `Complaint.metadata`
 *
 * Cross-module dependencies resolved at composition time (existing — foundation slice):
 *  - Legacy [me.manga.kira.presentation.features.settings.domain.SettingsRepository] (the
 *    constructor dep of [LanguageRepositoryImpl]) is bound `single` by `SharedModule` already.
 *  - `me.manga.kira.core.locale.applyApplicationLocale` is a top-level function, not a Koin
 *    binding — the `:data` impl calls it directly.
 *
 * Cross-module dependencies resolved at composition time (new — Phase 7.x.language.request):
 *  - Legacy [me.manga.kira.presentation.features.complaint.usecase.SendComplaintUseCase] is
 *    bound `single` by `SharedModule` (alongside the other complaint use cases). The rework
 *    `:data` impl pulls it via `get()` and calls `invoke(complaint)` to commit to Firestore.
 *  - Legacy [me.manga.kira.domain.auth.UserIdProvider] is bound `single` by platform-specific
 *    modules (PlatformModule.android / .ios / .desktop, each providing a platform-stable ID
 *    source). The rework reuses these singletons — no new platform-specific code in this slice.
 *  - Legacy [me.manga.kira.domain.device.DeviceInfoProvider] is bound `single` by
 *    platform-specific modules. Same posture.
 *
 * Strangler-fig posture: the rework slice writes to the same Firestore collection as the legacy
 * `ComplaintViewModel.submit` path — admin dashboard reads either path's submissions identically
 * (same `userId`, same `type = LANGUAGES`, same `subject = "Languages"`, same `metadata` shape).
 *
 * SRP (contract §6): one module = one feature slice (language picker + its Request-Language
 * sub-flow are aspects of one coherent feature).
 *
 * DIP (contract §6): both interfaces from `:domain` are bound to their `:data` impls at the
 * composition root. Presentation and UI see only the use cases / interfaces; the legacy types
 * (`SettingsRepository`, `SendComplaintUseCase`, `UserIdProvider`, `DeviceInfoProvider`) do not
 * leak into the rework presentation layer.
 *
 * Lifecycle choices:
 *  - [LanguageRepository] → `single`: see foundation-slice KDoc above.
 *  - [FeedbackRepository] → `single`: stateless transport whose collaborators are themselves
 *    singletons. Re-creating per resolution would be wasteful and inconsistent with the
 *    upstream `:shared` collaborators' lifecycle.
 *  - Use cases → `factory`: stateless thin pass-throughs, cheap to instantiate.
 *  - [LanguageViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster22.staleKdocSweep.cascade,
 * Task #478, 2026-05-28): one category of stale-symbol-reference citation
 * appears above:
 *  - Lines 56-58 ("Strangler-fig posture: the rework slice writes to
 *    the same Firestore collection as the legacy `ComplaintViewModel.submit`
 *    path — admin dashboard reads either path's submissions identically
 *    (same `userId`, same `type = LANGUAGES`, same `subject = "Languages"`,
 *    same `metadata` shape)"). STALE-SYMBOL-REFERENCE — Phase
 *    9.x.complaintvm.retire (§363) deleted the orphan legacy
 *    `ComplaintViewModel` referenced in the citation. The "writes to
 *    the same Firestore collection" claim STILL HOLDS at the data
 *    layer (the rework `:data` `FeedbackRepositoryImpl` reuses the
 *    legacy `:shared` `SendComplaintUseCase` which writes to the same
 *    `complaints` Firestore collection — see legacyReworkModule
 *    cross-module reuse posture at lines 46-49 above), and the admin
 *    dashboard reads BOTH paths identically (same `userId` / `type` /
 *    `subject` / `metadata` shape). What's stale is the SYMBOL anchor:
 *    `ComplaintViewModel.submit` no longer exists on disk; the
 *    submission path is now `SendComplaintUseCase.invoke(complaint)`
 *    called from `FeedbackRepositoryImpl.submitFeedback(...)` (rework
 *    slice) — never from a legacy VM. Mirror of §445 + §470 + §471 +
 *    §472 + §473 + §474 + §475 + §476 + §477 stale-symbol-reference
 *    precedent.
 *  - Lines 30-38 (legacy `:shared` collaborator references —
 *    `SettingsRepository`, `SendComplaintUseCase`, `UserIdProvider`,
 *    `DeviceInfoProvider`). LIVE — all four legacy `:shared` /
 *    PlatformModule.* singletons STILL EXIST and are resolved
 *    transitively via `get()` at the impl constructor sites (lines
 *    77-78 below). The strangler-fig backbone holds; only the legacy
 *    consumer-side `ComplaintViewModel` symbol was retired across §363.
 * The strangler-fig WRITE-side rationale + DIP/SRP rationale +
 * lifecycle-choices (single/factory/viewModel) + foundation-slice
 * (Phase 7.x.language) + Request-Language extension (Phase
 * 7.x.language.request — §250) sub-sections all stand on their own
 * merits past the §363 fulfilled landing. The languageReworkModule
 * remains LIVE as the canonical Koin module for `Screen.LanguageScreen`
 * + `Screen.LanguageRework` (both now converge on the rework path
 * post-§292 swap — see [me.manga.kira.navigation.routes.LanguageReworkScreenRoute]
 * KDoc audit-trail postscript at Task #474). Original §253-era prose
 * preserved verbatim per the audit-trail-preservation convention — the
 * citation is historical record of the design lineage including the
 * `ComplaintViewModel.submit` symbol-anchor that was subsequently
 * removed across §363, with the Firestore-collection-write semantics
 * preserved through the rework strangler-fig path.
 */
val languageReworkModule: Module = module {
    single<LanguageRepository> { LanguageRepositoryImpl(legacy = get(), localeSwitcher = get()) }
    single<FeedbackRepository> { FeedbackRepositoryImpl(get(), get(), get()) }

    factory { GetSupportedLanguagesUseCase(get()) }
    factory { ObserveSelectedLanguageUseCase(get()) }
    factory { SetLanguageUseCase(get()) }
    factory { SendLanguageRequestUseCase(get()) }

    viewModel { LanguageViewModel(get(), get(), get(), get()) }
}
