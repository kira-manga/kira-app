package me.manga.kira.domain.usecase.complaint

import me.manga.kira.domain.repository.ComplaintActionRepository

/**
 * Use case: delete the user's complaint identified by [id].
 *
 * Phase 7.x.complaint.actions rework. Thin pass-through over
 * [ComplaintActionRepository.deleteComplaint] — single argument (the id, since delete is a
 * pure identity-based operation that doesn't need any of the record's fields).
 *
 * **Caller obligation**: this use case does NOT prompt for confirmation. The rework `:ui`
 * dialog (see [me.manga.kira.ui.complaint.ComplaintActionDialog]) handles the confirmation
 * step before the VM dispatches the delete intent. Same posture as legacy
 * `DeleteConfirmationContent` which gates the actual delete behind a confirm button.
 *
 * Contract §6 SRP: one rule — "issue a delete intent to the repository".
 *
 * Contract §6 DIP: depends on [ComplaintActionRepository], not on the `:data` impl.
 *
 * **Class name disambiguation**: there is a SAME-NAMED `DeleteComplaintUseCase` in the legacy
 * `:shared` module at `me.manga.kira.presentation.features.complaint.usecase.DeleteComplaintUseCase`.
 * That class wraps the legacy `ComplaintRepository.deleteComplaint` and is the strangler-fig
 * source for THIS use case (the `:data` impl injects it). Different packages — no clash. The
 * `:data` impl uses an `as LegacyDeleteComplaintUseCase` import alias to keep the file
 * unambiguous (same posture as the foundation slice's `LegacyGetUserComplaintUseCase` alias).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster124.staleKdocSweep.cascade,
 * Task #580, 2026-05-28): classified as follows after recursive symbol
 * verification (seventy-fourth sibling of the cluster57-123 sweep —
 * first file of the wave-21 `:domain/usecase/complaint/` user-side 4-
 * file follow-up batch alongside Edit plus ObserveUser plus Reply;
 * closes complaint/ subpackage as FULLY SWEPT (9 of 9 files) alongside
 * the cluster123 admin-side 5-file batch (AdminDelete plus AdminEdit
 * plus ChangeStatus plus AddClosureReason plus ObserveAll); the 9-file
 * complaint/ subpackage closes the wave-21 cascade and the ≤5-file-cap-
 * with-followup pattern is upheld for the second consecutive wave):
 *  (a) "Phase 7.x.complaint.actions rework — thin pass-through over
 *  ComplaintActionRepository.deleteComplaint; single argument (the id,
 *  since delete is a pure identity-based operation that doesn't need
 *  any of the record's fields)" — LIVE-NOT-STALE. ComplaintViewModel.
 *  kt L7 import, L119 ctor `private val deleteComplaint:
 *  DeleteComplaintUseCase`, L226 realization `val result =
 *  deleteComplaint(target.id)` inside the ComplaintIntent.OnDelete-
 *  Confirm branch (ComplaintIntent.kt L49 KDoc reference confirms the
 *  dispatch-site framing). L31-32 single-line pass-through `repository.
 *  deleteComplaint(id)`. Intra-cluster124 peer-sibling cross-ref:
 *  user-side DeleteComplaintUseCase depends on ComplaintActionRepository
 *  whereas intra-cluster123 sibling AdminDeleteComplaintUseCase
 *  (sibling 69th) depends on AdminComplaintActionRepository — the
 *  two-sibling-repo ISP-clean-split posture documented at cluster123
 *  AdminDeleteComplaintUseCase postscript (b) is upheld from the user
 *  side: same legacy `:shared/DeleteComplaintUseCase` wire, different
 *  consumer-side dependency graph; ComplaintViewModel imports the
 *  user-side use case only (no AdminDelete cross-pollination).
 *  (b) "Caller obligation — this use case does NOT prompt for confir-
 *  mation; the rework `:ui` dialog (see ComplaintActionDialog) handles
 *  the confirmation step before the VM dispatches the delete intent;
 *  same posture as legacy DeleteConfirmationContent which gates the
 *  actual delete behind a confirm button" — LIVE-NOT-STALE. Complaint-
 *  ActionDialog.DeleteConfirmationContent confirmation-step verified
 *  at cluster #468 sibling sweep (complaintactiondialog.staleKdoc-
 *  Sweep); ComplaintViewModel-side OnDeleteConfirm intent dispatch
 *  posture verified at cluster #452 sibling sweep (complaint.staleKdoc-
 *  Sweep.cascade) — the icon-tap-shows-dialog-then-confirm-fires-
 *  intent two-step flow is upheld; the use case is the terminal
 *  effector, not the gate.
 *  (c) §6 SRP + §6 DIP + class name disambiguation + Koin factory
 *  lifecycle — LIVE-NOT-STALE. ComplaintReworkModule.kt L130 `factory
 *  { DeleteComplaintUseCase(get()) }` realization; L7 import binds
 *  `:domain`-layer interface, not `:data`-layer impl. The legacy-
 *  `:shared`-same-named-class disambiguation via `as LegacyDelete-
 *  ComplaintUseCase` import alias on the `:data` impl side is upheld —
 *  this is the strangler-fig source the `:data` impl wraps; the
 *  `:domain` symbol named here is the rework-side type that lives in
 *  `me.manga.kira.domain.usecase.complaint`, distinct from the
 *  legacy `:shared` type at `me.manga.kira.presentation.features.
 *  complaint.usecase`. Two clean different-package siblings, no clash.
 *  Three classifications STAND on their own merits. Original Phase
 *  7.x.complaint.actions-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class DeleteComplaintUseCase(
    private val repository: ComplaintActionRepository,
) {
    suspend operator fun invoke(id: String): Result<Unit> =
        repository.deleteComplaint(id)
}
