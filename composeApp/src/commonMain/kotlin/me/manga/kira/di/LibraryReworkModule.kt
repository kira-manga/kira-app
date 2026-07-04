package me.manga.kira.di

import me.manga.kira.data.repository.LibraryPrefsRepositoryImpl
import me.manga.kira.data.repository.LibraryRefreshRepositoryImpl
import me.manga.kira.data.repository.LibraryRepositoryImpl
import me.manga.kira.domain.repository.LibraryPrefsRepository
import me.manga.kira.domain.repository.LibraryRefreshRepository
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.usecase.library.BulkRemoveFromLibraryUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryCategoryUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryDisplayUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryFilterUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryGridDensityUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryItemsPerRowUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryLastUpdatedUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryRefreshResultUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryRefreshUseCase
import me.manga.kira.domain.usecase.library.PersistNewChaptersAndNotifyUseCase
import me.manga.kira.domain.usecase.library.RefreshAllLibraryChaptersUseCase
import me.manga.kira.domain.usecase.library.ObserveLibrarySortDirectionUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryRandomSeedUseCase
import me.manga.kira.domain.usecase.library.ObserveLibrarySortUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.domain.usecase.library.RefreshLibraryUseCase
import me.manga.kira.domain.usecase.library.SetLibraryCategoryUseCase
import me.manga.kira.domain.usecase.library.SetLibraryFilterUseCase
import me.manga.kira.domain.usecase.library.SetLibraryGridDensityUseCase
import me.manga.kira.domain.usecase.library.SetLibraryItemsPerRowUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowButtonsUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowCountUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowDetailsUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowSourceUseCase
import me.manga.kira.domain.usecase.library.SetLibraryShowTabsUseCase
import me.manga.kira.domain.usecase.library.SetLibrarySortDirectionUseCase
import me.manga.kira.domain.usecase.library.SetLibraryRandomSeedUseCase
import me.manga.kira.domain.usecase.library.SetLibrarySortUseCase
import me.manga.kira.domain.usecase.library.ToggleInLibraryUseCase
import me.manga.kira.domain.usecase.library.ToggleMangaLikedUseCase
import me.manga.kira.domain.usecase.library.ToggleMangaWatchingNowUseCase
import me.manga.kira.presentation.library.LibraryViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework Library slice (Phase 8).
 *
 * Scope discipline:
 *  - Binds ONLY rework types (new `LibraryRepository` in `:domain`, new `LibraryRepositoryImpl` in
 *    `:data`, new `LibraryViewModel` in `:presentation`). Legacy `LibraryViewModel` and legacy
 *    repository stay bound by `SharedModule` / `PlatformModule.*`; both graphs coexist until the
 *    user-facing route swap in Phase 8.y.
 *  - Reuses existing platform-module bindings for `MangaDao` and `LibraryDeo` — those are still
 *    produced by the Room database that lives in `:shared` (see `PlatformModule.android.kt:84,87`
 *    and the matching iOS / Desktop files). The DAO interfaces themselves are referenced through
 *    the transitional `:data → implementation(:shared)` edge, but the singletons are emitted by
 *    `:shared`'s platform module. When Room relocates into `:data` (later phase), the DAO bindings
 *    move along with it.
 *
 * SRP (contract §6): one module = one feature slice. Future slices (details, reader, etc.) each
 * get their own `*ReworkModule.kt` aggregated through [allReworkModules].
 *
 * DIP (contract §6): the `LibraryRepository` interface from `:domain` is bound to its `:data`
 * implementation here at the composition root. `:presentation` and `:ui` see only the interface.
 *
 * Lifecycle choices:
 *  - [LibraryRepository] → `single`: Room DAOs underneath are singletons, and the impl holds no
 *    per-call state — a fresh instance per resolution would be wasteful.
 *  - Use cases → `factory`: stateless and cheap to instantiate; matches the contract's stated
 *    "Koin binds it as a factory" guidance (see `ObserveLibraryUseCase.kt`).
 *  - [LibraryViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding so the screen
 *    survives configuration changes / pop-and-restore navigation.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster21.staleKdocSweep.cascade,
 * Task #477, 2026-05-28): one category of fulfilled-prediction +
 * stale-citation entry appears above:
 *  - Lines 44-47 ("Binds ONLY rework types (new `LibraryRepository` in
 *    `:domain`, new `LibraryRepositoryImpl` in `:data`, new
 *    `LibraryViewModel` in `:presentation`). Legacy `LibraryViewModel`
 *    and legacy repository stay bound by `SharedModule` /
 *    `PlatformModule.*`; both graphs coexist until the user-facing
 *    route swap in Phase 8.y"). FACTUALLY INVERTED + STALE — Phase
 *    9.x.library.swap (§346) re-pointed `Screen.Library`'s rendering
 *    adapter to the rework `LibraryScreen` already; Phase
 *    9.x.library.retire (§347) deleted the legacy `:shared`
 *    `LibraryScreen.kt` UI + the parallel debug route; Phase
 *    9.x.library.deadcomposable.retire (§348) dropped the dead
 *    `AnimatedPreloader` composable. "Both graphs coexist" framing is
 *    moot — only the rework consumer-side graph survives. The
 *    "Phase 8.y user-facing route swap" forecast happened earlier as a
 *    9.x-prefixed swap+retire chain (post-foundation work timeline
 *    shifted from 8.y to 9.x). The legacy DAOs (`MangaDao` + `LibraryDeo`)
 *    cited on lines 48-50 remain LIVE in `PlatformModule.*` as the Room
 *    cell-of-truth that the rework `:data` `LibraryRepositoryImpl`
 *    consumes via `mangaDao = get(), libraryDeo = get()` (verified at
 *    lines 75-76 below). Mirror of §445 + §470 + §471 + §472 + §473 +
 *    §474 + §475 + §476 fulfilled-deferral-inversion precedent.
 * The DI scope-discipline + SRP/DIP rationale + lifecycle-choices
 * (single/factory/viewModel) sub-sections all stand on their own merits
 * past the §§346 + 347 + 348 fulfilled landings. The libraryReworkModule
 * remains LIVE as the canonical Koin module for `Screen.Library` +
 * `Screen.LibraryRework` (both now converge on the rework path post-§346
 * swap). The 5/5 per-flag display-toggle ladder (§334 / §336 / §338 /
 * §340 / §341 cited on lines 132-158) + §327 download-progress badge +
 * §345 action-row toggles (`ToggleMangaLikedUseCase` / `ToggleMangaWatchingNowUseCase`)
 * remain accurate descriptors of the current binding shape. Original
 * §253-era prose preserved verbatim per the audit-trail-preservation
 * convention — the citation is historical record of the design lineage
 * including the deferred-route-swap forecast (Phase 8.y) that was
 * subsequently fulfilled as a 9.x-prefixed swap+retire chain.
 */
val libraryReworkModule: Module = module {
    single<LibraryRepository> {
        LibraryRepositoryImpl(
            mangaDao = get(),
            libraryDeo = get(),
            // chapterDao + notificationDao back persistNewChaptersAndNotify (refresh-all writes
            // Notifications-screen rows for new chapters; cross-platform parity with the Android worker).
            chapterDao = get(),
            notificationDao = get(),
            // historyDao backs updateCoverIfChanged (rotated-cover reconcile across saved_manga +
            // history + notifications, mirroring the Android worker's updateMangaImageUrlEverywhere).
            historyDao = get(),
            // chapterDownloadDao + downloadRepository let purgeManga cancel an in-flight download of the
            // manga being removed (so the engine can't write an orphan CBZ into the just-purged dir).
            chapterDownloadDao = get(),
            downloadRepository = get(),
            fileService = get(),
            // ReadProgressRepository (bound in readerReworkModule) — clears resume positions on
            // removal; resolved cross-module by Koin's single graph.
            readProgress = get(),
            dispatchers = get(),
        )
    }

    single<LibraryRefreshRepository> {
        LibraryRefreshRepositoryImpl(
            scheduler = get(),
            refreshAllChapters = get(),
            libraryPrefs = get(),
            dispatchers = get(),
        )
    }

    // #1 cross-platform refresh-all: composes ObserveLibrary + FetchMangaDetails (detailsReworkModule)
    // + PersistNewChapters (detailsReworkModule), resolved across modules by Koin's single graph.
    factory { PersistNewChaptersAndNotifyUseCase(get()) }
    factory {
        RefreshAllLibraryChaptersUseCase(
            observeLibrary = get(),
            fetchDetails = get(),
            persistAndNotify = get(),
            libraryRepo = get(),
            dispatchers = get(),
        )
    }

    single<LibraryPrefsRepository> { LibraryPrefsRepositoryImpl(settings = get()) }

    factory { ObserveLibraryUseCase(get()) }
    factory { ToggleInLibraryUseCase(get()) }
    factory { BulkRemoveFromLibraryUseCase(get()) }
    factory { RefreshLibraryUseCase(get()) }
    factory { ObserveLibraryRefreshUseCase(get()) }
    factory { ObserveLibraryRefreshResultUseCase(get()) }
    factory { ObserveLibrarySortUseCase(get()) }
    factory { SetLibrarySortUseCase(get()) }
    factory { ObserveLibraryRandomSeedUseCase(get()) }
    factory { SetLibraryRandomSeedUseCase(get()) }
    factory { ObserveLibrarySortDirectionUseCase(get()) }
    factory { SetLibrarySortDirectionUseCase(get()) }
    factory { ObserveLibraryFilterUseCase(get()) }
    factory { SetLibraryFilterUseCase(get()) }
    factory { ObserveLibraryGridDensityUseCase(get()) }
    factory { SetLibraryGridDensityUseCase(get()) }
    factory { ObserveLibraryItemsPerRowUseCase(get()) }
    factory { SetLibraryItemsPerRowUseCase(get()) }
    factory { ObserveLibraryCategoryUseCase(get()) }
    factory { SetLibraryCategoryUseCase(get()) }
    factory { ObserveLibraryLastUpdatedUseCase(get()) }
    factory { ObserveLibraryDisplayUseCase(get()) }
    factory { SetLibraryShowSourceUseCase(get()) }
    factory { SetLibraryShowCountUseCase(get()) }
    factory { SetLibraryShowDetailsUseCase(get()) }
    factory { SetLibraryShowButtonsUseCase(get()) }
    factory { SetLibraryShowTabsUseCase(get()) }
    // §179 rung 19 (Task #345): per-card action-row toggles. Flip-not-set affinity flags
    // (`isLiked` / `isWatchingNow`) — see the use case KDocs for the strangler-fig
    // boundary narrative. The third action of the row (single-delete) reuses the existing
    // `BulkRemoveFromLibraryUseCase` factory above — no new binding there.
    factory { ToggleMangaLikedUseCase(get()) }
    factory { ToggleMangaWatchingNowUseCase(get()) }

    viewModel {
        LibraryViewModel(
            observeLibrary = get(),
            bulkRemoveFromLibrary = get(),
            refreshLibrary = get(),
            observeLibraryRefresh = get(),
            observeLibraryRefreshResult = get(),
            observeLibrarySort = get(),
            setLibrarySort = get(),
            observeLibrarySortDirection = get(),
            setLibrarySortDirection = get(),
            observeLibraryFilter = get(),
            setLibraryFilter = get(),
            observeLibraryGridDensity = get(),
            setLibraryGridDensity = get(),
            observeLibraryItemsPerRow = get(),
            setLibraryItemsPerRow = get(),
            observeLibraryCategory = get(),
            setLibraryCategory = get(),
            observeLibraryLastUpdated = get(),
            // §150 rung 16b (Task #334): display-toggle bundle observer + showSource setter.
            // Bundle ADT for read (cheap `combine` in `:data`) + per-flag setters for write
            // (each toggle flip is its own intent). The remaining four setters
            // (Count/Details/Buttons/Tabs) join here as rungs 16c-16f.
            observeLibraryDisplay = get(),
            setLibraryShowSource = get(),
            // §150 rung 16c (Task #336): setter for the showCount toggle. Pairs with the
            // bundled `observeLibraryDisplay` observer already in place from 16b — same
            // "bundle observe + per-flag set" symmetry break as rung 16b.
            setLibraryShowCount = get(),
            // §150 rung 16d (Task #338): setter for the showDetails toggle. Same "bundle
            // observe + per-flag set" symmetry as 16b/16c — one flag, two `:ui` gates
            // (§165 cardlastread + §166 cardprogress) landing in the rung-16d2 follow-on.
            setLibraryShowDetails = get(),
            // §150 rung 16e (Task #340): setter for the showButtons toggle. Same "bundle
            // observe + per-flag set" symmetry as 16b/16c/16d. Single sub-rung — no
            // `:ui` gate follow-on today because the legacy MangaCard bottom action row
            // hasn't been ported yet; the VM-side write path still lands so legacy
            // display-sheet flips stay in sync with the shared `library_show_buttons`
            // disk cell.
            setLibraryShowButtons = get(),
            // §150 rung 16f (Task #341): setter for the showTabs toggle. Closes the 5/5
            // per-flag vertical ladder (showSource → showCount → showDetails → showButtons
            // → showTabs). The `:ui` gate lifts in the SAME slice as the VM wiring because
            // the gate is one screen-level `if (state.display.showTabs)` wrap around the
            // CategoryTabs row (not per-card) — fits inside the same ≤5-file commit cap.
            setLibraryShowTabs = get(),
            // §161.downloadprogress (Task #327): the active-download badge collector. The use
            // case itself is bound by `downloadsReworkModule` (DRY — one slice owns one
            // factory); referencing it through Koin's global graph here keeps the Library
            // slice's Koin module focused on Library bindings only. Same cross-module Koin
            // resolution posture as the legacy code's reach into other repositories.
            observeDownloads = get(),
            // §179 rung 19 (Task #345): per-card action-row toggles. Pair of flip-not-set
            // affinity-flag use cases — see the use case KDocs for the strangler-fig
            // boundary narrative. The third action of the row (single-delete) reuses the
            // existing `bulkRemoveFromLibrary` ctor arg above — no new arg needed there.
            toggleMangaLiked = get(),
            toggleMangaWatchingNow = get(),
            // Global "Downloaded only" Settings observer (native parity — filters ALL library
            // entries). Bound by `settingsReworkModule`; resolved through Koin's global graph here,
            // same cross-module posture as `observeDownloads` above.
            observeSettings = get(),
            // RANDOM-sort stable-shuffle seed persistence (native KEY_SEED parity).
            observeLibraryRandomSeed = get(),
            setLibraryRandomSeed = get(),
        )
    }
}
