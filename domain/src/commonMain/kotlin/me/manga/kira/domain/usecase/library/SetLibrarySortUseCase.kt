package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Persist the user's chosen [LibrarySort].
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.setSort]". Counterpart to
 * [ObserveLibrarySortUseCase]; both are constructor-injected into the Library VM so the VM holds
 * narrow, intent-specific surfaces rather than the broader repository handle.
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster128.staleKdocSweep.cascade,
 * Task #584, 2026-05-28): classified as follows after recursive symbol
 * verification (ninety-third sibling of the cluster57-127 sweep — third
 * file of the wave-23 `:domain/usecase/library/` 5-file refresh-state
 * plus sort-axis-pair batch alongside ObserveLibraryRefresh plus
 * ObserveLibrarySort plus ObserveLibrarySortDirection plus
 * SetLibrarySortDirection):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.setSort +
 *  counterpart-to-ObserveLibrarySort + both-constructor-injected-into-
 *  Library-VM-narrow-intent-specific-surfaces-rather-than-broader-
 *  repository-handle" — LIVE-NOT-STALE + FULFILLED-PREDICTION.
 *  LibraryViewModel.kt L45 import, L95 ctor `private val setLibrarySort:
 *  SetLibrarySortUseCase`, L383 realization `viewModelScope.launch {
 *  setLibrarySort(sort) }` inside `onSortChange(sort)` handler. The
 *  write-side write-then-flow-re-emits-back round-trip stands: L378
 *  `updateState { it.copy(sort = sort, ...) }` writes state optimistically,
 *  then L383 persists, then the L146 observeLibrarySort flow eventually
 *  emits the same value back idempotently (ARCHITECTURE.md L24161-24162
 *  cross-ref preserved verbatim — "When the VM persists a new sort via
 *  setLibrarySort(sort), the same observeLibrarySort() flow eventually
 *  emits the new value back").
 *  (b) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-
 *  NOT-STALE. LibraryReworkModule.kt L31 import, L129 `factory {
 *  SetLibrarySortUseCase(get()) }` realization; L160 `setLibrarySort =
 *  get()` VM ctor wiring confirmed. Closes the sort-mode Observe/Set
 *  pair (92nd+93rd siblings). Two classifications STAND on their own
 *  merits. Original Phase 7.x.library.sort.persist-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
class SetLibrarySortUseCase(
    private val repository: LibraryPrefsRepository,
) {
    suspend operator fun invoke(sort: LibrarySort) {
        repository.setSort(sort)
    }
}
