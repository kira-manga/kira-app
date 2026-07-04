package me.manga.kira.di

import me.manga.kira.data.repository.DownloadsActionRepositoryImpl
import me.manga.kira.data.repository.DownloadsRepositoryImpl
import me.manga.kira.domain.repository.DownloadsActionRepository
import me.manga.kira.domain.repository.DownloadsRepository
import me.manga.kira.domain.usecase.downloads.CancelDownloadUseCase
import me.manga.kira.domain.usecase.downloads.CancelRunningDownloadUseCase
import me.manga.kira.domain.usecase.downloads.DeleteDownloadUseCase
import me.manga.kira.domain.usecase.downloads.DeleteDownloadedChapterUseCase
import me.manga.kira.domain.usecase.downloads.EnqueueDownloadUseCase
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.downloads.ReconcileDownloadsUseCase
import me.manga.kira.domain.usecase.downloads.RetryDownloadUseCase
import me.manga.kira.presentation.downloads.DownloadsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework Downloads slice (Phase 7.x.downloads.foundation + actions).
 *
 * Scope discipline (mirrors [statisticsReworkModule] / [historyReworkModule]):
 *  - Binds ONLY rework types: [DownloadsRepository] (`:domain`) →
 *    [DownloadsRepositoryImpl] (`:data`), [DownloadsActionRepository] (`:domain`) →
 *    [DownloadsActionRepositoryImpl] (`:data`), the 5 use cases (`:domain`), and
 *    [DownloadsViewModel] (`:presentation`).
 *  - The legacy `:shared`
 *    [me.manga.kira.presentation.features.download.domain.clean.DownloadRepository] +
 *    legacy [me.manga.kira.data.local.dao.ChapterDownloadDao] stay bound by the
 *    platform-specific `PlatformModule.{android,ios,desktop}.kt` files — Android wires the
 *    WorkManager-backed `DownloadRepositoryImpl`, iOS + Desktop share
 *    `CoroutineDownloadRepositoryImpl` via the `nonAndroidMain` source set; the DAO is
 *    declared as `single<ChapterDownloadDao> { get<MangaDatabase>().chapterDownloadingDao() }`
 *    in each `PlatformModule.*.kt`. Both Koin graphs coexist until the Phase 9.x
 *    user-facing route swap.
 *
 * Cross-module dependencies resolved at composition time:
 *  - Legacy `DownloadRepository` — consumed by both [DownloadsRepositoryImpl] (read) and
 *    [DownloadsActionRepositoryImpl] (write). Same `single` instance in both reaches.
 *  - Legacy `ChapterDownloadDao` — consumed by [DownloadsActionRepositoryImpl] for the
 *    retry-path row lookup (see impl KDoc for why this stretches one row wider than the
 *    read-side sibling).
 *
 * SRP (contract §6): one module = one feature slice.
 *
 * ISP (contract §6): two repository interfaces, one per concern (read /
 * write) — sibling pattern, same as `ComplaintListRepository` + `ComplaintActionRepository`.
 *
 * DIP (contract §6): the interfaces from `:domain` are bound to their `:data` impls at the
 * composition root. Presentation and UI see only the use cases / interfaces — not the
 * legacy `:shared` `DownloadRepository` or `ChapterDownloadDao`.
 *
 * Lifecycle choices:
 *  - [DownloadsRepository] → `single`: impl holds no per-call state; the underlying legacy
 *    `DownloadRepository` is already a singleton (it owns the in-process queue +
 *    Room-backed flow). Re-creating the impl per resolution would be wasteful AND would
 *    re-subscribe per-screen instead of sharing the upstream collector.
 *  - [DownloadsActionRepository] → `single`: same rationale — stateless transport whose
 *    collaborators are themselves singletons.
 *  - The 5 use cases ([ObserveDownloadsUseCase], [RetryDownloadUseCase],
 *    [CancelDownloadUseCase], [CancelRunningDownloadUseCase], [DeleteDownloadUseCase]) →
 *    `factory`: stateless, cheap, matches the established "use case is a factory" pattern.
 *  - [DownloadsViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding so the
 *    screen survives configuration changes / pop-and-restore navigation. The actions slice
 *    extends the constructor with the four mutation use cases — Koin resolves the
 *    additional positional `get()`s automatically.
 *
 * **Actions slice scope**: extends the foundation slice's read-display bindings with the
 * WRITE-side repository + 4 mutation use cases. The follow-on `Phase 7.x.downloads.swap`
 * commit redirects the user-reachable Settings Downloads row to the rework path.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster21.staleKdocSweep.cascade,
 * Task #477, 2026-05-28): two categories of fulfilled-prediction citations
 * appear above:
 *  - Lines 26-34 ("The legacy `:shared`
 *    [me.manga.kira.presentation.features.download.domain.clean.DownloadRepository]
 *    + legacy [me.manga.kira.data.local.dao.ChapterDownloadDao] stay
 *    bound by the platform-specific
 *    `PlatformModule.{android,ios,desktop}.kt` files — Android wires
 *    the WorkManager-backed `DownloadRepositoryImpl`, iOS + Desktop
 *    share `CoroutineDownloadRepositoryImpl` via the `nonAndroidMain`
 *    source set; the DAO is declared as `single<ChapterDownloadDao> {
 *    get<MangaDatabase>().chapterDownloadingDao() }` in each
 *    `PlatformModule.*.kt`. Both Koin graphs coexist until the Phase
 *    9.x user-facing route swap"). PARTIALLY-FULFILLED-INVERSION —
 *    Phase 7.x.downloads.swap (§295) re-pointed the Settings Downloads
 *    row + `Screen.DownloadsScreen` to the rework `DownloadsScreen`
 *    already; Phase 9.x.downloads.legacyui.retire (§352) deleted the
 *    legacy `:shared` `DownloadsScreen.kt` UI; Phase
 *    9.x.downloadvmv2.retire (§439) deleted the cascade-orphan legacy
 *    download VM; Phase 9.x.downloadrepository.componentprune.cascade
 *    (§440) + 9.x.chapterdownloaddao.componentprune.cascade (§441)
 *    pruned orphan members. HOWEVER — the legacy `DownloadRepository`
 *    interface + its WorkManager-backed Android impl +
 *    CoroutineDownloadRepositoryImpl iOS/Desktop impl + the
 *    `ChapterDownloadDao` STILL EXIST as the cell-of-truth that the
 *    rework `:data` impls delegate to via `legacy = get()` /
 *    `chapterDownloadDao = get()` (verified at lines 72-78 below).
 *    "Both Koin graphs coexist" framing is half-fulfilled: only one
 *    consumer-side UI graph survives (the rework one), but two
 *    transport graphs coexist (legacy WorkManager-backed repository +
 *    DAO providing the cell-of-truth; rework `:data` adapters
 *    delegating to it). The "Phase 9.x user-facing route swap"
 *    forecast happened as a §295 7.x-prefixed swap (earlier than
 *    predicted) followed by §352 + §439 + §440 + §441 9.x retires.
 *    Mirror of §474 `WhatsNewScreenRoute.kt` half-fulfilled-retire
 *    precedent.
 *  - Lines 67-69 ("The follow-on `Phase 7.x.downloads.swap` commit
 *    redirects the user-reachable Settings Downloads row to the rework
 *    path"). FULFILLED — §295 executed exactly that redirect; the
 *    actions-slice scope-paragraph's forecast is now historical
 *    record.
 * The DI scope-discipline + ISP (READ + WRITE repo split) + SRP/DIP
 * rationale + lifecycle-choices (single/factory/viewModel) sub-sections
 * all stand on their own merits past the §§295 + 352 + 439 + 440 + 441
 * fulfilled landings. The downloadsReworkModule remains LIVE as the
 * canonical Koin module for `Screen.DownloadsScreen` +
 * `Screen.DownloadsRework` (both now converge on the rework path
 * post-§295 swap). Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citations are historical
 * record of the design lineage including the deferred-route-swap
 * forecast that was subsequently fulfilled across §§295 + 352.
 */
val downloadsReworkModule: Module = module {
    single<DownloadsRepository> { DownloadsRepositoryImpl(legacy = get()) }
    single<DownloadsActionRepository> {
        DownloadsActionRepositoryImpl(
            legacy = get(),
            chapterDownloadDao = get(),
            chapterDao = get(),
            appFileSystem = get(),
            fileService = get(),
        )
    }
    factory { ObserveDownloadsUseCase(get()) }
    factory { EnqueueDownloadUseCase(get()) }
    factory { RetryDownloadUseCase(get()) }
    factory { CancelDownloadUseCase(get()) }
    factory { CancelRunningDownloadUseCase(get()) }
    factory { DeleteDownloadUseCase(get()) }
    factory { DeleteDownloadedChapterUseCase(get()) }
    factory { ReconcileDownloadsUseCase(get()) }
    viewModel { DownloadsViewModel(get(), get(), get(), get(), get()) }
}
