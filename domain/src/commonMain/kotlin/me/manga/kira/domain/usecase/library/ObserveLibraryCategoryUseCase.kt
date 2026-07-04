package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Observe the user's persisted Library category tab as a live stream.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.observeCategory]".
 * Mirrors [ObserveLibrarySortUseCase] / [ObserveLibrarySortDirectionUseCase] /
 * [ObserveLibraryFilterUseCase] / [ObserveLibraryGridDensityUseCase] — same one-line delegate
 * shape that gives the VM a narrow, intent-specific dependency rather than a wide repository
 * handle.
 *
 * The Library VM's `init {}` collects this flow and projects each emission into
 * [me.manga.kira.presentation.library.LibraryState.category], then re-runs the `applyView`
 * pipeline so the new category narrowing takes immediate effect on the visible items. Persistence
 * parity with the §154 filter-persistence, §157 density-persistence slices — same shape, fifth
 * axis on the same repository.
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster130.staleKdocSweep.cascade,
 * Task #586, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundredth sibling of the cluster57-129 sweep — first
 * file of the wave-23 `:domain/usecase/library/` 4-file category plus
 * lastUpdated plus display-observer batch alongside SetLibraryCategory
 * plus ObserveLibraryLastUpdated plus ObserveLibraryDisplay; opens
 * cluster130):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.observeCategory
 *  + mirrors-ObserveLibrarySort-ObserveLibrarySortDirection-ObserveLibrary-
 *  Filter-ObserveLibraryGridDensity-same-one-line-delegate-shape-narrow-
 *  intent-specific-dependency" — LIVE-NOT-STALE + FULFILLED-PREDICTION.
 *  LibraryViewModel.kt L26 import, L102 ctor `private val
 *  observeLibraryCategory: ObserveLibraryCategoryUseCase`, L183
 *  realization `observeLibraryCategory().onEach { category ->
 *  updateState { it.copy(category = category, items = applyView(allItems,
 *  it.searchQuery, it.sort, it.sortDirection, it.randomSeed, it.filter,
 *  category)) } }.launchIn(viewModelScope)` inside VM init {} — re-runs
 *  applyView on every emission so the new category narrowing takes
 *  immediate effect on the visible items. The five-axis-mirror chain
 *  (sort, sortDirection, filter, gridDensity, category) is preserved
 *  verbatim across the wave-23 cycle.
 *  (b) "Persistence parity with §154 filter-persistence + §157 density-
 *  persistence slices — same shape, fifth axis on the same repository" —
 *  LIVE-FRAMING + FULFILLED-PREDICTION. The §158.persist category-
 *  persistence slice (Task #325, completed) is the canonical introducer;
 *  same-repo-fifth-axis framing stands.
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-
 *  NOT-STALE. LibraryReworkModule.kt L12 import, L136 `factory {
 *  ObserveLibraryCategoryUseCase(get()) }` realization. Three
 *  classifications STAND on their own merits. Original Phase 7.x.
 *  library.category.persist-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class ObserveLibraryCategoryUseCase(
    private val repository: LibraryPrefsRepository,
) {
    operator fun invoke(): Flow<LibraryCategory> = repository.observeCategory()
}
