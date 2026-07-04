package me.manga.kira.di

import me.manga.kira.data.repository.HistoryRepositoryImpl
import me.manga.kira.domain.repository.HistoryRepository
import me.manga.kira.domain.usecase.history.DeleteAllHistoryUseCase
import me.manga.kira.domain.usecase.history.DeleteHistoryEntryUseCase
import me.manga.kira.domain.usecase.history.ObserveHistoryUseCase
import me.manga.kira.presentation.history.HistoryViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework History slice (Phase 7.x.history).
 *
 * Scope discipline (mirrors [statisticsReworkModule] / [readerReworkModule]):
 *  - Binds ONLY rework types: [HistoryRepository] (`:domain`) →
 *    [HistoryRepositoryImpl] (`:data`), three use cases (`:domain`), and [HistoryViewModel]
 *    (`:presentation`).
 *  - Legacy `:shared` `HistoryViewModel` + the
 *    [me.manga.kira.presentation.features.history.domain.HistoryRepository] facade it
 *    consumes stay bound by `SharedModule`; both graphs coexist until Phase 9.x's user-facing
 *    route swap. Both routes consume the SAME legacy `HistoryItemD` Room table through the
 *    legacy facade — so adding a chapter to history in EITHER reader updates the list on BOTH
 *    History screens.
 *
 * Cross-module dependencies resolved at composition time:
 *  - The Room `HistoryDao` (the constructor dep of [HistoryRepositoryImpl]) is bound `single` by
 *    each `PlatformModule.{android,ios,desktop}` actual via
 *    `single<HistoryDao> { get<MangaDatabase>().historyDao() }`. [HistoryRepositoryImpl] talks to
 *    the DAO directly — see its KDoc for the SRP/DIP rationale.
 *
 * SRP (contract §6): one module = one feature slice.
 *
 * DIP (contract §6): the [HistoryRepository] interface from `:domain` is bound to its `:data`
 * impl at the composition root. Presentation and UI see only the use cases / interface.
 *
 * Lifecycle choices:
 *  - [HistoryRepository] → `single`: impl holds no per-call state; the underlying `HistoryDao`
 *    is already a singleton (it re-emits the `getAllHistory()` flow on every write). Re-creating
 *    the impl per resolution would mean resubscribing `getAllHistory()` for each consumer —
 *    wasteful for a read-mostly surface shared across the app's lifetime.
 *  - Three use cases (`ObserveHistoryUseCase`, `DeleteHistoryEntryUseCase`,
 *    `DeleteAllHistoryUseCase`) → `factory`: stateless thin pass-throughs, cheap; matches the
 *    established "use case is a factory" pattern.
 *  - [HistoryViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding so the screen
 *    survives configuration changes / pop-and-restore navigation. Mirrors `LibraryViewModel`
 *    and `ReaderViewModel`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster19.staleKdocSweep.cascade,
 * Task #475, 2026-05-28): two categories of fulfilled-prediction +
 * stale-citation entries appear above:
 *  - Lines 20-25 ("Legacy `:shared` `HistoryViewModel` + the
 *    [me.manga.kira.presentation.features.history.domain.HistoryRepository]
 *    facade it consumes stay bound by `SharedModule`; both graphs coexist
 *    until Phase 9.x's user-facing route swap. Both routes consume the
 *    SAME legacy `HistoryItemD` Room table through the legacy facade — so
 *    adding a chapter to history in EITHER reader updates the list on
 *    BOTH History screens"). FACTUALLY INVERTED + STALE — Phase
 *    7.x.history.swap (§288) re-pointed `Screen.History`'s rendering
 *    adapter to the rework `HistoryScreen` already; Phase
 *    9.x.history.legacyui.retire (§357) deleted the legacy `:shared`
 *    `HistoryScreen.kt` UI + its `HistoryViewModel` consumer + the
 *    `SharedModule` `viewModel { HistoryViewModel(...) }` binding.
 *    "Both graphs coexist" framing is now historical record of the
 *    foundation-slice posture; the legacy graph collapsed across §288 +
 *    §357. The "BOTH History screens reflect the change" reactive-flow
 *    parity claim is moot — only one screen exists post-§357. The "Phase
 *    9.x user-facing route swap" forecast happened as a §288 7.x-prefixed
 *    swap (earlier than predicted) followed by §357 9.x.retire. Mirror
 *    of §445 + §470 + §471 + §472 + §473 + §474 fulfilled-deferral-
 *    inversion precedent.
 *  - Lines 28-33 ("Legacy
 *    [me.manga.kira.presentation.features.history.domain.HistoryRepository]
 *    (the constructor dep of [HistoryRepositoryImpl]) is bound `single`
 *    by `SharedModule.kt:235` already (and is reused by the legacy
 *    history VM)"). HALF-LIVE — the legacy `HistoryRepository` facade
 *    interface + its `SharedModule` `single` binding STILL EXIST as the
 *    cell-of-truth that the rework `:data` [HistoryRepositoryImpl]
 *    delegates to (verified by constructor `legacy = get()` at line 54
 *    below); "reused by the legacy history VM" framing is the stale half
 *    (the legacy `HistoryViewModel` was retired across §357), but the
 *    facade itself stands. The `SharedModule.kt:235` line anchor may
 *    have shifted post-§357 binding removal; downstream readers should
 *    re-`grep` for the binding rather than trust the line number.
 * The Koin scope-discipline + DIP/SRP rationale + lifecycle-choices
 * (single/factory/viewModel) sub-sections all stand on their own merits
 * past the §§288 + 357 fulfilled landings. The historyReworkModule
 * remains LIVE as the canonical Koin module for `Screen.History` +
 * `Screen.HistoryRework` (both now converge on the rework path post-
 * §288 swap). Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citations are historical
 * record of the design lineage including the deferred-route-swap
 * forecast that was subsequently fulfilled across §288 + §357.
 */
val historyReworkModule: Module = module {
    single<HistoryRepository> { HistoryRepositoryImpl(historyDao = get()) }
    factory { ObserveHistoryUseCase(get()) }
    factory { DeleteHistoryEntryUseCase(get()) }
    factory { DeleteAllHistoryUseCase(get()) }
    viewModel { HistoryViewModel(get(), get(), get()) }
}
