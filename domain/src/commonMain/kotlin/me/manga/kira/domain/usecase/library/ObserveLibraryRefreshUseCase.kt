package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.repository.LibraryRefreshRepository

/**
 * Observe whether a library-refresh background job is currently running.
 *
 * Thin pass-through over [LibraryRefreshRepository.observeIsRefreshing]. Returned flow
 * emits `true` while the worker is in the Running state and `false` otherwise. Drives the
 * pull-to-refresh spinner in the rework Library UI.
 *
 * Constructor injection per contract §6 DIP. Koin binds as a `factory` in
 * [me.manga.kira.di.libraryReworkModule].
 *
 * **Audit-trail postscript** (Phase 9.x.cluster128.staleKdocSweep.cascade,
 * Task #584, 2026-05-28): classified as follows after recursive symbol
 * verification (ninety-first sibling of the cluster57-127 sweep — first
 * file of the wave-23 `:domain/usecase/library/` 5-file refresh-state
 * plus sort-axis-pair batch alongside ObserveLibrarySort plus
 * SetLibrarySort plus ObserveLibrarySortDirection plus
 * SetLibrarySortDirection; opens cluster128):
 *  (a) "Thin-pass-through-over-LibraryRefreshRepository.observeIsRefreshing
 *  + flow-emits-true-while-worker-Running-false-otherwise + drives-pull-to-
 *  refresh-spinner-in-rework-Library-UI" — LIVE-NOT-STALE + FULFILLED-
 *  PREDICTION. LibraryViewModel.kt L31 import, L93 ctor `private val
 *  observeLibraryRefresh: ObserveLibraryRefreshUseCase`, L142
 *  realization `observeLibraryRefresh().onEach { running -> updateState
 *  { it.copy(isRefreshing = running) } }.launchIn(viewModelScope)` inside
 *  the VM `init {}` block — the pull-to-refresh-spinner-driving prediction
 *  stands verbatim. LibraryIntent.kt L56 KDoc cross-references this use
 *  case explicitly as the projection source for the refresh-state surface.
 *  Cross-ref: companion writer `RefreshLibraryUseCase` (cluster127 sibling)
 *  authored the refresh-state via the [LibraryRefreshRepository.refresh]
 *  pair — write + observe split into two narrow use cases per the
 *  established Observe/Set split-pair convention.
 *  (b) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-NOT-
 *  STALE. LibraryReworkModule.kt L17 import, L127 `factory {
 *  ObserveLibraryRefreshUseCase(get()) }` realization; L158
 *  `observeLibraryRefresh = get()` VM ctor wiring confirmed. Two
 *  classifications STAND on their own merits. Original Phase 6.2.x.library.
 *  refresh-state-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
class ObserveLibraryRefreshUseCase(
    private val repository: LibraryRefreshRepository,
) {
    operator fun invoke(): Flow<Boolean> = repository.observeIsRefreshing()
}
