package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.repository.LibraryRefreshRepository

/**
 * Trigger a user-initiated library refresh.
 *
 * Thin pass-through over [LibraryRefreshRepository.refresh]. Contract §6 SRP / DIP — the
 * `:presentation` `LibraryViewModel` depends on this use case (an interface-coupling shape
 * matching every other Library use case), not on the repository directly. Future composition
 * (e.g., gating refresh on connectivity, debouncing rapid taps) can live here without touching
 * either the VM or the repository impl.
 *
 * Constructor injection per contract §6 DIP. Koin binds as a `factory` in
 * [me.manga.kira.di.libraryReworkModule].
 *
 * **Audit-trail postscript** (Phase 9.x.cluster127.staleKdocSweep.cascade,
 * Task #583, 2026-05-28): classified as follows after recursive symbol
 * verification (ninetieth sibling of the cluster57-126 sweep — fifth
 * and closing file of the wave-23 `:domain/usecase/library/` 5-file
 * foundation batch alongside ObserveLibrary plus ToggleInLibrary plus
 * ObserveInLibrary plus BulkRemoveFromLibrary; closes cluster127):
 *  (a) "Thin-pass-through-over-LibraryRefreshRepository.refresh + §6
 *  SRP + §6 DIP + LibraryViewModel-depends-on-use-case-not-repository
 *  + future-composition-gating-on-connectivity-debouncing-rapid-taps-
 *  lives-here" — LIVE-NOT-STALE. LibraryViewModel.kt L35 import, L92
 *  ctor `private val refreshLibrary: RefreshLibraryUseCase`, L253
 *  realization `LibraryIntent.OnRefresh -> refreshLibrary()` inside
 *  the OnRefresh intent handler. Connectivity-gating + debouncing-
 *  rapid-taps forecast — FORECAST-NOT-YET-FULFILLED. Recursive search
 *  for connectivity-aware or debounce-shaped refresh-policy returns
 *  zero matches; single-line `repository.refresh()` pass-through (L20)
 *  preserved.
 *  (b) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-
 *  NOT-STALE. LibraryReworkModule.kt L21 import, L126 `factory {
 *  RefreshLibraryUseCase(get()) }` realization. Closes wave-23
 *  `:domain/usecase/library/` foundation 5-file opener batch
 *  (cluster127); the remaining 20 library/ files split across
 *  cluster128-131 will cover the sort axis (cluster128: Observe/Set-
 *  Sort + Observe/Set-SortDirection + ObserveLibraryRefresh) +
 *  filter+gridDensity axes (cluster129) + category+lastUpdated+display
 *  observer (cluster130) + remaining display setters + toggle pair
 *  (cluster131). Two classifications STAND on their own merits.
 *  Original Phase 7.x.library.refresh-era prose preserved verbatim per
 *  the audit-trail-preservation convention.
 */
class RefreshLibraryUseCase(
    private val repository: LibraryRefreshRepository,
) {
    operator fun invoke() = repository.refresh()
}
