package me.manga.kira.domain.usecase.complaint

import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.repository.AdminComplaintActionRepository

/**
 * Use case: change the status of an existing complaint with admin privileges.
 *
 * Phase 7.x.complaint.admin.actions rework. Thin pass-through over
 * [AdminComplaintActionRepository.changeStatus]. The repository impl reconstructs a full legacy
 * `Complaint` from [ComplaintSummary] + new status, preserving all other fields including
 * `metadata` (so closure-reason fields survive status flips).
 *
 * **Caller obligation**: the `:ui` dialog enforces "Update" button disabled when
 * `selectedStatus == complaint.status` to prevent no-op writes. The VM also short-circuits via
 * the in-flight [isSubmittingAction] guard. This use case does NOT re-check equality — the
 * legacy use case writes whatever is passed.
 *
 * Contract §6 SRP: one rule — "issue a status-change intent to the repository".
 *
 * Contract §6 DIP: depends on [AdminComplaintActionRepository], not on the `:data` impl.
 *
 * **Why pass [complaint] not just an id**: legacy `updateComplaint(complaint)` overwrites the
 * entire document with the passed value. Without [complaint]'s non-status fields, the impl
 * would need to re-fetch (extra round-trip) or null-out fields server-side (data loss).
 * Passing the full [ComplaintSummary] is the cheapest correct option — the caller (VM) already
 * has it from the list state.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster123.staleKdocSweep.cascade,
 * Task #579, 2026-05-28): classified as follows after recursive symbol
 * verification (seventy-first sibling of the cluster57-122 sweep — third
 * file of the wave-21 `:domain/usecase/complaint/` admin-side 5-file
 * batch alongside AdminDelete plus AdminEdit plus AddClosureReason plus
 * ObserveAll):
 *  (a) "Phase 7.x.complaint.admin.actions rework — thin pass-through over
 *  AdminComplaintActionRepository.changeStatus; the repository impl
 *  reconstructs a full legacy Complaint from ComplaintSummary plus new
 *  status, preserving all other fields including metadata (so closure-
 *  reason fields survive status flips)" — LIVE-NOT-STALE. AdminComplaint-
 *  ViewModel.kt L11 import, L84 ctor `private val changeStatus:
 *  ChangeComplaintStatusUseCase`, L196 realization `val result =
 *  changeStatus(target, newStatus)` inside the AdminComplaintIntent.
 *  OnStatusChangeConfirm branch. L33-36 single-line pass-through
 *  `repository.changeStatus(complaint, newStatus)`. Full-record-
 *  reconstruction-from-summary-plus-status-preserving-metadata posture
 *  verified at cluster #468 sibling sweep (complaintactionrepo.staleKdoc-
 *  Sweep) — the `:data` impl preserves the metadata `Map<String, Any>?`
 *  field across status flips (closure-reason audit fields survive an
 *  OPEN-to-IN_PROGRESS-to-CLOSED status flip, satisfying the legacy-
 *  parity audit-trail requirement).
 *  (b) "Caller obligation — the `:ui` dialog enforces Update button
 *  disabled when selectedStatus == complaint.status to prevent no-op
 *  writes; the VM also short-circuits via the in-flight isSubmitting-
 *  Action guard; this use case does NOT re-check equality — the legacy
 *  use case writes whatever is passed; Why pass complaint not just an
 *  id — legacy updateComplaint(complaint) overwrites the entire document
 *  with the passed value; without complaint's non-status fields, the
 *  impl would need to re-fetch (extra round-trip) or null-out fields
 *  server-side (data loss); passing the full ComplaintSummary is the
 *  cheapest correct option — the caller (VM) already has it from the
 *  list state" — LIVE-NOT-STALE. AdminComplaintIntent.kt L187 KDoc
 *  reference to ChangeComplaintStatusUseCase confirms the dispatch
 *  site; UI-side selectedStatus-versus-complaint.status equality-gate
 *  verified at cluster #461 sibling sweep (complaintdialogs.staleKdoc-
 *  Sweep.cascade); isSubmittingAction-in-flight-guard verified at the
 *  same sibling cluster on the AdminComplaintViewModel side. The "why
 *  pass complaint not just an id" round-trip-avoidance plus data-loss-
 *  avoidance dual-rationale is upheld by the no-re-fetch single-line
 *  repository delegate — the impl takes the passed-in non-status fields
 *  at face value, no extra Firestore read needed.
 *  (c) §6 SRP + §6 DIP + Koin factory lifecycle — LIVE-NOT-STALE.
 *  ComplaintAdminReworkModule.kt L149 `factory { ChangeComplaintStatus-
 *  UseCase(get()) }` realization; L5 import binds `:domain`-layer
 *  interface, not `:data`-layer impl. The "unprefixed" naming (no
 *  `Admin` prefix despite being admin-scoped) is upheld by the intra-
 *  cluster123 peer cross-ref to AdminEditComplaintUseCase's (sibling
 *  70th) naming-convention bullet: ChangeStatus has no user-side
 *  counterpart, so the unprefixed name is unambiguous; this is
 *  internally consistent with sibling AddClosureReason (sibling 72nd,
 *  also unprefixed, also no user-side counterpart) and asymmetric with
 *  AdminDelete plus AdminEdit (siblings 69th plus 70th, prefixed
 *  because user-side counterparts exist).
 *  Three classifications STAND on their own merits. Original Phase
 *  7.x.complaint.admin.actions-era prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
class ChangeComplaintStatusUseCase(
    private val repository: AdminComplaintActionRepository,
) {
    suspend operator fun invoke(
        complaint: ComplaintSummary,
        newStatus: ComplaintStatus,
    ): Result<Unit> = repository.changeStatus(complaint, newStatus)
}
