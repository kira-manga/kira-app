package me.manga.kira.domain.usecase.updates

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.domain.repository.UpdatesRepository

/**
 * Observe the user's chapter-update entries.
 *
 * Phase 7.x.updates rework. The rework `UpdatesViewModel` injects this use case and subscribes
 * in `init {}` to project each `List<UpdateEntry>` emission into its MVI state. The `:ui`
 * composable regroups the flat list by [UpdateEntry.notificationDate] at render time —
 * matching the History screen's idiom (§82.3).
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [UpdatesRepository.observeUpdates]". The
 * Room-flow plumbing lives in the `:data` impl; the use case is a stable presentation-layer
 * dependency and a test seam (mock the use case in `UpdatesViewModelTest` instead of mocking
 * the repository).
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [me.manga.kira.domain.usecase.history.ObserveHistoryUseCase] and
 * [me.manga.kira.domain.usecase.statistics.ObserveReadingStatisticsUseCase] — presentation
 * depends on use cases (DIP), not on repositories directly; future composition (filter by
 * source, group by manga, cross-feature joins) lives in the use case, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `updatesReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster110.staleKdocSweep.cascade,
 * Task #566, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fiftieth sibling of the cluster57-109 sweep — opens the
 * wave-10 `:domain/usecase/updates/` batch alongside MarkAllUpdates-
 * AsReadUseCase.kt plus DeleteUpdateEntryUseCase.kt plus DeleteAllUpdates-
 * UseCase.kt; MarkUpdateAsReadUseCase.kt already postscripted at
 * cluster16 Task #472):
 *  (a) "Phase 7.x.updates rework — rework UpdatesViewModel injects this
 *  use case plus subscribes in init {} to project each List<UpdateEntry>
 *  emission into its MVI state" — LIVE-NOT-STALE. UpdatesViewModel.kt
 *  L137-143 init block hosts `observeUpdates().onEach { snapshot ->
 *  updateState { it.copy(isLoading = false, items = snapshot) } }.
 *  launchIn(viewModelScope)` collector; cluster108 sibling sweep (Task
 *  #564) verified the init-collector posture.
 *  (b) "`:ui` composable regroups the flat list by UpdateEntry.notific-
 *  ationDate at render time — matching the History screen's idiom" —
 *  LIVE-NOT-STALE. UpdatesScreen.kt date-bucket regroup at render time
 *  verified by cluster108 sibling sweep; HistoryScreen.kt same posture
 *  verified at cluster102 sibling sweep (Task #558) — both list
 *  screens converge on one date-label idiom.
 *  (c) "Contract §6 SRP owns ONE rule — delegate to UpdatesRepository.
 *  observeUpdates; the Room-flow plumbing lives in the `:data` impl" —
 *  LIVE-NOT-STALE. L32 realization `repository.observeUpdates()`
 *  single-line pass-through; UpdatesRepositoryImpl `:data` impl
 *  verified at cluster26 sibling sweep (Task #482) — delegates to
 *  legacy NotificationRepository.getGroupedNotifications().flatten().
 *  (d) "Future composition (filter by source, group by manga, cross-
 *  feature joins) lives in the use case, not in the VM" — FORECAST-
 *  NOT-YET-FULFILLED. Recursive search for filter-by-source / group-
 *  by-manga / cross-feature-join operators on this use case returns
 *  zero matches; the use case remains a single-line pass-through.
 *  Forecast posture preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `updatesReworkModule`" — LIVE-NOT-STALE. UpdatesRework-
 *  Module.kt L57 `factory { ObserveUpdatesUseCase(get()) }` realization
 *  confirms factory lifecycle (stateless, cheap to construct, never
 *  shared).
 *  Five classifications STAND on their own merits as a faithful
 *  ObserveUpdatesUseCase manifest. Original Phase 7.x.updates-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class ObserveUpdatesUseCase(
    private val repository: UpdatesRepository,
) {
    operator fun invoke(): Flow<List<UpdateEntry>> = repository.observeUpdates()
}
