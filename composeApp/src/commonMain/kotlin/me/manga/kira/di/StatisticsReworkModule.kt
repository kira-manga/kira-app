package me.manga.kira.di

import me.manga.kira.data.repository.ReadingStatisticsRepositoryImpl
import me.manga.kira.domain.repository.ReadingStatisticsRepository
import me.manga.kira.domain.usecase.statistics.ObserveReadingStatisticsUseCase
import me.manga.kira.presentation.statistics.StatisticsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework Statistics slice (Phase 7.x.statistics).
 *
 * Scope discipline (mirrors [readerReworkModule]):
 *  - Binds ONLY rework types: [ReadingStatisticsRepository] (`:domain`) →
 *    [ReadingStatisticsRepositoryImpl] (`:data`), [ObserveReadingStatisticsUseCase]
 *    (`:domain`), and [StatisticsViewModel] (`:presentation`).
 *  - Legacy `:shared` `StatisticsViewModel` + its eight `Flow<Int>` / `Flow<String>` aggregates
 *    stay bound by `SharedModule`; both graphs coexist until the Phase 9.x user-facing route
 *    swap. Both routes consume the SAME legacy
 *    [me.manga.kira.presentation.features.statistics.domain.StatisticsRepository] under the
 *    hood — so a chapter the user reads in EITHER reader updates the numbers on BOTH screens.
 *
 * Cross-module dependencies resolved at composition time:
 *  - Legacy [me.manga.kira.presentation.features.statistics.domain.StatisticsRepository]
 *    (consumed by [ReadingStatisticsRepositoryImpl]) is the same `:shared` `single` declared by
 *    `SharedModule.kt` and already reused by [readerReworkModule]'s [ReadingSessionRepositoryImpl].
 *    Strangler-fig posture — see [ReadingStatisticsRepositoryImpl] KDoc for the boundary rationale.
 *
 * SRP (contract §6): one module = one feature slice.
 *
 * DIP (contract §6): the [ReadingStatisticsRepository] interface from `:domain` is bound to its
 * `:data` impl at the composition root. Presentation and UI see only the use case / interface.
 *
 * Lifecycle choices:
 *  - [ReadingStatisticsRepository] → `single`: impl holds no per-call state; the underlying
 *    legacy `StatisticsRepository` is already a singleton (it owns the eight in-memory
 *    `MutableStateFlow`s that mirror the Room aggregates). Re-creating the impl per resolution
 *    would be wasteful and would NOT partition state (the `combine` collector is per-subscriber).
 *  - [ObserveReadingStatisticsUseCase] → `factory`: stateless, cheap, matches the established
 *    "use case is a factory" pattern.
 *  - [StatisticsViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding so the screen
 *    survives configuration changes / pop-and-restore navigation. Mirrors `LibraryViewModel`
 *    and `ReaderViewModel`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster19.staleKdocSweep.cascade,
 * Task #475, 2026-05-28): two categories of fulfilled-prediction +
 * stale-citation entries appear above:
 *  - Lines 18-22 ("Legacy `:shared` `StatisticsViewModel` + its eight
 *    `Flow<Int>` / `Flow<String>` aggregates stay bound by `SharedModule`;
 *    both graphs coexist until the Phase 9.x user-facing route swap").
 *    FACTUALLY INVERTED + STALE — Phase 7.x.statistics.swap (§286)
 *    re-pointed `Screen.Statistics`'s rendering adapter to the rework
 *    `StatisticsScreen` already; Phase 9.x.statistics.retire (§349)
 *    deleted the legacy `:shared` `StatisticsViewModel` + its eight
 *    aggregate `Flow<Int>` / `Flow<String>` properties + the `SharedModule`
 *    `viewModel { StatisticsViewModel(...) }` binding. "Both graphs
 *    coexist" framing is now historical record of the foundation-slice
 *    posture; the legacy graph collapsed across §286 + §349. The "Phase
 *    9.x user-facing route swap" forecast happened as a §286 7.x-prefixed
 *    swap (earlier than predicted) followed by §349 9.x.retire. Mirror
 *    of §445 + §470 + §471 + §472 + §473 + §474 fulfilled-deferral-
 *    inversion precedent.
 *  - Lines 25-27 ("Legacy
 *    [me.manga.kira.presentation.features.statistics.domain.StatisticsRepository]
 *    (consumed by [ReadingStatisticsRepositoryImpl]) is the same
 *    `:shared` `single` declared by `SharedModule.kt`"). LIVE — the
 *    legacy `StatisticsRepository` interface + its `SharedModule` `single`
 *    binding STILL EXIST as the cell-of-truth that the rework `:data`
 *    [ReadingStatisticsRepositoryImpl] delegates to (verified by
 *    constructor `legacy = get()` at line 47 below). The strangler-fig
 *    posture stands; only the legacy presentation-layer VM was retired
 *    across §349.
 * The Koin scope-discipline + DIP/SRP rationale + lifecycle-choices
 * (single/factory/viewModel) sub-sections all stand on their own merits
 * past the §§286 + 349 fulfilled landings. The statisticsReworkModule
 * remains LIVE as the canonical Koin module for
 * `Screen.Statistics` + `Screen.StatisticsRework` (both now converge on
 * the rework path post-§286 swap). Original §253-era prose preserved
 * verbatim per the audit-trail-preservation convention — the citations
 * are historical record of the design lineage including the
 * deferred-route-swap forecast that was subsequently fulfilled across
 * §286 + §349.
 */
val statisticsReworkModule: Module = module {
    single<ReadingStatisticsRepository> { ReadingStatisticsRepositoryImpl(legacy = get()) }
    factory { ObserveReadingStatisticsUseCase(get()) }
    viewModel { StatisticsViewModel(get()) }
}
