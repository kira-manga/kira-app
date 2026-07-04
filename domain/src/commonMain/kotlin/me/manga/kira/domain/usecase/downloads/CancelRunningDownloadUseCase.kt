package me.manga.kira.domain.usecase.downloads

import me.manga.kira.domain.repository.DownloadsActionRepository

/**
 * Use case: cancel the in-flight RUNNING download identified by [chapterId] + [mangaId].
 *
 * Phase 7.x.downloads.actions rework. Thin pass-through over
 * [DownloadsActionRepository.cancelRunningDownload] — interruptible-in-flight semantics
 * (distinct from [CancelDownloadUseCase] which prunes QUEUED / COMPRESSING rows). The
 * legacy worker checks the DAO state mid-fetch and stops if marked cancelled; on Android
 * the WorkManager tags are keyed by [mangaId], which is why this method takes both ids.
 *
 * **Caller obligation**: the rework `:ui` shows the running-cancel affordance (the
 * progress-percent `TextButton`) only on rows whose `state == DownloadState.RUNNING`.
 *
 * Contract §6 SRP: one rule — "issue an interruptible-in-flight cancel intent to the
 * repository".
 *
 * Contract §6 DIP: depends on [DownloadsActionRepository], not on the `:data` impl or the
 * legacy `:shared` facade.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `downloadsReworkModule` (stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster121.staleKdocSweep.cascade,
 * Task #577, 2026-05-28): classified as follows after recursive symbol
 * verification (sixty-fourth sibling of the cluster57-120 sweep — second
 * file of the wave-20 `:domain/usecase/downloads/` 5-file batch alongside
 * Cancel plus Delete plus Enqueue plus Observe; RetryDownloadUseCase
 * deferred to cluster122 follow-up to respect ≤5-file commit cap):
 *  (a) "Phase 7.x.downloads.actions rework — thin pass-through over
 *  DownloadsActionRepository.cancelRunningDownload; interruptible-in-
 *  flight semantics (distinct from CancelDownloadUseCase which prunes
 *  QUEUED / COMPRESSING rows); the legacy worker checks the DAO state
 *  mid-fetch and stops if marked cancelled; on Android the WorkManager
 *  tags are keyed by mangaId, which is why this method takes both ids" —
 *  LIVE-NOT-STALE. DownloadsViewModel.kt L9 import, L157 ctor `private
 *  val cancelRunningDownload: CancelRunningDownloadUseCase`, L210
 *  realization `val result = cancelRunningDownload(chapterId, mangaId)`
 *  inside the DownloadsIntent.OnCancelRunning branch. L29-30 single-line
 *  pass-through `repository.cancelRunningDownload(chapterId, mangaId)`.
 *  Android-WorkManager-tag-keyed-by-mangaId rationale verified at cluster
 *  #442 sibling sweep (downloadsactionrepo.staleKdocSweep) plus cluster
 *  #449 follow-up — the two-id signature is structurally required by the
 *  legacy worker-tag topology, not a vestigial pass-through.
 *  (b) "Caller obligation — the rework `:ui` shows the running-cancel
 *  affordance (the progress-percent TextButton) only on rows whose state
 *  == DownloadState.RUNNING" — LIVE-NOT-STALE. `:ui` RUNNING-only-state-
 *  branching verified at cluster #444 sibling sweep (downloads.staleKdoc-
 *  Sweep.cascade); peer sibling CancelDownloadUseCase cross-ref intra-
 *  cluster121 (just-edited sibling — the queue-prune-versus-in-flight-
 *  cancel distinction is upheld by both VM-side intent branching plus
 *  `:ui` row-state predicate filtering).
 *  (c) §6 SRP + §6 DIP + Koin factory lifecycle — LIVE-NOT-STALE.
 *  DownloadsReworkModule.kt L136 `factory { CancelRunningDownloadUseCase(
 *  get()) }` realization; L3 import binds `:domain`-layer interface, not
 *  `:data`-layer impl.
 *  Three classifications STAND on their own merits. Original Phase
 *  7.x.downloads.actions-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class CancelRunningDownloadUseCase(
    private val repository: DownloadsActionRepository,
) {
    suspend operator fun invoke(chapterId: Long, mangaId: Long): Result<Unit> =
        repository.cancelRunningDownload(chapterId, mangaId)
}
