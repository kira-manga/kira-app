package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.library.GridDensity
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Observe the user's persisted Library grid density as a live stream.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.observeGridDensity]".
 * Mirrors [ObserveLibrarySortUseCase] / [ObserveLibrarySortDirectionUseCase] /
 * [ObserveLibraryFilterUseCase] — same one-line delegate shape that gives the VM a narrow,
 * intent-specific dependency rather than a wide repository handle.
 *
 * The Library VM's `init {}` collects this flow and projects each emission into
 * [me.manga.kira.presentation.library.LibraryState.gridDensity]. Unlike sort / filter,
 * `applyView` is NOT re-run because density only changes how the same `items` list is laid out
 * (the `:ui` adaptive grid recomposes on the `state.gridDensity` flip because its `minSize`
 * parameter is derived from it). Persistence parity with the §154 filter-persistence slice —
 * same shape, just a fourth axis on the same repository.
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster129.staleKdocSweep.cascade,
 * Task #585, 2026-05-28): classified as follows after recursive symbol
 * verification (ninety-eighth sibling of the cluster57-128 sweep —
 * third file of the wave-23 `:domain/usecase/library/` 4-file filter
 * plus grid-density Observe/Set-pair batch alongside ObserveLibrary-
 * Filter plus SetLibraryFilter plus SetLibraryGridDensity):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.observeGrid-
 *  Density + mirrors-ObserveLibrarySort-ObserveLibrarySortDirection-
 *  ObserveLibraryFilter-same-one-line-delegate-shape" — LIVE-NOT-STALE
 *  + FULFILLED-PREDICTION. LibraryViewModel.kt L29 import, L100 ctor
 *  `private val observeLibraryGridDensity:
 *  ObserveLibraryGridDensityUseCase`, L179 realization
 *  `observeLibraryGridDensity()...launchIn(viewModelScope)` inside VM
 *  `init {}`. The four-axis-mirror chain (sort, sortDirection, filter,
 *  gridDensity) is preserved verbatim — all four observers fire from
 *  VM init {} per the wave-23 cycle's established pattern.
 *  (b) "applyView-NOT-re-run-because-density-only-changes-how-same-items-
 *  list-is-laid-out + :ui-adaptive-grid-recomposes-on-state.gridDensity-
 *  flip-because-minSize-parameter-derived-from-it" — LIVE-NOT-STALE +
 *  FULFILLED-PREDICTION. The deliberate asymmetry vs the sort/filter
 *  observers (which re-apply applyView) stands — density is a layout-
 *  only axis. VM L179 collector projects to state.gridDensity without
 *  invoking applyView, matching the prose verbatim.
 *  (c) "Persistence parity with the §154 filter-persistence slice + §6
 *  DIP + Koin factory binding" — LIVE-NOT-STALE + FULFILLED-PREDICTION.
 *  LibraryReworkModule.kt L15 import, L134 `factory {
 *  ObserveLibraryGridDensityUseCase(get()) }` realization. The §155
 *  grid-density-persistence slice (Task #322 + #323, completed) is the
 *  canonical introducer; same-repo-fourth-axis framing stands. Three
 *  classifications STAND on their own merits. Original Phase 7.x.
 *  library.display.persist-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class ObserveLibraryGridDensityUseCase(
    private val repository: LibraryPrefsRepository,
) {
    operator fun invoke(): Flow<GridDensity> = repository.observeGridDensity()
}
