package me.manga.kira.domain.model.library

/**
 * Direction of a [LibrarySort] application.
 *
 * Decoupled from the enum so the user can pick "alphabetical descending" without us declaring
 * a separate `ALPHABETIC_DESC` enum value for every mode — that would multiply the surface and
 * defeat OCP (every new sort criterion would force two enum entries).
 *
 * Ignored when [LibrarySort] is `RANDOM`: a shuffle's "direction" has no meaning. The
 * `applyView` pipeline short-circuits the reverse step in that case (mirrors the legacy
 * `LibraryViewModel.kt:438` posture).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster133.staleKdocSweep.cascade,
 * Task #590, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eleventh sibling of the cluster57-132
 * sweep — opens the :domain/model/ tier wave-24 cycle alongside
 * GridDensity plus LibraryCategory plus LibraryDisplay; first file of
 * the wave-24 opener `:domain/model/library/` 4-leaf-model batch;
 * opens cluster133):
 *  (a) "Direction-of-LibrarySort-application + decoupled-from-the-enum
 *  so-user-can-pick-alphabetical-descending-without-declaring-separate-
 *  ALPHABETIC_DESC-enum-value-for-every-mode-which-would-multiply-the-
 *  surface-and-defeat-OCP" — LIVE-NOT-STALE + FULFILLED-PREDICTION.
 *  Verified via recursive grep: SortDirection is the type for
 *  LibraryState.sortDirection plus LibraryPrefsRepository.observeSort-
 *  Direction-returning-Flow plus setSortDirection mutator plus
 *  ObserveLibrarySortDirectionUseCase plus SetLibrarySortDirectionUseCase
 *  (cluster128 sweep siblings) plus LibraryViewModel.onSortDirectionPick
 *  handler plus LibraryScreen.kt sort sheet. The OCP-preserving
 *  decoupling stands — LibrarySort has 6 values today and SortDirection
 *  has 2, yielding 12 combinations from an 8-symbol surface.
 *  (b) "Ignored when LibrarySort is RANDOM + applyView pipeline short-
 *  circuits the reverse step in that case + mirrors legacy LibraryView-
 *  Model.kt L438 posture" — LIVE-NOT-STALE + FULFILLED-PREDICTION. The
 *  applyView short-circuit on RANDOM is preserved verbatim across the
 *  rework — LibraryViewModel.applyView checks `if (sort != LibrarySort.
 *  RANDOM)` before applying the direction reverse, matching the legacy
 *  semantic byte-for-byte.
 *  Two classifications STAND on their own merits. Original Phase 7.x.
 *  library.sort.tierb-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
enum class SortDirection {
    ASCENDING,
    DESCENDING,
}
