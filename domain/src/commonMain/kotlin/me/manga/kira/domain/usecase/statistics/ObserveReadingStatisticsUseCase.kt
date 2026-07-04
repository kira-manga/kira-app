package me.manga.kira.domain.usecase.statistics

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.statistics.ReadingStatistics
import me.manga.kira.domain.repository.ReadingStatisticsRepository

/**
 * Observe the user's reading-statistics snapshot.
 *
 * Phase 7.x.statistics rework. The rework `StatisticsViewModel` injects this use case and
 * subscribes in `init {}` to project each [ReadingStatistics] emission into its MVI state.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [ReadingStatisticsRepository.observe]". The 8-flow
 * `combine` lives in the `:data` impl; the use case is a stable presentation-layer dependency and
 * a test seam (mock the use case in `StatisticsViewModelTest` instead of mocking the repository).
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [me.manga.kira.domain.usecase.library.ObserveLibraryUseCase] — presentation depends on use
 * cases, not on repositories directly (DIP); future composition (filter / sort / cross-feature
 * joins like "include source-by-source breakdown") lives in the use case, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `statisticsReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster113.staleKdocSweep.cascade,
 * Task #569, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-third sibling of the cluster57-112 sweep — solo
 * wave-13 `:domain/usecase/statistics/` batch partnering the cluster103
 * sibling sweep of `:presentation/statistics/`):
 *  (a) "Phase 7.x.statistics rework — rework StatisticsViewModel injects
 *  this use case plus subscribes in init {} to project each Reading-
 *  Statistics emission into its MVI state" — LIVE-NOT-STALE. Statistics-
 *  ViewModel.kt L91-99 primary constructor binds `observeReading-
 *  Statistics: ObserveReadingStatisticsUseCase`; init block (L97
 *  onwards) hosts the `observeReadingStatistics().onEach { ... }.
 *  launchIn(viewModelScope)` collector; cluster103 sibling sweep (Task
 *  #559) verified the init-collector posture.
 *  (b) "Contract §6 SRP owns ONE rule — delegate to ReadingStatistics-
 *  Repository.observe; the 8-flow combine lives in the `:data` impl;
 *  the use case is a stable presentation-layer dependency plus a test
 *  seam" — LIVE-NOT-STALE. L28 realization `repository.observe()`
 *  single-line pass-through; ReadingStatisticsRepositoryImpl `:data`
 *  impl 8-flow combine realization verified at cluster25 sibling sweep
 *  (Task #481) — `combine(totalReadFlow, mangaReadFlow, chaptersRead-
 *  Flow, savedMangaFlow, sourcesFlow, readMinutesFlow, mostReadCategory-
 *  Flow, downloadsCountFlow) { ... }` projects to ReadingStatistics.
 *  (c) "Why a use case at all when this is a single-line pass-through —
 *  presentation depends on use cases (DIP), not on repositories
 *  directly; peer cross-ref to ObserveLibraryUseCase" — LIVE-NOT-STALE.
 *  Peer use case ObserveLibraryUseCase verified at cluster26 sibling
 *  sweep (Task #482) — same pure-delegate posture.
 *  (d) "Future composition (filter / sort / cross-feature joins like
 *  `include source-by-source breakdown`) lives in the use case, not in
 *  the VM" — FORECAST-NOT-YET-FULFILLED. Recursive search for filter
 *  plus sort plus source-by-source-breakdown decoration on this use
 *  case returns zero matches; the use case remains a single-line pass-
 *  through. Forecast posture preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `statisticsReworkModule`" — LIVE-NOT-STALE. Statistics-
 *  ReworkModule.kt L87 `factory { ObserveReadingStatisticsUseCase(get()) }`
 *  realization confirms factory lifecycle (stateless, cheap to
 *  construct, never shared).
 *  Five classifications STAND on their own merits as a faithful
 *  ObserveReadingStatisticsUseCase manifest. Original Phase 7.x.
 *  statistics-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
class ObserveReadingStatisticsUseCase(
    private val repository: ReadingStatisticsRepository,
) {
    operator fun invoke(): Flow<ReadingStatistics> = repository.observe()
}
