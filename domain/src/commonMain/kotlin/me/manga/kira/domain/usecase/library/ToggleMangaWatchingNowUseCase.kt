package me.manga.kira.domain.usecase.library

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.repository.MangaKey

/**
 * Flip the `isWatchingNow` affinity flag on a library entry.
 *
 * Contract §6 SRP: owns one rule — "toggle the watching-now flag for this manga, surface the
 * repository's success/failure to the caller". Same shape and rationale as
 * [ToggleMangaLikedUseCase]; the two are parallel because the legacy MangaCard action row
 * exposes them as two independent icon toggles, so the rework MVI surface preserves the
 * one-intent-per-axis posture.
 *
 * §179 ladder rung 19 (Task #345). Closes the `LibraryManga.isWatchingNow` KDoc deferral —
 * see the repository method's KDoc for the strangler-fig boundary narrative.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster132.staleKdocSweep.cascade,
 * Task #588, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-tenth sibling of the cluster57-131 sweep
 * — second and closing file of the wave-23 closer `:domain/usecase/
 * library/` 2-toggle-pair batch alongside ToggleMangaLiked; closes
 * cluster132 plus wave-23 cycle plus library/ subpackage entirely; closes
 * the :domain/usecase/ §253 audit-trail-preservation sweep at 78/78 =
 * 100%):
 *  (a) "§6 SRP one-rule-toggle-the-watching-now-flag-for-this-manga-
 *  surface-the-repository-success-failure-to-the-caller + same-shape-and-
 *  rationale-as-ToggleMangaLiked + parallel-because-legacy-MangaCard-
 *  action-row-exposes-them-as-two-independent-icon-toggles + rework-MVI-
 *  surface-preserves-one-intent-per-axis-posture" — LIVE-NOT-STALE +
 *  FULFILLED-PREDICTION. LibraryViewModel.kt L48 import, L133 ctor
 *  `private val toggleMangaWatchingNow: ToggleMangaWatchingNowUseCase`,
 *  L576 realization `toggleMangaWatchingNow(key).onFailure { emit(
 *  LibraryEffect.ShowError(it)) }` inside the `onToggleWatchingNow(key)`
 *  handler. Identical structural shape as 109th sibling ToggleMangaLiked
 *  — both AppResult<Unit>-returning toggles against LibraryRepository,
 *  both thin DIP pass-throughs, both share the contract §6 SRP shape.
 *  (b) "Same-shape-as-ToggleMangaLiked + see-that-handler-KDoc-for-flow-
 *  re-emit-failure-surfacing-narrative + no-synchronous-local-state-copy-
 *  observeLibrary-flow-re-emits-updated-LibraryManga-list + :ui-watching-
 *  now-icon-fills-in-naturally-on-next-frame + failure-surfaces-through-
 *  LibraryEffect.ShowError-success-is-silent" — LIVE-NOT-STALE +
 *  FULFILLED-PREDICTION. VM L569-577 KDoc reference preserved verbatim:
 *  "Same shape as onToggleLike — see that handler's KDoc for the flow-
 *  re-emit / failure-surfacing narrative". The two-icon-toggle parity is
 *  preserved verbatim across both 109th and 110th siblings — same
 *  posture, same fail-surfacing-via-effect, same observe-flow-re-emit-
 *  drives-:ui-recomposition.
 *  (c) "§179 ladder rung 19 (Task #345) + closes-LibraryManga.is-
 *  WatchingNow-KDoc-deferral + repository-method-KDoc-strangler-fig-
 *  boundary-narrative + §6 DIP + Koin factory binding in libraryRework-
 *  Module" — LIVE-NOT-STALE + FULFILLED-PREDICTION. LibraryReworkModule.
 *  kt L34 import, L101 KDoc cite "§345 action-row toggles", L150
 *  `factory { ToggleMangaWatchingNowUseCase(get()) }` realization. The
 *  §179 rung 19 slice (Task #345, completed) is the canonical introducer
 *  for both toggle-pair members; affinity-flag-flip-not-set framing
 *  stands across both. Closes cluster132. Closes wave-23 cycle (25 files
 *  across clusters 127-132). Closes :domain/usecase/library/ subpackage
 *  entirely (25/25 SWEPT). Closes the :domain/usecase/ §253 audit-trail-
 *  preservation sweep at 78/78 = 100%. Three classifications STAND on
 *  their own merits. Original Phase 7.x.library.actionrow-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class ToggleMangaWatchingNowUseCase(
    private val repository: LibraryRepository,
) {
    suspend operator fun invoke(key: MangaKey): AppResult<Unit> = repository.toggleWatchingNow(key)
}
