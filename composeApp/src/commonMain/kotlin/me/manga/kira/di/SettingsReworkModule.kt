package me.manga.kira.di

import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.data.repository.SettingsRepositoryImpl
import me.manga.kira.domain.repository.SettingsRepository
import me.manga.kira.domain.usecase.feedback.SubmitFeedbackUseCase
import me.manga.kira.domain.usecase.reader.ObserveReadingModeUseCase
import me.manga.kira.domain.usecase.reader.SetReadingModeUseCase
import me.manga.kira.domain.usecase.settings.ClearCacheUseCase
import me.manga.kira.domain.usecase.settings.ClearCbzConversionUseCase
import me.manga.kira.domain.usecase.settings.CompressExistingDownloadsUseCase
import me.manga.kira.domain.usecase.settings.ObserveCbzConversionUseCase
import me.manga.kira.domain.usecase.settings.ObserveSettingsUseCase
import me.manga.kira.domain.usecase.settings.StopCbzConversionUseCase
import me.manga.kira.domain.usecase.settings.UpdateSettingsToggleUseCase
import me.manga.kira.presentation.settings.SettingsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework Settings hub slice (Phase 7.x.settings.foundation; extended in
 * Phase 7.x.settings.feedback to bind [SubmitFeedbackUseCase]).
 *
 * Scope discipline (mirrors [themeReworkModule] / [aboutReworkModule] / [languageReworkModule]):
 *  - Binds rework types:
 *    - [SettingsRepository] (`:domain`) → [SettingsRepositoryImpl] (`:data`)
 *    - Four use cases ([ObserveSettingsUseCase], [UpdateSettingsToggleUseCase],
 *      [ClearCacheUseCase], [SubmitFeedbackUseCase] — all `:domain`). The Feedback use case
 *      transitively resolves [me.manga.kira.domain.repository.FeedbackRepository] —
 *      [languageReworkModule] already binds the single<FeedbackRepository> instance over the
 *      `:data` [me.manga.kira.data.repository.FeedbackRepositoryImpl]; the settings slice
 *      consumes the SAME singleton via `get()` (no re-bind).
 *    - Two reader use cases consumed by the Settings hub's Phase 7.x.settings.readingmode
 *      slice: [ObserveReadingModeUseCase] + [SetReadingModeUseCase] (both `:domain`). Both
 *      transitively resolve [me.manga.kira.domain.repository.ReadingModeRepository] —
 *      [readerReworkModule] already binds the `single<ReadingModeRepository>` instance over
 *      the `:data` [me.manga.kira.data.repository.ReadingModeRepositoryImpl] (Phase
 *      6.4.x.mode); the settings slice consumes the SAME singleton via `get()` (no re-bind).
 *      This is the same cross-module reuse posture as the Feedback use case above —
 *      strangler-fig over the legacy `reading_mode` ObservableSettings cell shared with the
 *      legacy `DataStoreHelper.readingModeFlow`.
 *    - [SettingsViewModel] (`:presentation`)
 *  - Legacy `:shared` collaborators stay bound by `SharedModule` and are resolved transitively:
 *    - [me.manga.kira.presentation.features.settings.domain.SettingsRepository] — consumed
 *      by [SettingsRepositoryImpl] for the 5 pref flows + the cache-folder file walk.
 *    - The legacy `SendComplaintUseCase` / `UserIdProvider` / `DeviceInfoProvider` trio —
 *      consumed transitively by [me.manga.kira.data.repository.FeedbackRepositoryImpl] for
 *      the Phase 7.x.settings.feedback Submit path. The wiring is owned by
 *      [languageReworkModule] (the original FeedbackRepository binding site).
 *  - [DispatcherProvider] — bound by [coreReworkModule]; consumed by [SettingsRepositoryImpl] to push
 *    the okio cache-walk onto [DispatcherProvider.io], keeping the upstream `combine` off the
 *    main thread.
 *
 * Cross-module dependencies resolved at composition time:
 *  - Legacy [me.manga.kira.presentation.features.settings.domain.SettingsRepository] is bound
 *    `single` by `SharedModule` already (shared across the legacy settings stack).
 *  - [DispatcherProvider] is bound `single` by [coreReworkModule].
 *
 * Strangler-fig posture: the rework slice writes to the SAME `SharedPreferences` /
 * `DataStore` keys + clears the SAME cache folder as the legacy
 * [me.manga.kira.presentation.features.settings.viewmodel.SettingsViewModel]. Toggling on
 * either screen propagates to the other via the shared upstream pref flows. This is the
 * primary smoke-test for the slice — same posture as [languageReworkModule] /
 * [themeReworkModule] / [statisticsReworkModule].
 *
 * SRP (contract §6): one module = one feature slice (Settings hub presentation + 5 toggle
 * writes + cache clear).
 *
 * DIP (contract §6): the rework [SettingsRepository] interface from `:domain` is bound to its
 * `:data` impl at the composition root. Presentation and UI see only the use cases /
 * interfaces; the legacy
 * [me.manga.kira.presentation.features.settings.domain.SettingsRepository] type does not
 * leak into the rework presentation layer.
 *
 * Lifecycle choices:
 *  - [SettingsRepository] → `single`: stateful — owns a `MutableSharedFlow<Unit>` refresh
 *    trigger for the cache-size flow. Multiple resolutions would fragment the trigger across
 *    independent flow instances, breaking the "clear cache → cache-size re-emits" contract.
 *  - Use cases → `factory`: stateless thin pass-throughs, cheap to instantiate.
 *  - [SettingsViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster20.staleKdocSweep.cascade,
 * Task #476, 2026-05-28): one category of fulfilled-prediction +
 * stale-citation entry appears above:
 *  - Lines 56-61 ("Strangler-fig posture: the rework slice writes to the
 *    SAME `SharedPreferences` / `DataStore` keys + clears the SAME cache
 *    folder as the legacy
 *    [me.manga.kira.presentation.features.settings.viewmodel.SettingsViewModel].
 *    Toggling on either screen propagates to the other via the shared
 *    upstream pref flows. This is the primary smoke-test for the slice —
 *    same posture as [languageReworkModule] / [themeReworkModule] /
 *    [statisticsReworkModule]"). FACTUALLY INVERTED + STALE — Phase
 *    7.x.settings.swap (§301) re-pointed `Screen.Settings`'s rendering
 *    adapter to the rework `SettingsScreen` already; Phase
 *    9.x.settings_about.legacyui.retire (§354) deleted the 11-file
 *    legacy Settings + About orphan chain INCLUDING the legacy
 *    `SettingsViewModel` referenced in the citation. "Toggling on either
 *    screen propagates to the other" framing is moot — only one Settings
 *    screen exists post-§354. The `:shared` `SettingsRepository` facade
 *    (line 41 + line 52) is unaffected — it remains LIVE as the
 *    cell-of-truth that the rework `:data` `SettingsRepositoryImpl`
 *    delegates to via `legacy = get()` (verified at line 80 below).
 *    The strangler-fig backbone holds; only the legacy consumer-side
 *    `SettingsViewModel` was retired across §354. The "primary smoke
 *    test" no longer requires cross-screen propagation — single-screen
 *    pref-write + flow re-emit smoke test suffices. Mirror of §445 +
 *    §470 + §471 + §472 + §473 + §474 + §475 fulfilled-deferral-inversion
 *    precedent.
 * The cross-module reuse posture (FeedbackRepository singleton from
 * languageReworkModule + ReadingModeRepository singleton from
 * readerReworkModule) + DIP/SRP rationale + lifecycle-choices
 * (single/factory/viewModel) sub-sections all stand on their own merits
 * past the §§301 + 354 fulfilled landings. The settingsReworkModule
 * remains LIVE as the canonical Koin module for `Screen.Settings` (now
 * convergent on the rework path post-§301 swap). Original §253-era
 * prose preserved verbatim per the audit-trail-preservation convention —
 * the citation is historical record of the design lineage including the
 * cross-screen-propagation smoke-test framing that was subsequently
 * fulfilled-then-collapsed as the legacy screen retired across §354.
 */
val settingsReworkModule: Module = module {
    single<SettingsRepository> {
        SettingsRepositoryImpl(
            legacy = get(),
            dispatchers = get<DispatcherProvider>(),
            // Phase 7.x.settings.cbz — DataStoreHelper (`:platform`) bound `single` by the legacy
            // PlatformModule; the rework slice consumes the SAME instance so the Yami Compressor
            // toggles round-trip through the same KEY_USE_CBZ_FORMAT / KEY_AUTO_CONVERT_TO_CBZ
            // cells the legacy CbzConversionViewModel wrote.
            dataStore = get(),
            // Phase 7.x.settings.cbz — the bulk convert-existing-downloads engine. ChapterDao
            // (`:shared`, bound by SharedModule — same instance DownloadsActionRepositoryImpl
            // consumes) walks the downloaded chapters; CbzWriter (`:platform`, bound `single` per
            // platform by PlatformModule — Android Bitmap.compress(WEBP); Desktop + iOS both transcode
            // to WebP via SkiaWebpEncoder, with an honest verbatim fallback only for skiko-undecodable
            // formats, e.g. AVIF — #33/finding-11) repacks each into a `.cbz` and deletes the originals
            // on success.
            chapterDao = get(),
            cbzWriter = get(),
            // GAP-SET-16 — MangaDao (`:shared`, bound `single` by SharedModule — same instance
            // LibraryRepositoryImpl consumes) supplies the manga title per chapter for the
            // CbzConversionProgress "Current:" block during the bulk convert.
            mangaDao = get(),
            // B4 — same ChapterDownloadDao singleton the download engine uses; lets the manual compressor
            // skip chapters with an active download row so the two never race on one chapter's CBZ.
            chapterDownloadDao = get(),
            // Re-walks each converted chapter dir so the ledger row's sizeBytes tracks the new archive.
            appFileSystem = get(),
        )
    }

    factory { ObserveSettingsUseCase(get()) }
    factory { UpdateSettingsToggleUseCase(get()) }
    factory { ClearCacheUseCase(get()) }
    factory { SubmitFeedbackUseCase(get()) }
    factory { CompressExistingDownloadsUseCase(get()) }
    // GAP-SET-16 — observe + stop the CBZ conversion progress stream; both thin pass-throughs over
    // the same `single<SettingsRepository>` instance that drives the progress StateFlow.
    factory { ObserveCbzConversionUseCase(get()) }
    factory { StopCbzConversionUseCase(get()) }
    // #14 — reset the CBZ progress flow to idle on dialog dismiss (native clearError()).
    factory { ClearCbzConversionUseCase(get()) }

    viewModel {
        SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }
}
