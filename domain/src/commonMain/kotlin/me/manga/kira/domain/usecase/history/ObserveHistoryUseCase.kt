package me.manga.kira.domain.usecase.history

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.history.HistoryEntry
import me.manga.kira.domain.repository.HistoryRepository

/**
 * Observe the user's reading history.
 *
 * Phase 7.x.history rework. The rework `HistoryViewModel` injects this use case and subscribes
 * in `init {}` to project each `List<HistoryEntry>` emission into its MVI state.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [HistoryRepository.observeHistory]". The
 * Room-flow plumbing lives in the `:data` impl; the use case is a stable presentation-layer
 * dependency and a test seam (mock the use case in `HistoryViewModelTest` instead of mocking
 * the repository).
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [me.manga.kira.domain.usecase.library.ObserveLibraryUseCase] and
 * [me.manga.kira.domain.usecase.statistics.ObserveReadingStatisticsUseCase] — presentation
 * depends on use cases (DIP), not on repositories directly; future composition (filter by
 * source, group by manga, cross-feature joins) lives in the use case, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `historyReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster112.staleKdocSweep.cascade,
 * Task #568, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-second sibling of the cluster57-111 sweep — opens
 * the wave-12 `:domain/usecase/history/` batch alongside DeleteHistory-
 * EntryUseCase.kt plus DeleteAllHistoryUseCase.kt, partnering the
 * cluster102 sibling sweep of `:presentation/history/`):
 *  (a) "Phase 7.x.history rework — rework HistoryViewModel injects this
 *  use case plus subscribes in init {} to project each List<HistoryEntry>
 *  emission into its MVI state" — LIVE-NOT-STALE. HistoryViewModel.kt
 *  L114-120 init block hosts `observeHistory().onEach { snapshot ->
 *  updateState { it.copy(isLoading = false, items = snapshot) } }.
 *  launchIn(viewModelScope)` collector; cluster102 sibling sweep (Task
 *  #558) verified the init-collector posture.
 *  (b) "Contract §6 SRP owns ONE rule — delegate to HistoryRepository.
 *  observeHistory; the Room-flow plumbing lives in the `:data` impl; the
 *  use case is a stable presentation-layer dependency plus a test seam"
 *  — LIVE-NOT-STALE. L30 realization `repository.observeHistory()`
 *  single-line pass-through; HistoryRepositoryImpl `:data` impl verified
 *  at cluster25 sibling sweep (Task #481) — delegates to legacy History-
 *  Repository.getAllHistoryFlow() through entity mapping.
 *  (c) "Why a use case at all when this is a single-line pass-through —
 *  presentation depends on use cases (DIP), not on repositories
 *  directly; peer cross-ref to ObserveLibraryUseCase plus ObserveReading-
 *  StatisticsUseCase" — LIVE-NOT-STALE. Peer use cases verified at
 *  cluster26 sibling sweep (Task #482).
 *  (d) "Future composition (filter by source, group by manga, cross-
 *  feature joins) lives in the use case, not in the VM" — FORECAST-NOT-
 *  YET-FULFILLED. Recursive search for filter-by-source / group-by-
 *  manga / cross-feature-join operators on this use case returns zero
 *  matches; the use case remains a single-line pass-through. Forecast
 *  posture preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `historyReworkModule`" — LIVE-NOT-STALE. HistoryRework-
 *  Module.kt L101 `factory { ObserveHistoryUseCase(get()) }` realization
 *  confirms factory lifecycle (stateless, cheap to construct, never
 *  shared).
 *  Five classifications STAND on their own merits as a faithful
 *  ObserveHistoryUseCase manifest. Original Phase 7.x.history-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class ObserveHistoryUseCase(
    private val repository: HistoryRepository,
) {
    operator fun invoke(): Flow<List<HistoryEntry>> = repository.observeHistory()
}
