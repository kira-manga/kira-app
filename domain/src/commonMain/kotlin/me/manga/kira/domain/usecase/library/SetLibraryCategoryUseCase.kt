package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Persist the user's chosen [LibraryCategory].
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.setCategory]".
 * Counterpart to [ObserveLibraryCategoryUseCase]; both are constructor-injected into the
 * Library VM so the VM holds narrow, intent-specific surfaces rather than the broader repository
 * handle.
 *
 * Invoked from the VM's `OnCategoryChange` handler — the VM updates state synchronously (so the
 * UI recomposes immediately) AND launches this setter on `viewModelScope` to persist. The Flow
 * observer in `init {}` will re-emit the new value but the resulting state update is idempotent
 * (same category → same `state.category`); `StateFlow`'s distinct-emission guard collapses the
 * echo to a no-op recomposition. Same observer-echo posture as the §152 sort, §154 filter, and
 * §157 density persistence wirings.
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `factory` in `libraryReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster130.staleKdocSweep.cascade,
 * Task #586, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-first sibling of the cluster57-129 sweep
 * — second file of the wave-23 `:domain/usecase/library/` 4-file category
 * plus lastUpdated plus display-observer batch alongside ObserveLibrary-
 * Category plus ObserveLibraryLastUpdated plus ObserveLibraryDisplay):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.setCategory +
 *  counterpart-to-ObserveLibraryCategory + narrow-intent-specific-
 *  surfaces" — LIVE-NOT-STALE + FULFILLED-PREDICTION. LibraryViewModel.kt
 *  L36 import, L103 ctor `private val setLibraryCategory:
 *  SetLibraryCategoryUseCase`, L428 realization `viewModelScope.launch
 *  { setLibraryCategory(category) }` inside the `onCategoryChange(
 *  category)` handler — VM L421-429 wraps the `updateState { it.copy(
 *  category = category, items = applyView(...)) }` optimistic write plus
 *  persist launch. VM L413 KDoc reference preserved: "persist call is
 *  launched on viewModelScope and the observeLibraryCategory flow in
 *  init {} re-emits".
 *  (b) "VM-updates-state-synchronously + setter-launched-on-viewModel-
 *  Scope + Flow-observer-re-emits + state-update-idempotent + StateFlow-
 *  distinct-emission-guard-collapses-echo + same-observer-echo-posture-
 *  as-§152-sort-§154-filter-§157-density-persistence" — LIVE-NOT-STALE +
 *  FULFILLED-PREDICTION. The same optimistic-write-then-flow-re-emits-
 *  back idempotent round-trip pattern as the 93rd + 95th + 97th + 99th
 *  siblings (SetLibrarySort + SetLibrarySortDirection + SetLibraryFilter
 *  + SetLibraryGridDensity) is preserved verbatim — echo-coalescing
 *  invariant stands across all 5 Set* siblings in the wave-23 cycle.
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-
 *  NOT-STALE. LibraryReworkModule.kt L22 import, L137 `factory {
 *  SetLibraryCategoryUseCase(get()) }` realization. Closes 100th + 101st
 *  category Observe/Set pair. Three classifications STAND on their own
 *  merits. Original Phase 7.x.library.category.persist-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class SetLibraryCategoryUseCase(
    private val repository: LibraryPrefsRepository,
) {
    suspend operator fun invoke(category: LibraryCategory) {
        repository.setCategory(category)
    }
}
