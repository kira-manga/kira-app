package me.manga.kira.di

import me.manga.kira.data.repository.AdultContentClassifierImpl
import me.manga.kira.data.repository.AnalyticsRepositoryImpl
import me.manga.kira.data.repository.ChapterDeletionRepositoryImpl
import me.manga.kira.data.repository.ChapterIdResolverImpl
import me.manga.kira.data.repository.ConnectivityRepositoryImpl
import me.manga.kira.data.repository.ChapterNewBadgeRepositoryImpl
import me.manga.kira.data.repository.MangaDetailsRepositoryImpl
import me.manga.kira.data.repository.SavedMangaDetailsRepositoryImpl
import me.manga.kira.domain.repository.AdultContentClassifier
import me.manga.kira.domain.repository.AnalyticsPort
import me.manga.kira.domain.repository.ChapterDeletionRepository
import me.manga.kira.domain.repository.ChapterIdResolver
import me.manga.kira.domain.repository.ConnectivityRepository
import me.manga.kira.domain.repository.ChapterNewBadgeRepository
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.repository.SavedMangaDetailsRepository
import me.manga.kira.domain.usecase.details.ClearChapterNewUseCase
import me.manga.kira.domain.usecase.details.DeleteChapterUseCase
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.domain.usecase.details.IsAdultContentUseCase
import me.manga.kira.domain.usecase.details.ObserveSavedMangaDetailsUseCase
import me.manga.kira.domain.usecase.details.ResolveChapterIdUseCase
import me.manga.kira.domain.usecase.analytics.LogAppOpenUseCase
import me.manga.kira.domain.usecase.analytics.LogMangaOpenUseCase
import me.manga.kira.domain.usecase.connectivity.ObserveConnectivityUseCase
import me.manga.kira.domain.usecase.library.PersistNewChaptersUseCase
import me.manga.kira.domain.usecase.downloads.CancelAllDownloadsUseCase
import me.manga.kira.domain.usecase.downloads.CancelChapterDownloadUseCase
import me.manga.kira.domain.usecase.downloads.EnqueueAllChaptersDownloadUseCase
import me.manga.kira.domain.usecase.downloads.EnqueueChapterDownloadUseCase
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.library.MarkMangaOpenedUseCase
import me.manga.kira.domain.usecase.library.ObserveInLibraryUseCase
import me.manga.kira.domain.usecase.reader.MarkChaptersReadUseCase
import me.manga.kira.domain.usecase.reader.ToggleChapterReadUseCase
import me.manga.kira.presentation.details.DetailsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework Details slice (Phase 8.x).
 *
 * Scope discipline (mirrors [libraryReworkModule]):
 *  - Binds ONLY rework types: [MangaDetailsRepository] (`:domain`) → [MangaDetailsRepositoryImpl]
 *    (`:data`), [FetchMangaDetailsUseCase] (`:domain`), and [DetailsViewModel] (`:presentation`).
 *  - Legacy `MangaDerailsViewModel` and its repository stay bound by `SharedModule` /
 *    `PlatformModule.*`; both graphs coexist until the user-facing route swap in a later phase.
 *
 * Cross-module dependencies resolved at composition time:
 *  - `SourcesRepository` (consumed by [MangaDetailsRepositoryImpl]) is the same legacy
 *    `:shared` singleton declared by `SharedModule.kt:305`. This is intentional — the
 *    strangler-fig boundary lives at the rework repository (`MangaDetailsRepositoryImpl`), which
 *    delegates to `SourcesRepository.getOrRepoByName(api).fetchMangaChaptersF(url)` and maps the
 *    legacy `MangaInfo` into the pure-domain `MangaDetails` (see `:data` mapper §42.2).
 *  - `DispatcherProvider` (consumed by [MangaDetailsRepositoryImpl]) is bound as a `single` in
 *    [libraryReworkModule]. Both modules are aggregated through [allReworkModules], so the
 *    binding resolves at runtime. We deliberately do **not** re-declare it here — Koin
 *    forbids duplicate `single<T>` bindings, and the Library slice landed first.
 *
 * SRP (contract §6): one module = one feature slice. Mirrors [libraryReworkModule]'s shape;
 * future slices (reader, sources picker, etc.) each get their own `*ReworkModule.kt` aggregated
 * through [allReworkModules].
 *
 * DIP (contract §6): the `MangaDetailsRepository` interface from `:domain` is bound to its
 * `:data` implementation at the composition root. `:presentation` and `:ui` see only the
 * interface (the VM holds `FetchMangaDetailsUseCase`, never the repository directly).
 *
 * Lifecycle choices:
 *  - [MangaDetailsRepository] → `single`: the impl holds no per-call state and the underlying
 *    `SourcesRepository` is already a singleton; a fresh instance per resolution would be wasteful.
 *  - [FetchMangaDetailsUseCase] → `factory`: stateless, cheap, matches the established "use case
 *    is a factory" pattern from the Library slice and the use case's own KDoc.
 *  - [DetailsViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding so the screen
 *    survives configuration changes / pop-and-restore navigation.
 *
 * **Audit-trail postscript** (Phase 9.x.mangadetails.staleKdocSweep.cascade, Task #446,
 * 2026-05-28): the "Scope discipline" bullet above (lines 21-22) claiming "Legacy
 * `MangaDerailsViewModel` and its repository stay bound by `SharedModule` / `PlatformModule.*`;
 * both graphs coexist until the user-facing route swap in a later phase" is FACTUALLY STALE.
 * The user-facing route swap landed in Phase 9.x.mangadetails.swap (§429, Slice 4 of the
 * Phase 7.x.details.parity campaign) — `Screen.MangaDetails` was redirected to the rework
 * adapter via `OnEnterByUrl`. The legacy `MangaDerailsViewModel` + its Koin binding + the
 * legacy `MangaDetailsScreenRoute.kt` + the legacy `MangaDetailsScreen.kt` + its component
 * files were all retired in Phase 9.x.mangadetails.retire (§430, Slice 5). The graphs no
 * longer coexist — only the rework bindings declared in this module remain LIVE for the
 * Details surface. Verified by Glob search for `MangaDetailsScreenRoute.kt` returning zero hits and
 * the absence of any LiveCoexistance for the legacy VM in current SharedModule/PlatformModule
 * source. Original §253-era prose preserved verbatim per §253 — the design-intent of
 * scope-discipline-mirroring-libraryReworkModule stands; only the cited coexistence with
 * the now-retired legacy is gone.
 */
val detailsReworkModule: Module = module {
    single<MangaDetailsRepository> {
        MangaDetailsRepositoryImpl(
            sourcesRepository = get(),
            dispatchers = get(),
            // Routes ONLY config-backed sources (CONFIG_BACKED_APIS) through the generic engine; all others stay legacy.
            sourceRegistry = get(),
        )
    }

    factory { FetchMangaDetailsUseCase(get()) }

    // Adult-content gate (Phase 6.3.4) — synchronous in-memory blacklist lookup. Bound as
    // single because the classifier holds the legacy SourcesRepository reference and is
    // stateless beyond that — re-creating it per resolution would be wasteful. The use case
    // stays factory (stateless wrapper, cheap to construct).
    single<AdultContentClassifier> { AdultContentClassifierImpl(sourcesRepository = get()) }

    factory { IsAdultContentUseCase(get()) }

    // Library-membership reactive observer for the bookmark heart on the Details top bar
    // (Phase 7.x.details.bookmark §253). The `ToggleInLibraryUseCase` consumed by the
    // [DetailsViewModel] is **not** declared here — it's already bound as a factory in
    // [libraryReworkModule]. Koin's single-graph composition resolves the cross-module
    // reference at construction time; declaring it again here would either be a duplicate
    // factory (harmless but noisy) or, if accidentally promoted to `single`, a Koin
    // duplicate-binding error. ADR-1 + plan §253-3.
    //
    // Stateless wrapper around the repository's reactive `EXISTS` query — matches the
    // established "use case is a factory" pattern from the Library slice.
    factory { ObserveInLibraryUseCase(get()) }

    // Offline/local Details path (regression fix, 2026-05-31): a Library-opened manga renders its
    // saved chapter list + read/downloaded/bookmark marks from Room immediately, instead of looking
    // fresh while the network fetch runs (and stays visible offline / when the source fails). The
    // impl reads the `:shared` Room DAOs (MangaDao + ChapterDao) directly — the same singletons the
    // download/resolver impls inject — bound `single` (stateless beyond those DAO refs).
    single<SavedMangaDetailsRepository> {
        SavedMangaDetailsRepositoryImpl(mangaDao = get(), chapterDao = get(), dispatchers = get())
    }
    factory { ObserveSavedMangaDetailsUseCase(get()) }

    // "Download all" enqueue path (Phase 7.x.details.downloadall). The url -> Room-chapterId
    // resolver bridges the pure-domain url-keyed Chapter to the chapterId-keyed download
    // subsystem; bound as `single` because it wraps the `ChapterDao` singleton and is stateless.
    // The enqueue-all use case composes this resolver with [EnqueueDownloadUseCase] (bound as a
    // factory in [downloadsReworkModule]; Koin's single-graph composition resolves it cross-module
    // — both modules are aggregated through [allReworkModules]) plus the `DispatcherProvider`
    // single. Bound as `factory` per the established "use case is a factory" pattern.
    single<ChapterIdResolver> { ChapterIdResolverImpl(chapterDao = get()) }
    // DIP seam (A19): the Details VM resolves chapter url → Room id through this use case, never
    // the [ChapterIdResolver] interface directly. Factory per the slice pattern.
    factory { ResolveChapterIdUseCase(get()) }
    factory {
        EnqueueAllChaptersDownloadUseCase(
            chapterIdResolver = get(),
            enqueueDownload = get(),
            dispatchers = get(),
        )
    }

    // GAP-LIB-02/03 per-chapter library management on the Details chapter list. The repositories
    // these compose over are bound cross-module (Koin single-graph composition resolves them):
    //  - MarkChapterReadRepository → ReaderReworkModule (the same `saved_chapters.isRead` store the
    //    reader writes; the toggle/bulk-mark overloads were added on the existing repo+impl).
    //  - EnqueueDownloadUseCase / CancelDownloadUseCase → DownloadsReworkModule.
    //  - ChapterIdResolver → bound above in this module.
    // All bound `factory` per the established "use case is a factory" pattern.
    factory { ToggleChapterReadUseCase(get()) }
    factory { MarkChaptersReadUseCase(get()) }
    factory { EnqueueChapterDownloadUseCase(chapterIdResolver = get(), enqueueDownload = get()) }
    factory { CancelChapterDownloadUseCase(chapterIdResolver = get(), cancelDownload = get()) }
    // Bulk stop wraps the cross-module DownloadsActionRepository single (bound in
    // downloadsReworkModule) and stops the worker, not just prunes rows. CancelRunningDownloadUseCase
    // is bound once in downloadsReworkModule and resolved cross-module by Koin's single graph.
    factory { CancelAllDownloadsUseCase(get()) }
    // Bumps lastOpenTimestamp on Details open (LAST_READ sort parity); wraps the cross-module
    // LibraryRepository single (bound in libraryReworkModule), resolved by Koin's single graph.
    factory { MarkMangaOpenedUseCase(get()) }

    // #3 NEW-chapter lifecycle on the Details screen:
    //  - PersistNewChaptersUseCase wraps the cross-module LibraryRepository single (libraryReworkModule)
    //    — persists refresh-discovered chapters with isNew=true/fetchedAt so they survive nav-away.
    //  - ChapterNewBadgeRepository (over the :shared ChapterDao single) clears isNew on open WITHOUT
    //    marking read; ClearChapterNewUseCase is the VM seam. Bound single (stateless DAO wrapper).
    factory { PersistNewChaptersUseCase(get()) }
    single<ChapterNewBadgeRepository> { ChapterNewBadgeRepositoryImpl(chapterDao = get()) }
    factory { ClearChapterNewUseCase(get()) }

    // Per-chapter "delete from database" (Details delete button): removes the saved_chapters row.
    // The VM deletes the chapter's download first (existing DeleteDownloadUseCase) so files/queue
    // rows don't orphan, then calls this to drop the record.
    single<ChapterDeletionRepository> { ChapterDeletionRepositoryImpl(chapterDao = get()) }
    factory { DeleteChapterUseCase(get()) }

    // #4: offline-download gate. ConnectivityRepository wraps the `:platform` ConnectivityObserver
    // (bound `single` by the legacy PlatformModule on all 3 targets). `single` because it owns a
    // long-lived reachability flow; the use case is a stateless `factory`.
    single<ConnectivityRepository> { ConnectivityRepositoryImpl(observer = get()) }
    factory { ObserveConnectivityUseCase(get()) }

    // #11: native-parity analytics. AnalyticsPort (:domain) → AnalyticsRepositoryImpl delegating to
    // the `:platform` AnalyticsClient (bound `single` per platform). LogMangaOpen fired from the
    // Details VM; LogAppOpen koinInject()'d in App.kt's launch effect.
    single<AnalyticsPort> { AnalyticsRepositoryImpl(client = get()) }
    factory { LogMangaOpenUseCase(get()) }
    factory { LogAppOpenUseCase(get()) }

    viewModel {
        DetailsViewModel(
            fetchDetails = get(),
            isAdultContent = get(),
            observeInLibrary = get(),
            observeSavedDetails = get(),
            // Cross-module factory resolution: [ToggleInLibraryUseCase] is bound in
            // [libraryReworkModule], not redeclared here (would be a Koin duplicate-binding error
            // if accidentally promoted to `single`). Koin's single-graph composition resolves the
            // reference at construction time. ADR-1 / §253-3.
            toggleInLibrary = get(),
            enqueueAllChaptersDownload = get(),
            // GAP-LIB-02/03 per-chapter management.
            toggleChapterRead = get(),
            // Per-chapter bookmark toggle (native LibraryChapterItem bookmark icon). The
            // ToggleChapterBookmarkUseCase is bound as a `factory` in [readerReworkModule] (it shares
            // the `saved_chapters.isBookmarked` store the reader writes); Koin's single-graph
            // composition resolves the cross-module reference at construction time — not redeclared
            // here, mirroring the [ToggleInLibraryUseCase] cross-module posture above.
            toggleChapterBookmark = get(),
            markChaptersRead = get(),
            enqueueChapterDownload = get(),
            cancelChapterDownload = get(),
            cancelRunningDownload = get(),
            cancelAllDownloads = get(),
            // L-4 / L-7 delete-downloaded (multi-select delete + top-bar delete-all-downloaded + per-row
            // delete). DeleteDownloadedChapterUseCase (full cleanup: clear isDownloaded + delete files +
            // drop queue row) is bound as a `factory` in [downloadsReworkModule]; Koin's single-graph
            // composition resolves the cross-module reference at construction time.
            deleteDownloadedChapter = get(),
            observeDownloads = get(),
            resolveChapterId = get(),
            markMangaOpened = get(),
            // #3: persist refresh-discovered chapters (libraryReworkModule's LibraryRepository) and
            // clear the NEW badge on chapter open.
            persistNewChapters = get(),
            clearChapterNew = get(),
            // Per-chapter delete-from-DB button.
            deleteChapter = get(),
            // #4: connectivity gate for the download actions.
            observeConnectivity = get(),
            // #11: manga_open analytics.
            logMangaOpen = get(),
        )
    }
}
