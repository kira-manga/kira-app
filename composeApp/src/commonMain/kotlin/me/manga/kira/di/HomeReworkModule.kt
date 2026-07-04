package me.manga.kira.di

import me.manga.kira.data.repository.HomeFeedRepositoryImpl
import me.manga.kira.data.repository.SearchRepositoryImpl
import me.manga.kira.domain.repository.HomeFeedRepository
import me.manga.kira.domain.repository.SearchRepository
import me.manga.kira.domain.usecase.home.FetchFeaturedUseCase
import me.manga.kira.domain.usecase.home.FetchHomeFeedUseCase
import me.manga.kira.domain.usecase.home.FetchMoreHomeFeedUseCase
import me.manga.kira.domain.usecase.home.LoadSearchFiltersUseCase
import me.manga.kira.domain.usecase.home.ObserveActiveTabIndexUseCase
import me.manga.kira.domain.usecase.home.ObserveSiteStateUseCase
import me.manga.kira.domain.usecase.home.ObserveSourceTabsUseCase
import me.manga.kira.domain.usecase.home.SearchAllReposUseCase
import me.manga.kira.domain.usecase.home.SearchSourceUseCase
import me.manga.kira.domain.usecase.home.SelectSourceTabUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.presentation.home.HomeViewModel
import me.manga.kira.presentation.search.SearchViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework Home + Search slice (Epic H5a).
 *
 * Scope discipline (mirrors [detailsReworkModule] / [readerReworkModule]):
 *  - Binds ONLY rework types: [HomeFeedRepository] / [SearchRepository] (`:domain`) → their
 *    `:data` impls, the H1 `:domain` Home/Search use cases (factories), and the H3
 *    [HomeViewModel] / [SearchViewModel] (`:presentation`).
 *  - Both screens are observed through the H5a route adapter `HomeReworkScreenRoute`, which
 *    swaps `HomeScreen`/`SearchScreen` on `HomeState.isSearching` (legacy overlay parity).
 *
 * **Strangler-fig reuse — legacy `:shared` singletons resolved via `get()` (NOT re-declared)**:
 *  - `SourcesRepository` (consumed by [HomeFeedRepositoryImpl] + [SearchRepositoryImpl]) is the
 *    same legacy `:shared` singleton bound by `SharedModule.kt` (`single { SourcesRepository(...) }`)
 *    that the Details + Reader slices already strangle. The strangler boundary lives in the rework
 *    `:data` impls, which delegate to the active `BaseMangaRepository`'s `fetchMangaHomeF` /
 *    `fetchMoreManga` / `fetchPopularManga` / `fetchSearchDataF` and map legacy `core.states.State`
 *    payloads into pure-domain `AppResult`. Per user direction the per-source `sources_repositry`
 *    parsers stay in `:shared` (never migrated), so this seam is permanent.
 *  - `ManhastroDadosStore` (consumed by [HomeFeedRepositoryImpl]) is the same per-process cache
 *    `single { ManhastroDadosStore() }` bound by `SharedModule.kt`. The impl replicates the legacy
 *    `MangaViewModel.onTabSelected` `clear()`-on-tab-switch behaviour (locked decision H-§77-(3)).
 *  - `DispatcherProvider` (consumed by both impls) is the `single<DispatcherProvider>` bound by
 *    [libraryReworkModule] — same posture the Details + Reader slices use. We deliberately do NOT
 *    re-declare any of these three: Koin forbids duplicate `single<T>` bindings, and all three
 *    land before this module in the aggregated graph ([allReworkModules]).
 *  - [ObserveLibraryUseCase] (consumed by [HomeViewModel] for whole-library heart-sync) is already a
 *    `factory` in [libraryReworkModule]; Koin's single-graph composition resolves it cross-module,
 *    so it is not re-declared here. [ToggleInLibraryUseCase] is bound in [libraryReworkModule]
 *    (resolved the same way) — same cross-module reuse as [detailsReworkModule].
 *
 * SRP (contract §6): one module = one feature slice. Mirrors the established `*ReworkModule.kt`
 * shape; aggregated through [allReworkModules].
 *
 * DIP (contract §6): the `:domain` repository interfaces are bound to their `:data` impls at the
 * composition root. `:presentation` + `:ui` see only the use cases / interfaces (the VMs hold use
 * cases, never the repositories directly).
 *
 * Lifecycle choices:
 *  - Repositories → `single`: the impls hold no per-call state and their legacy collaborators are
 *    already singletons; a fresh instance per resolution would be wasteful.
 *  - Use cases → `factory`: stateless, cheap, matches the established "use case is a factory"
 *    pattern across every prior rework slice.
 *  - ViewModels → `viewModel`: Koin's `ViewModelStore`-aware binding so the screens survive
 *    configuration changes / pop-and-restore navigation. Mirrors `DetailsViewModel` /
 *    `ReaderViewModel`.
 */
val homeReworkModule: Module = module {
    single<HomeFeedRepository> {
        // 4th arg: SourceRegistry — routes ONLY config-backed sources (CONFIG_BACKED_APIS) Home/Featured through
        // the generic engine; every other source stays on the legacy SourcesRepository path.
        HomeFeedRepositoryImpl(get(), get(), get(), get())
    }
    single<SearchRepository> {
        // 3rd arg: SourceRegistry — routes ONLY config-backed sources (CONFIG_BACKED_APIS) search through the engine.
        SearchRepositoryImpl(get(), get(), get())
    }

    // H1 Home feed use cases — each a thin projection over [HomeFeedRepository].
    factory { ObserveSourceTabsUseCase(get()) }
    factory { ObserveActiveTabIndexUseCase(get()) }
    factory { ObserveSiteStateUseCase(get()) }
    factory { SelectSourceTabUseCase(get()) }
    factory { FetchHomeFeedUseCase(get()) }
    factory { FetchMoreHomeFeedUseCase(get()) }
    factory { FetchFeaturedUseCase(get()) }

    // H1 Search use cases. [LoadSearchFiltersUseCase] reads the active source's sort/genre axes,
    // which the feed repository owns, so it depends on [HomeFeedRepository] (not [SearchRepository]).
    factory { SearchSourceUseCase(get()) }
    factory { SearchAllReposUseCase(get()) }
    factory { LoadSearchFiltersUseCase(get()) }

    viewModel {
        HomeViewModel(
            observeSourceTabs = get(),
            observeActiveTabIndex = get(),
            observeSiteState = get(),
            selectSourceTab = get(),
            fetchHomeFeed = get(),
            fetchMoreHomeFeed = get(),
            fetchFeatured = get(),
            // Cross-module factory resolution: [ObserveLibraryUseCase] is bound in
            // [libraryReworkModule], [ToggleInLibraryUseCase] too. Both are resolved by Koin's
            // single-graph composition; re-declaring either as `single` here would be a Koin
            // duplicate-binding error. Heart-sync now observes the whole library once (see HomeVM).
            observeLibrary = get(),
            toggleInLibrary = get(),
            observeNewSourcesBadge = get(),
            clearNewSourcesBadge = get(),
            // #2: FetchMangaDetailsUseCase is bound `factory` in [detailsReworkModule] (over the
            // `single` MangaDetailsRepository); resolved cross-module by Koin's single-graph
            // composition — same posture as observeLibrary/toggleInLibrary above.
            fetchDetails = get(),
        )
    }

    viewModel {
        SearchViewModel(
            searchSource = get(),
            searchAllRepos = get(),
            loadFilters = get(),
        )
    }
}
