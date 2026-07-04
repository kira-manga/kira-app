package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.model.library.SortDirection
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Persist the user's chosen [SortDirection].
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.setSortDirection]".
 * Counterpart to [ObserveLibrarySortDirectionUseCase].
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster128.staleKdocSweep.cascade,
 * Task #584, 2026-05-28): classified as follows after recursive symbol
 * verification (ninety-fifth sibling of the cluster57-127 sweep — fifth
 * and closing file of the wave-23 `:domain/usecase/library/` 5-file
 * refresh-state plus sort-axis-pair batch alongside ObserveLibrary-
 * Refresh plus ObserveLibrarySort plus SetLibrarySort plus Observe-
 * LibrarySortDirection; closes cluster128):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.setSort-
 *  Direction + counterpart-to-ObserveLibrarySortDirection" — LIVE-NOT-
 *  STALE + FULFILLED-PREDICTION. LibraryViewModel.kt L44 import, L97
 *  ctor `private val setLibrarySortDirection: SetLibrarySortDirection-
 *  UseCase`, L397 realization `viewModelScope.launch {
 *  setLibrarySortDirection(nextDir) }` inside the
 *  `onSortDirectionToggle()` handler at L386 — `nextDir` resolves via
 *  exhaustive `when (state.value.sortDirection)` over the 2-value
 *  SortDirection enum (ASCENDING ↔ DESCENDING) per SOLID_AUDIT.md
 *  L22087 banned-features check "exhaustive over 2 values". The
 *  optimistic-write-then-flow-re-emits-back round-trip matches the 93rd
 *  sibling SetLibrarySort pattern: L391 `updateState { it.copy
 *  (sortDirection = nextDir, ...) }` writes optimistically, then L397
 *  persists, then the L157 observeLibrarySortDirection flow eventually
 *  emits the same value back idempotently.
 *  (b) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-
 *  NOT-STALE. LibraryReworkModule.kt L30 import, L131 `factory {
 *  SetLibrarySortDirectionUseCase(get()) }` realization; L162
 *  `setLibrarySortDirection = get()` VM ctor wiring confirmed. Closes
 *  cluster128 (refresh-state observer + sort-mode Observe/Set pair +
 *  sort-direction Observe/Set pair = 5 files); remaining 15 library/
 *  files split across cluster129-131 (filter + grid density pairs,
 *  category + lastUpdated + display observer, remaining display
 *  setters + toggle pair). Two classifications STAND on their own
 *  merits. Original Phase 7.x.library.sort.persist-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
class SetLibrarySortDirectionUseCase(
    private val repository: LibraryPrefsRepository,
) {
    suspend operator fun invoke(direction: SortDirection) {
        repository.setSortDirection(direction)
    }
}
