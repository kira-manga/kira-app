package me.manga.kira.domain.usecase.downloads

import me.manga.kira.domain.repository.DownloadsActionRepository

/**
 * Use case: cancel the QUEUED / COMPRESSING download identified by [chapterId].
 *
 * Phase 7.x.downloads.actions rework. Thin pass-through over
 * [DownloadsActionRepository.cancelDownload] — queue-prune semantics (distinct from
 * [CancelRunningDownloadUseCase] which interrupts an in-flight RUNNING row).
 *
 * **Caller obligation**: the rework `:ui` shows the cancel button only on rows whose
 * `state` is `QUEUED` or `COMPRESSING`. RUNNING rows route through the sibling
 * [CancelRunningDownloadUseCase] instead because the legacy worker uses different signal
 * semantics for in-flight cancel.
 *
 * Contract §6 SRP: one rule — "issue a queue-prune cancel intent to the repository".
 *
 * Contract §6 DIP: depends on [DownloadsActionRepository], not on the `:data` impl or the
 * legacy `:shared` facade.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `downloadsReworkModule` (stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster121.staleKdocSweep.cascade,
 * Task #577, 2026-05-28): classified as follows after recursive symbol
 * verification (sixty-third sibling of the cluster57-120 sweep — opens
 * the wave-20 `:domain/usecase/downloads/` 5-file batch alongside Cancel-
 * Running plus Delete plus Enqueue plus Observe; RetryDownloadUseCase
 * deferred to cluster122 follow-up to respect ≤5-file commit cap):
 *  (a) "Phase 7.x.downloads.actions rework — thin pass-through over
 *  DownloadsActionRepository.cancelDownload; queue-prune semantics
 *  (distinct from CancelRunningDownloadUseCase which interrupts an in-
 *  flight RUNNING row)" — LIVE-NOT-STALE. DownloadsViewModel.kt L8
 *  import, L156 ctor `private val cancelDownload: CancelDownloadUseCase`,
 *  L202 realization `val result = cancelDownload(chapterId)` inside the
 *  DownloadsIntent.OnCancel branch. L28-29 single-line pass-through
 *  `repository.cancelDownload(chapterId)`. DownloadsActionRepositoryImpl
 *  queue-prune semantics verified at cluster #442 sibling sweep
 *  (downloadsactionrepo.staleKdocSweep) plus cluster #449 follow-up.
 *  (b) "Caller obligation — the rework `:ui` shows the cancel button
 *  only on rows whose state is QUEUED or COMPRESSING; RUNNING rows route
 *  through the sibling CancelRunningDownloadUseCase instead" — LIVE-NOT-
 *  STALE. `:ui` state-branching verified at cluster #444 sibling sweep
 *  (downloads.staleKdocSweep.cascade); peer sibling CancelRunning cross-
 *  ref intra-cluster121.
 *  (c) §6 SRP + §6 DIP + Koin factory lifecycle — LIVE-NOT-STALE.
 *  DownloadsReworkModule.kt L135 `factory { CancelDownloadUseCase(get())
 *  }` realization; L3 import binds `:domain`-layer interface, not
 *  `:data`-layer impl.
 *  Three classifications STAND on their own merits. Original Phase
 *  7.x.downloads.actions-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class CancelDownloadUseCase(
    private val repository: DownloadsActionRepository,
) {
    suspend operator fun invoke(chapterId: Long): Result<Unit> =
        repository.cancelDownload(chapterId)
}
