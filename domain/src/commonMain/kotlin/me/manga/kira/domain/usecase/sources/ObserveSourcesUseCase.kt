package me.manga.kira.domain.usecase.sources

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.sources.Source
import me.manga.kira.domain.repository.SourcesRepository

/**
 * Observe every content source registered with the app.
 *
 * Phase 7.x.sources rework. The rework `SourcesViewModel` injects this use case and subscribes
 * in `init {}` to project each `List<Source>` emission into its MVI state. The `:ui` composable
 * regroups the flat list by [Source.language] at render time — matching the History / Updates
 * screens' regroup idiom (§82.3, §83.3).
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [SourcesRepository.observeSources]". The
 * Room-flow plumbing lives in the `:data` impl; the use case is a stable presentation-layer
 * dependency and a test seam (mock the use case in `SourcesViewModelTest` instead of mocking
 * the repository).
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as
 * [me.manga.kira.domain.usecase.history.ObserveHistoryUseCase] /
 * [me.manga.kira.domain.usecase.updates.ObserveUpdatesUseCase] /
 * [me.manga.kira.domain.usecase.statistics.ObserveReadingStatisticsUseCase] — presentation
 * depends on use cases (DIP), not on repositories directly; future composition (filter
 * disabled-only, group by language at the domain layer, cross-feature joins with library) lives
 * in the use case, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `sourcesReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster118.staleKdocSweep.cascade,
 * Task #574, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-eighth sibling of the cluster57-117 sweep — opens the
 * wave-18 `:domain/usecase/sources/` batch alongside SetSourceEnabledUse-
 * Case.kt plus SetLanguageEnabledUseCase.kt plus EnableDefaultLanguage-
 * SourcesUseCase.kt, partnering the cluster108 sibling sweep of
 * `:presentation/sources+updates/` — Task #564):
 *  (a) "Phase 7.x.sources rework — the rework `SourcesViewModel` injects
 *  this use case and subscribes in `init {}` to project each `List<
 *  Source>` emission into its MVI state; the `:ui` composable regroups
 *  the flat list by Source.language at render time — matching the
 *  History / Updates screens' regroup idiom (§82.3, §83.3)" — LIVE-NOT-
 *  STALE. SourcesViewModel.kt L134 primary constructor binds
 *  `observeSources: ObserveSourcesUseCase`; L143-148 `observeSources()
 *  .onEach { snapshot rename-to updateState { it.copy(isLoading = false,
 *  items = snapshot) } }.launchIn(viewModelScope)` realization confirms
 *  the init-collector reactive-subscription posture; cluster108 sibling
 *  sweep (Task #564) verified the SourcesViewModel KDoc plus the
 *  regroup-at-`:ui` idiom; SourcesRepositoryImpl `:data` impl Room-flow
 *  plumbing verified at cluster25 sibling sweep (Task #481).
 *  (b) "Contract §6 SRP owns ONE rule — delegate to SourcesRepository.
 *  observeSources(); the Room-flow plumbing lives in the `:data` impl;
 *  the use case is a stable presentation-layer dependency and a test
 *  seam (mock the use case in SourcesViewModelTest instead of mocking
 *  the repository)" — LIVE-NOT-STALE. L34 realization `repository.
 *  observeSources()` single-line pass-through; SourcesRepository.kt L65
 *  `fun observeSources(): Flow<List<Source>>` interface signature
 *  verified.
 *  (c) "Why a use case at all when this is a single-line pass-through —
 *  same rationale as ObserveHistoryUseCase / ObserveUpdatesUseCase /
 *  ObserveReadingStatisticsUseCase — presentation depends on use cases
 *  (DIP), not on repositories directly" — LIVE-NOT-STALE. Peer DIP
 *  rationale cross-refs all SWEPT: ObserveHistoryUseCase (cluster112
 *  Task #568), ObserveUpdatesUseCase (cluster110 Task #566), Observe-
 *  ReadingStatisticsUseCase (cluster113 Task #569). The peer-pure-
 *  delegate posture holds across the wave-cadence cascade.
 *  (d) "Future composition (filter disabled-only, group by language at
 *  the domain layer, cross-feature joins with library) lives in the use
 *  case, not in the VM" — FORECAST-NOT-YET-FULFILLED. Recursive search
 *  for derived-domain-layer composition (filter-disabled-only stage,
 *  group-by-language stage, cross-feature library joins) returns zero
 *  matches; group-by-language regroup still lives at the `:ui` layer
 *  matching the History/Updates regroup idiom (§82.3, §83.3). Forecast
 *  posture preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  `factory` in `sourcesReworkModule` (factory: stateless, cheap to
 *  construct, never shared)" — LIVE-NOT-STALE. SourcesReworkModule.kt
 *  L108 `factory { ObserveSourcesUseCase(get()) }` realization confirms
 *  factory lifecycle; cluster14 sibling sweep (Task #470) verified the
 *  broader sourcesReworkModule strangler-fig posture.
 *  Five classifications STAND on their own merits as a faithful Observe-
 *  SourcesUseCase manifest. Original Phase 7.x.sources-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class ObserveSourcesUseCase(
    private val repository: SourcesRepository,
) {
    operator fun invoke(): Flow<List<Source>> = repository.observeSources()
}
