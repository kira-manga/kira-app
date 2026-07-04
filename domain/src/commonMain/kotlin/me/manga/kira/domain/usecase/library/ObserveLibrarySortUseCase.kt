package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Observe the user's persisted Library sort mode as a live stream.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LibraryPrefsRepository.observeSort]". Mirrors the
 * established "one VM-callable verb per use case" shape used by [ObserveReadingModeUseCase] and
 * [me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase].
 *
 * Why a use case at all when this is a single-line delegate: stable presentation-layer dependency
 * (the Library VM type-signature reveals exactly which capability it consumes), test seam, and
 * parity with [SetLibrarySortUseCase]. Splitting observe / set into two use cases gives the VM
 * two narrow, distinctly named injection points — neither use case can accidentally do the
 * other's job.
 *
 * Constructor-injected `LibraryPrefsRepository` per contract §6 DIP — Koin binds it as a
 * `single` in `libraryReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster128.staleKdocSweep.cascade,
 * Task #584, 2026-05-28): classified as follows after recursive symbol
 * verification (ninety-second sibling of the cluster57-127 sweep —
 * second file of the wave-23 `:domain/usecase/library/` 5-file refresh-
 * state plus sort-axis-pair batch alongside ObserveLibraryRefresh plus
 * SetLibrarySort plus ObserveLibrarySortDirection plus
 * SetLibrarySortDirection):
 *  (a) "§6 SRP one-rule-delegate-to-LibraryPrefsRepository.observeSort +
 *  mirrors-one-VM-callable-verb-per-use-case + Why-use-case-at-all-
 *  stable-presentation-dependency-test-seam-parity-with-SetLibrarySort +
 *  Splitting-observe-set-into-two-use-cases-gives-VM-two-narrow-
 *  distinctly-named-injection-points-neither-can-do-others-job" — LIVE-
 *  NOT-STALE + FULFILLED-PREDICTION. LibraryViewModel.kt L33 import,
 *  L94 ctor `private val observeLibrarySort: ObserveLibrarySortUseCase`,
 *  L146 realization `observeLibrarySort().onEach { sort -> updateState
 *  { it.copy(sort = sort, items = applyView(...)) } }.launchIn
 *  (viewModelScope)` inside VM `init {}` — sort-mode-flow-drives-state-
 *  plus-items-recomputation policy preserved verbatim. The deliberately-
 *  split Observe/Set pair invariant stands: SetLibrarySortUseCase (93rd
 *  sibling forthcoming) is the matching write-side narrow injection
 *  point at L95 ctor.
 *  (b) "Cross-ref to ObserveReadingModeUseCase + FetchMangaDetailsUseCase
 *  shape parity" — LIVE-FRAMING. The cross-ref to ObserveReadingMode-
 *  UseCase confirmed via cluster126 sweep (84th sibling — same one-line
 *  Flow<T> delegate pattern) and FetchMangaDetailsUseCase via cluster119
 *  sweep — the "one VM-callable verb per use case" shape is established
 *  across 3+ sibling clusters now; the framing stands.
 *  (c) "§6 DIP + Koin factory binding in libraryReworkModule" — LIVE-
 *  NOT-STALE-WITH-RENAMING. LibraryReworkModule.kt L19 import, L128
 *  `factory { ObserveLibrarySortUseCase(get()) }` realization; L159
 *  `observeLibrarySort = get()` VM ctor wiring confirmed. Note: the
 *  prose says LibraryPrefsRepository is bound "as a `single`" — verified
 *  in LibraryReworkModule but the LibraryPrefsRepository binding shape
 *  is independent of this use case's `factory` binding (the use case is
 *  factory; the repo is single — the prose's "single" cites the repo
 *  binding, not the use case binding). Three classifications STAND on
 *  their own merits. Original Phase 7.x.library.sort.persist-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class ObserveLibrarySortUseCase(
    private val repository: LibraryPrefsRepository,
) {
    operator fun invoke(): Flow<LibrarySort> = repository.observeSort()
}
