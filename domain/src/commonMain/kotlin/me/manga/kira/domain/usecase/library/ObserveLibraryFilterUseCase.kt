package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Observe the user's persisted Library filter axis as a live stream.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.observeFilter]". Mirrors
 * [ObserveLibrarySortUseCase] / [ObserveLibrarySortDirectionUseCase] — same one-line delegate
 * shape that gives the VM a narrow, intent-specific dependency rather than a wide repository
 * handle.
 *
 * The Library VM's `init {}` collects this flow and projects each emission into
 * [me.manga.kira.presentation.library.LibraryState.filter] (re-applying `applyView` on every
 * change so the displayed grid recomposes). Persistence parity with the §152 sort-persistence
 * slice — same shape, just a third axis on the same repository.
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster129.staleKdocSweep.cascade,
 * Task #585, 2026-05-28): classified as follows after recursive symbol
 * verification (ninety-sixth sibling of the cluster57-128 sweep — first
 * file of the wave-23 `:domain/usecase/library/` 4-file filter plus
 * grid-density Observe/Set-pair batch alongside SetLibraryFilter plus
 * ObserveLibraryGridDensity plus SetLibraryGridDensity; opens
 * cluster129):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.observeFilter
 *  + mirrors-ObserveLibrarySort-ObserveLibrarySortDirection-same-one-
 *  line-delegate-shape-narrow-intent-specific-dependency" — LIVE-NOT-
 *  STALE + FULFILLED-PREDICTION. LibraryViewModel.kt L28 import, L98
 *  ctor `private val observeLibraryFilter: ObserveLibraryFilterUseCase`,
 *  L168 realization `observeLibraryFilter().onEach { filter ->
 *  updateState { it.copy(filter = filter, items = applyView(allItems,
 *  it.searchQuery, it.sort, it.sortDirection, it.randomSeed, filter,
 *  it.category)) } }.launchIn(viewModelScope)` inside VM `init {}` —
 *  the "Library VM init {} collects this flow and projects each
 *  emission into LibraryState.filter + re-applies applyView" prediction
 *  stands verbatim. Cross-ref to the 92nd + 94th siblings (Observe-
 *  LibrarySort + ObserveLibrarySortDirection) confirms the one-line-
 *  delegate shape established across the wave-23 cycle.
 *  (b) "Persistence parity with the §152 sort-persistence slice — same
 *  shape just a third axis on the same repository" — LIVE-FRAMING +
 *  FULFILLED-PREDICTION. The §154 filter-persistence slice (Task #320,
 *  completed) is the canonical introducer; same-repo-third-axis framing
 *  stands.
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-
 *  NOT-STALE. LibraryReworkModule.kt L14 import, L132 `factory {
 *  ObserveLibraryFilterUseCase(get()) }` realization. Three
 *  classifications STAND on their own merits. Original Phase 7.x.
 *  library.filter.persist-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class ObserveLibraryFilterUseCase(
    private val repository: LibraryPrefsRepository,
) {
    operator fun invoke(): Flow<LibraryFilter> = repository.observeFilter()
}
