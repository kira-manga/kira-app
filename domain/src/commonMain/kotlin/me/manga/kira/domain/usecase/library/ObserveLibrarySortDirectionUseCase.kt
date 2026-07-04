package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.library.SortDirection
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Observe the user's persisted Library sort direction as a live stream.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.observeSortDirection]".
 * Paired with [SetLibrarySortDirectionUseCase]; both flow into / out of the VM's
 * `LibraryIntent.OnSortDirectionToggle` boundary alongside the sort-mode pair.
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster128.staleKdocSweep.cascade,
 * Task #584, 2026-05-28): classified as follows after recursive symbol
 * verification (ninety-fourth sibling of the cluster57-127 sweep —
 * fourth file of the wave-23 `:domain/usecase/library/` 5-file refresh-
 * state plus sort-axis-pair batch alongside ObserveLibraryRefresh plus
 * ObserveLibrarySort plus SetLibrarySort plus SetLibrarySortDirection):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.observeSort-
 *  Direction + paired-with-SetLibrarySortDirection + both-flow-into-out-
 *  of-VM-OnSortDirectionToggle-boundary-alongside-sort-mode-pair" — LIVE-
 *  NOT-STALE + FULFILLED-PREDICTION. LibraryViewModel.kt L32 import,
 *  L96 ctor `private val observeLibrarySortDirection: ObserveLibrary-
 *  SortDirectionUseCase`, L157 realization `observeLibrarySortDirection
 *  ().onEach { direction -> updateState { it.copy(sortDirection =
 *  direction, items = applyView(...)) } }.launchIn(viewModelScope)`
 *  inside VM `init {}` — mirror-block-pattern relative to the 92nd
 *  sibling's L146 sort-mode collector. The "alongside sort-mode pair"
 *  framing stands verbatim (sort-mode pair siblings 92+93; sort-
 *  direction pair siblings 94+95 — all 4 wired into VM init block per
 *  ARCHITECTURE.md L24094-24107 "init {} adds two observer flows
 *  (alongside the existing observeLibraryRefresh)" + "Mirror block for
 *  observeLibrarySortDirection()").
 *  (b) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-
 *  NOT-STALE. LibraryReworkModule.kt L18 import, L130 `factory {
 *  ObserveLibrarySortDirectionUseCase(get()) }` realization; L161
 *  `observeLibrarySortDirection = get()` VM ctor wiring confirmed.
 *  Two classifications STAND on their own merits. Original Phase 7.x.
 *  library.sort.persist-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class ObserveLibrarySortDirectionUseCase(
    private val repository: LibraryPrefsRepository,
) {
    operator fun invoke(): Flow<SortDirection> = repository.observeSortDirection()
}
