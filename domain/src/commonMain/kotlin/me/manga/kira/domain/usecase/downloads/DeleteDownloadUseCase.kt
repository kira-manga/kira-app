package me.manga.kira.domain.usecase.downloads

import me.manga.kira.domain.repository.DownloadsActionRepository

/**
 * Use case: delete the FAILED / SUCCESS download row identified by [chapterId].
 *
 * Phase 7.x.downloads.actions rework. Thin pass-through over
 * [DownloadsActionRepository.deleteDownload].
 *
 * **Scope clarification**: this deletes the queue history row (the
 * `chapter_downloads` Room entity). The downloaded chapter files (if any) remain on disk
 * for the reader to consume — legacy `DownloadRepository.deleteDownload` has the same
 * scope, no file-system cleanup. Restoring file cleanup is a Phase 10.x concern (see
 * legacy `DownloadRepository.deleteDownload` KDoc TODO).
 *
 * **Caller obligation**: the rework `:ui` shows the delete affordance on FAILED rows
 * (alongside retry) and on SUCCESS rows. The use case itself does NOT prompt for
 * confirmation — same posture as the legacy `DownloadItemCard` which fires the callback
 * directly on icon tap. If user-confirmation UX is desired later, the dialog lives in
 * `:ui` (same pattern as `ComplaintActionDialog`'s `DeleteConfirmationContent`); the
 * use case stays a pure pass-through.
 *
 * Contract §6 SRP: one rule — "issue a delete intent to the repository".
 *
 * Contract §6 DIP: depends on [DownloadsActionRepository], not on the `:data` impl or the
 * legacy `:shared` facade.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `downloadsReworkModule` (stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster121.staleKdocSweep.cascade,
 * Task #577, 2026-05-28): classified as follows after recursive symbol
 * verification (sixty-fifth sibling of the cluster57-120 sweep — third
 * file of the wave-20 `:domain/usecase/downloads/` 5-file batch alongside
 * Cancel plus CancelRunning plus Enqueue plus Observe; RetryDownloadUse-
 * Case deferred to cluster122 follow-up to respect ≤5-file commit cap):
 *  (a) "Phase 7.x.downloads.actions rework — thin pass-through over
 *  DownloadsActionRepository.deleteDownload" — LIVE-NOT-STALE. Downloads-
 *  ViewModel.kt L10 import, L158 ctor `private val deleteDownload:
 *  DeleteDownloadUseCase`, L217 realization `val result = deleteDownload(
 *  chapterId)` inside the DownloadsIntent.OnDelete branch. L35-36 single-
 *  line pass-through `repository.deleteDownload(chapterId)`.
 *  (b) "Scope clarification — this deletes the queue history row (the
 *  `chapter_downloads` Room entity); the downloaded chapter files (if
 *  any) remain on disk for the reader to consume; legacy DownloadReposi-
 *  tory.deleteDownload has the same scope, no file-system cleanup;
 *  restoring file cleanup is a Phase 10.x concern (see legacy Download-
 *  Repository.deleteDownload KDoc TODO)" — LIVE-NOT-STALE plus FORECAST-
 *  NOT-YET-FULFILLED. Row-only-delete-no-file-cleanup posture verified at
 *  cluster #442 sibling sweep (downloadsactionrepo.staleKdocSweep) —
 *  DownloadsActionRepositoryImpl `:data` impl delegates to legacy
 *  DownloadRepository.deleteDownload which only DELETEs the Room row.
 *  Phase 10.x file-system-cleanup forecast — FORECAST-NOT-YET-FULFILLED.
 *  Recursive search for chapter-file-deletion-on-delete-download returns
 *  zero matches outside legacy DownloadRepository.deleteDownload-KDoc-
 *  TODO; the row-only scope is unchanged.
 *  (c) "Caller obligation — the rework `:ui` shows the delete affordance
 *  on FAILED rows (alongside retry) and on SUCCESS rows; the use case
 *  itself does NOT prompt for confirmation — same posture as the legacy
 *  DownloadItemCard which fires the callback directly on icon tap; if
 *  user-confirmation UX is desired later, the dialog lives in `:ui`
 *  (same pattern as ComplaintActionDialog's DeleteConfirmationContent);
 *  the use case stays a pure pass-through" — LIVE-NOT-STALE plus
 *  FORECAST-NOT-YET-FULFILLED. `:ui` FAILED-plus-SUCCESS state-branching
 *  verified at cluster #444 sibling sweep (downloads.staleKdocSweep.
 *  cascade); ComplaintActionDialog.DeleteConfirmationContent dialog-in-
 *  `:ui` precedent verified at cluster #468 sibling sweep (complaint-
 *  actiondialog.staleKdocSweep). User-confirmation-dialog forecast —
 *  FORECAST-NOT-YET-FULFILLED. Recursive search for delete-download-
 *  confirmation-dialog returns zero matches; the icon-tap-fires-callback-
 *  directly posture is unchanged.
 *  (d) §6 SRP + §6 DIP + Koin factory lifecycle — LIVE-NOT-STALE.
 *  DownloadsReworkModule.kt L137 `factory { DeleteDownloadUseCase(get())
 *  }` realization; L3 import binds `:domain`-layer interface, not
 *  `:data`-layer impl.
 *  Four classifications STAND on their own merits. Original Phase
 *  7.x.downloads.actions-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class DeleteDownloadUseCase(
    private val repository: DownloadsActionRepository,
) {
    suspend operator fun invoke(chapterId: Long): Result<Unit> =
        repository.deleteDownload(chapterId)
}
