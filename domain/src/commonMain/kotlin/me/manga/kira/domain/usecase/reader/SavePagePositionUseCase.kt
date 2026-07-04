package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.repository.ReadProgressRepository

/**
 * Phase 7.x.reader.resumeposition. Persists the user's current page index for the given chapter
 * so the Reader can resume there on re-entry.
 *
 * SRP: single-line delegate to [ReadProgressRepository.save]. No transformation, no validation
 * (the impl is allowed to drop out-of-range or absurd values silently; today it stores whatever
 * the caller passes, since the Reader VM already clamps the page index before invoking this use
 * case via the `OnPageChanged` reducer).
 *
 * `suspend` because the underlying repository declares it suspend (matches
 * [me.manga.kira.domain.repository.ReadProgressRepository.save] for the same reasons —
 * future `withContext(io)` headroom). The Reader VM calls this from a fire-and-forget
 * `viewModelScope.launch` on every page change, so the suspending shape is consumed naturally.
 *
 * "Two use cases vs one repository.position(chapterUrl) accessor" decision: mirrors the
 * Save/Load split established by [StartReadingSessionUseCase] / [EndReadingSessionUseCase] in
 * Phase 6.4.x.statistics — each verb is its own use case so the VM's reducer reads as a flat
 * sequence of intent → use-case mappings rather than threading a single state-holding type
 * through every branch. Also keeps the seams independently testable: a load-only test double
 * doesn't need a save method, and vice versa.
 *
 * DIP: depends only on the `:domain` repository interface. Koin's factory binding constructs the
 * use case with `get<ReadProgressRepository>()` — the impl in `:data` is invisible to the VM
 * and to this class. Construction is `factory` (not `single`) because the use case is stateless;
 * the repository it delegates to is the `single` that holds the wire to disk.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster125.staleKdocSweep.cascade,
 * Task #581, 2026-05-28): classified as follows after recursive symbol
 * verification (eighty-first sibling of the cluster57-124 sweep —
 * fourth file of the wave-22 `:domain/usecase/reader/` 5-file batch
 * alongside StartReadingSession plus EndReadingSession plus LoadPage-
 * Position plus ListChapters):
 *  (a) "Phase 7.x.reader.resumeposition Reader-VM-onPageChanged-
 *  persist + fire-and-forget viewModelScope.launch wrapper" — LIVE-
 *  NOT-STALE. ReaderViewModel.kt L18 import, L233 ctor `private val
 *  savePagePosition: SavePagePositionUseCase`, L391 realization
 *  `viewModelScope.launch { savePagePosition(chapterUrl, clamped) }`
 *  inside the OnPageChanged reducer; ReaderViewModel L106 KDoc
 *  references the resume-position pair framing. Pre-clamped-pageIndex
 *  invariant upheld — the VM clamps via runFetch.coerceIn before
 *  invocation, leaving this use case as a pure write-through.
 *  (b) "SRP single-line repository.save delegate + no-validation
 *  no-transformation (drop-silently-or-store-as-passed policy lives
 *  in :data)" — LIVE-NOT-STALE. L34-36 single-line `repository.save(
 *  chapterUrl, pageIndex)` pass-through preserved; the VM's pre-
 *  clamping makes the :data side's drop-silently option moot in
 *  practice but the contract permission stands.
 *  (c) "Two-use-cases-versus-one-position-accessor decision — Save/Load
 *  split mirrors Start/End session pair; each verb is its own use case
 *  for flat VM reducer + independently testable seams" — LIVE-NOT-
 *  STALE. Intra-cluster125 sibling cross-ref to LoadPagePositionUseCase
 *  (80th, just-swept) and StartReadingSessionUseCase (78th) +
 *  EndReadingSessionUseCase (79th) — three sibling verb-split pairs
 *  in `:domain/usecase/reader/` (Start/End + Load/Save + Observe/Set
 *  forthcoming in cluster126) corroborate the architectural-symmetry
 *  framing of the original Phase 7.x.reader.resumeposition-era prose.
 *  (d) "§6 DIP + factory-stateless + suspend-shape (matches repo
 *  declaration; called from fire-and-forget viewModelScope.launch)" —
 *  LIVE-NOT-STALE. ReaderReworkModule.kt L130 `factory { SavePage-
 *  PositionUseCase(get()) }` realization. Four classifications STAND
 *  on their own merits. Original Phase 7.x.reader.resumeposition-era
 *  prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
class SavePagePositionUseCase(
    private val repository: ReadProgressRepository,
) {
    suspend operator fun invoke(chapterUrl: String, pageIndex: Int) {
        repository.save(chapterUrl, pageIndex)
    }
}
