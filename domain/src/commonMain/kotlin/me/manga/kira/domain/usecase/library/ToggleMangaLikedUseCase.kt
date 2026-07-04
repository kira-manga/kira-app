package me.manga.kira.domain.usecase.library

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.repository.MangaKey

/**
 * Flip the `isLiked` affinity flag on a library entry.
 *
 * Contract §6 SRP: owns one rule — "toggle the heart flag for this manga, surface the
 * repository's success/failure to the caller". The flip-not-set semantics (no `value`
 * parameter) live in [me.manga.kira.domain.repository.LibraryRepository.toggleLiked]; this
 * use case is a thin DIP pass-through so the VM doesn't import the repository interface
 * directly. Same shape as [BulkRemoveFromLibraryUseCase] / [ToggleInLibraryUseCase].
 *
 * §179 ladder rung 19 (Task #345). Closes the long-standing
 * `LibraryManga.isLiked` KDoc deferral — see the repository method's KDoc for the
 * strangler-fig boundary narrative.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster132.staleKdocSweep.cascade,
 * Task #588, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-ninth sibling of the cluster57-131 sweep —
 * first file of the wave-23 closer `:domain/usecase/library/` 2-toggle-
 * pair batch alongside ToggleMangaWatchingNow; opens cluster132):
 *  (a) "§6 SRP one-rule-toggle-the-heart-flag-for-this-manga-surface-the-
 *  repository-success-failure-to-the-caller + flip-not-set-semantics-no-
 *  value-parameter-lives-in-LibraryRepository.toggleLiked + thin-DIP-
 *  pass-through-VM-doesnt-import-repository-interface-directly + same-
 *  shape-as-BulkRemoveFromLibrary-ToggleInLibrary" — LIVE-NOT-STALE +
 *  FULFILLED-PREDICTION. LibraryViewModel.kt L47 import, L132 ctor
 *  `private val toggleMangaLiked: ToggleMangaLikedUseCase`, L566
 *  realization `toggleMangaLiked(key).onFailure { emit(LibraryEffect.
 *  ShowError(it)) }` inside the `onToggleLike(key)` handler. Same
 *  AppResult<Unit>-returning toggle posture as ToggleInLibraryUseCase
 *  (sibling cluster127) and BulkRemoveFromLibraryUseCase — three thin
 *  DIP pass-throughs share the contract §6 SRP shape against the
 *  same LibraryRepository.
 *  (b) "No-synchronous-local-state-copy-because-like-flag-lives-on-
 *  persisted-SavedMangaEntity-row-not-on-state.display + observeLibrary-
 *  flow-in-startObserving-re-emits-updated-LibraryManga-list-on-every-
 *  legacy-DAO-write + :ui-heart-icon-fills-in-naturally-on-next-frame +
 *  failure-surfaces-through-LibraryEffect.ShowError-success-is-silent-
 *  flow-re-emit-covers-it" — LIVE-NOT-STALE + FULFILLED-PREDICTION. The
 *  deliberate asymmetry vs the Set* per-flag-display-toggle siblings
 *  (cluster131, rungs 16b-16f) stands — those write to a separate prefs
 *  cell and need an optimistic state.copy to drive immediate UI
 *  recomposition; this toggle writes to the row-level entity itself and
 *  the observe-flow re-emits the entire LibraryManga list naturally.
 *  VM L552 KDoc reference preserved verbatim. LibraryIntent.kt L297 cite
 *  preserved verbatim. The use case itself returns success even when
 *  the manga is not in the library (defensive no-op — see use case
 *  KDoc); VM does NOT gate on a membership check.
 *  (c) "§179 ladder rung 19 (Task #345) + closes-long-standing-Library-
 *  Manga.isLiked-KDoc-deferral + repository-method-KDoc-strangler-fig-
 *  boundary-narrative + §6 DIP + Koin factory binding in libraryRework-
 *  Module" — LIVE-NOT-STALE + FULFILLED-PREDICTION. LibraryReworkModule.
 *  kt L33 import, L101 KDoc cite "§345 action-row toggles", L149
 *  `factory { ToggleMangaLikedUseCase(get()) }` realization. The §179
 *  rung 19 slice (Task #345, completed) is the canonical introducer;
 *  affinity-flag-flip-not-set framing stands. Three classifications
 *  STAND on their own merits. Original Phase 7.x.library.actionrow-era
 *  prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
class ToggleMangaLikedUseCase(
    private val repository: LibraryRepository,
) {
    suspend operator fun invoke(key: MangaKey): AppResult<Unit> = repository.toggleLiked(key)
}
