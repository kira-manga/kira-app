package me.manga.kira.domain.usecase.downloads

import me.manga.kira.domain.repository.DownloadsActionRepository

/**
 * Use case: retry the FAILED download identified by [chapterId].
 *
 * Phase 7.x.downloads.actions rework. Thin pass-through over
 * [DownloadsActionRepository.retryDownload] — single argument (the chapterId, since retry
 * is identity-based and the `:data` impl reconstructs the remaining metadata `url` / `api`
 * / `mangaTitle` from the legacy DAO row).
 *
 * **Caller obligation**: the rework `:ui` shows the retry button only on rows whose
 * `state == DownloadState.FAILED`. The use case itself does NOT validate — if a caller
 * passes a chapterId whose row is not FAILED, the legacy `enqueueChapterDownload` happily
 * re-enqueues anyway (Room replaces the row by chapterId primary key). The state guard is
 * a UI rule (parity with the legacy `DownloadItemCard(showRetry = item.state == FAILED)`
 * gating), not a domain rule.
 *
 * Contract §6 SRP: one rule — "issue a retry intent to the repository".
 *
 * Contract §6 DIP: depends on [DownloadsActionRepository], not on the `:data` impl or the
 * legacy `:shared` facade.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `downloadsReworkModule` (stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster122.staleKdocSweep.cascade,
 * Task #578, 2026-05-28): classified as follows after recursive symbol
 * verification (sixty-eighth sibling of the cluster57-121 sweep — single-
 * file follow-up that closes the wave-20 `:domain/usecase/downloads/`
 * subpackage as FULLY SWEPT alongside Cancel plus CancelRunning plus
 * Delete plus Enqueue plus Observe from cluster121 — the 1-file cluster122
 * scope avoids exceeding the ≤5-file commit cap that the 6-file downloads/
 * batch would have otherwise breached):
 *  (a) "Phase 7.x.downloads.actions rework — thin pass-through over
 *  DownloadsActionRepository.retryDownload; single argument (the chapter-
 *  Id, since retry is identity-based and the `:data` impl reconstructs
 *  the remaining metadata url / api / mangaTitle from the legacy DAO
 *  row)" — LIVE-NOT-STALE. DownloadsViewModel.kt L155 ctor `private val
 *  retryDownload: RetryDownloadUseCase`, L195 realization `val result =
 *  retryDownload(chapterId)` inside the DownloadsIntent.OnRetry branch.
 *  L31-32 single-line pass-through `repository.retryDownload(chapterId)`.
 *  Legacy-DAO-row-metadata-reconstruction posture verified at cluster
 *  #442 sibling sweep (downloadsactionrepo.staleKdocSweep) plus cluster
 *  #449 follow-up — the asymmetric single-id-vs-three-id signature
 *  versus EnqueueDownloadUseCase is structurally required by the post-
 *  enqueue-row-exists assumption documented at the peer's KDoc.
 *  (b) "Caller obligation — the rework `:ui` shows the retry button only
 *  on rows whose state == DownloadState.FAILED; the use case itself does
 *  NOT validate — if a caller passes a chapterId whose row is not
 *  FAILED, the legacy enqueueChapterDownload happily re-enqueues anyway
 *  (Room replaces the row by chapterId primary key); the state guard is
 *  a UI rule (parity with the legacy DownloadItemCard(showRetry = item.
 *  state == FAILED) gating), not a domain rule" — LIVE-NOT-STALE.
 *  `:ui` FAILED-only-state-branching verified at cluster #444 sibling
 *  sweep (downloads.staleKdocSweep.cascade); peer-sibling EnqueueDownload-
 *  UseCase intra-cluster121 cross-ref (just-swept wave-20 peer — the
 *  row-existence-asymmetry distinction the EnqueueDownloadUseCase post-
 *  script (b) draws between retry-row-exists-assumption versus first-
 *  time-enqueue-no-row-yet stands upheld here from the retry side).
 *  Room-replace-by-PK-no-state-validation posture verified at cluster
 *  #442 plus #449.
 *  (c) §6 SRP + §6 DIP + Koin factory lifecycle — LIVE-NOT-STALE.
 *  DownloadsReworkModule.kt L134 `factory { RetryDownloadUseCase(get())
 *  }` realization; L3 import binds `:domain`-layer interface, not
 *  `:data`-layer impl.
 *  Three classifications STAND on their own merits. Original Phase
 *  7.x.downloads.actions-era prose preserved verbatim per the audit-
 *  trail-preservation convention. Closes `:domain/usecase/downloads/`
 *  subpackage as FULLY SWEPT (6 of 6 files) — the wave-20 downloads/
 *  batch is the largest single-subpackage sweep cluster of the cascade
 *  to date and demonstrates the ≤5-file-cap-with-followup pattern as a
 *  durable convention for any future ≥6-file subpackage encountered
 *  downstream.
 */
class RetryDownloadUseCase(
    private val repository: DownloadsActionRepository,
) {
    suspend operator fun invoke(chapterId: Long): Result<Unit> =
        repository.retryDownload(chapterId)
}
