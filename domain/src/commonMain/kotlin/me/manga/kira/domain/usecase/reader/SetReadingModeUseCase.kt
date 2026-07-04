package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.repository.ReadingModeRepository

/**
 * Persist the user's chosen [ReadingMode].
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [ReadingModeRepository.set]". Counterpart to
 * [ObserveReadingModeUseCase]; both are constructor-injected into the Reader VM so the VM holds
 * narrow, intent-specific surfaces rather than the broader repository handle.
 *
 * Why a use case at all when this is a single-line delegate: same rationale as
 * [ObserveReadingModeUseCase] — stable presentation-layer dependency, test seam, parity with the
 * established "one verb = one use case" shape. The two are deliberately split rather than rolled
 * into a single repository injection so that the VM's constructor type-signature reveals exactly
 * which capabilities the VM consumes.
 *
 * Constructor-injected `ReadingModeRepository` per contract §6 DIP — Koin binds it as a `factory`
 * in `readerReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster126.staleKdocSweep.cascade,
 * Task #582, 2026-05-28): classified as follows after recursive symbol
 * verification (eighty-fifth and closing sibling of the cluster57-125
 * sweep — third and closing file of the wave-22 closer `:domain/use-
 * case/reader/` 3-file batch alongside FetchChapterPages plus Observe-
 * ReadingMode; closes reader/ FULLY SWEPT 8/8 files):
 *  (a) "§6 SRP single-rule-delegate-to-ReadingModeRepository.set +
 *  counterpart-to-ObserveReadingModeUseCase + narrow-intent-specific-
 *  surface (VM constructor type-signature reveals exactly which
 *  capabilities the VM consumes)" — LIVE-NOT-STALE. ReaderViewModel.kt
 *  L19 import, L228 ctor `private val setReadingMode: SetReadingMode-
 *  UseCase`, L308 realization `setReadingMode(mode)` inside the
 *  reading-mode-set intent handler. L25-27 single-line `repository.set(
 *  mode)` pass-through preserved; suspend invoke shape matches suspend
 *  ReadingModeRepository.set (the impl writes to persistent storage,
 *  matching the SavePagePositionUseCase + EndReadingSessionUseCase
 *  suspend-write posture).
 *  (b) "Why-use-case-at-all single-line-delegate same-rationale-as-
 *  ObserveReadingModeUseCase — stable presentation-layer dependency +
 *  test seam + parity-with-one-verb-equals-one-use-case-shape;
 *  deliberately-split-rather-than-rolled-into-single-repository-
 *  injection-so-VM-constructor-signature-reveals-capabilities" —
 *  LIVE-NOT-STALE. Intra-cluster126 sibling cross-ref to Observe-
 *  ReadingModeUseCase (84th, just-swept) — the Observe/Set verb-split
 *  pair completes the third sibling-pair pattern in `:domain/usecase/
 *  reader/` (Start/End session pair from cluster125 78th+79th +
 *  Load/Save position pair from cluster125 80th+81st + Observe/Set
 *  reading-mode pair from cluster126 84th+85th); three-pair
 *  architectural-symmetry posture fully realized.
 *  (c) "§6 DIP + Koin factory-stateless + readerReworkModule binding +
 *  shared-ReadingModeRepository-single with ObserveReadingMode (same
 *  repository instance serves both Observe and Set per the Koin single
 *  graph)" — LIVE-NOT-STALE. ReaderReworkModule.kt L102 `factory {
 *  SetReadingModeUseCase(get()) }` realization, directly adjacent to
 *  L101 ObserveReadingModeUseCase factory binding — the adjacent-line
 *  Koin declaration visually corroborates the Observe/Set sibling-pair
 *  framing. Closes wave-22 `:domain/usecase/reader/` 3-file closer
 *  batch; reader/ now FULLY SWEPT (8 of 8 files: 5 from cluster125
 *  opener + 3 from cluster126 closer); ≤5-file-cap-with-followup
 *  convention upheld for the third consecutive wave (after wave-20
 *  downloads/ 5+1 and wave-21 complaint/ 5+4). Three classifications
 *  STAND on their own merits. Original Phase 6.4.2+-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
class SetReadingModeUseCase(
    private val repository: ReadingModeRepository,
) {
    suspend operator fun invoke(mode: ReadingMode) {
        repository.set(mode)
    }
}
