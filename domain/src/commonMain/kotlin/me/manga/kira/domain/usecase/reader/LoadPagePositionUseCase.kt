package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.repository.ReadProgressRepository

/**
 * Phase 7.x.reader.resumeposition. Reads the last-saved page index for the given chapter so the
 * Reader can seed `state.currentPageIndex` on entry (instead of always starting at page 0).
 *
 * SRP: single-line delegate to [ReadProgressRepository.load]. The "use the saved page if any,
 * otherwise start at 0" fallback lives in the VM's `onEnter` reducer where the policy belongs;
 * this use case is a pure read-through.
 *
 * Returns `Int?` — null means "no position has been saved for this chapter" (or the saved entry
 * was a hash collision; see the repository's class-level KDoc). The Reader VM treats both cases
 * identically (seed to page 0); the distinction matters at the repository contract level but
 * not at the policy level.
 *
 * `suspend` because the underlying repository declares it suspend; called from the
 * `OnEnter` reducer which is already suspend (the reducer in `MviViewModel.handle` is the only
 * call site).
 *
 * Phase invariant: the saved page may be PAST the new chapter's last-index (chapter shrank on
 * a re-publish since the user last opened it). The VM's existing `runFetch` clamp via
 * `currentPageIndex.coerceIn(0, pages.lastIndex)` handles this — see Reader VM's class-level
 * "Page-index clamping on Success" KDoc. This use case does not bounds-check itself.
 *
 * DIP: depends only on the `:domain` repository interface. Construction is `factory` (stateless,
 * matches the established slice pattern).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster125.staleKdocSweep.cascade,
 * Task #581, 2026-05-28): classified as follows after recursive symbol
 * verification (eightieth sibling of the cluster57-124 sweep — third
 * file of the wave-22 `:domain/usecase/reader/` 5-file batch alongside
 * StartReadingSession plus EndReadingSession plus SavePagePosition
 * plus ListChapters):
 *  (a) "Phase 7.x.reader.resumeposition Reader-VM-onEnter-seed-saved-
 *  page-index" — LIVE-NOT-STALE. ReaderViewModel.kt L16 import, L232
 *  ctor `private val loadPagePosition: LoadPagePositionUseCase`, L327
 *  realization `val savedPage = loadPagePosition(chapter.url) ?: 0`
 *  inside the OnEnter reducer; ReaderViewModel L106 KDoc references
 *  the resume-position pair framing. Intra-cluster125 sibling cross-
 *  ref to SavePagePositionUseCase (81st sibling forthcoming) — the
 *  Load/Save pair mirrors the Start/End session pair, both established
 *  in Phase 6.4.x.statistics and extended in Phase 7.x.reader.resume-
 *  position with the same "two verbs = two use cases" decomposition.
 *  (b) "SRP single-line repository.load delegate + Int? null-means-no-
 *  saved-position + clamping-policy-lives-in-VM-not-here" — LIVE-NOT-
 *  STALE. L33-34 single-line `repository.load(chapterUrl)` pass-
 *  through preserved; null fallback policy lives in ReaderViewModel
 *  `?: 0` at L327; out-of-range clamping handled by runFetch.coerceIn
 *  per the original prose's invariant referencing the VM's "Page-
 *  index clamping on Success" KDoc.
 *  (c) "§6 DIP + factory-stateless + suspend-shape (matches repo
 *  declaration; called from suspend MviViewModel.handle reducer)" —
 *  LIVE-NOT-STALE. ReaderReworkModule.kt L131 `factory { LoadPage-
 *  PositionUseCase(get()) }` realization; the stateless-factory + the
 *  repository's underlying state-holding-single split mirrors the
 *  Start/End sibling-pair pattern. Three classifications STAND on
 *  their own merits. Original Phase 7.x.reader.resumeposition-era
 *  prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
class LoadPagePositionUseCase(
    private val repository: ReadProgressRepository,
) {
    suspend operator fun invoke(chapterUrl: String): Int? =
        repository.load(chapterUrl)
}
