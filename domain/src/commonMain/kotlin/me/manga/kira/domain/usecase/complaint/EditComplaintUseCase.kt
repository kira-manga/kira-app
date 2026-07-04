package me.manga.kira.domain.usecase.complaint

import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.repository.ComplaintActionRepository

/**
 * Use case: edit the subject and body of an existing complaint.
 *
 * Phase 7.x.complaint.actions rework. Thin pass-through over
 * [ComplaintActionRepository.editComplaint] — preserves the parent's `userId`, `type`,
 * `createdAt`, and `status` (the impl reconstructs the full legacy `Complaint` from
 * [ComplaintSummary] + new fields, since legacy `UpdateComplaintUseCase` takes a full record).
 *
 * **Why pass [original] instead of just an id**: legacy `updateComplaint(complaint)` overwrites
 * the entire document with the passed value. Without [original]'s non-edited fields, the impl
 * would either need to re-fetch (extra round-trip) or null-out fields server-side (data loss).
 * Passing [original] is the cheapest correct option — the caller (rework VM) already has the
 * full [ComplaintSummary] from the list state.
 *
 * **Validation policy**: this use case does NOT pre-validate. The legacy
 * `UpdateComplaintUseCase` itself does not validate either — it just calls
 * `repo.updateComplaint(complaint)`. Validation is the UI's job (the legacy dialog enforces
 * non-blank subject + non-blank body + ≤ 1000 chars before enabling the Save button); the
 * rework `:ui` enforces the same guards.
 *
 * Contract §6 SRP: one rule — "issue an edit intent to the repository".
 *
 * Contract §6 DIP: depends on [ComplaintActionRepository], not on the `:data` impl.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster124.staleKdocSweep.cascade,
 * Task #580, 2026-05-28): classified as follows after recursive symbol
 * verification (seventy-fifth sibling of the cluster57-123 sweep —
 * second file of the wave-21 `:domain/usecase/complaint/` user-side 4-
 * file follow-up batch alongside Delete plus ObserveUser plus Reply):
 *  (a) "Phase 7.x.complaint.actions rework — thin pass-through over
 *  ComplaintActionRepository.editComplaint; preserves the parent's
 *  userId, type, createdAt, and status (the impl reconstructs the full
 *  legacy Complaint from ComplaintSummary plus new fields, since
 *  legacy UpdateComplaintUseCase takes a full record)" — LIVE-NOT-
 *  STALE. ComplaintViewModel.kt L8 import, L118 ctor `private val
 *  editComplaint: EditComplaintUseCase`, L215 realization `val result
 *  = editComplaint(original, subject, body)` inside the ComplaintIntent.
 *  OnEditConfirm branch (ComplaintIntent.kt L46 KDoc reference confirms
 *  the dispatch-site framing). L33-37 single-line pass-through
 *  `repository.editComplaint(original, subject, body)`. Full-record-
 *  reconstruction-from-summary posture verified at cluster #468 sibling
 *  sweep (complaintactionrepo.staleKdocSweep) — the `:data` impl reads
 *  the legacy record via the strangler-fig boundary, overlays the new
 *  subject/body fields, and writes back the full record via legacy
 *  UpdateComplaintUseCase, preserving the parent's non-edited fields.
 *  (b) "Why pass original instead of just an id — legacy updateCom-
 *  plaint(complaint) overwrites the entire document with the passed
 *  value; without original's non-edited fields, the impl would either
 *  need to re-fetch (extra round-trip) or null-out fields server-side
 *  (data loss); passing original is the cheapest correct option — the
 *  caller (rework VM) already has the full ComplaintSummary from the
 *  list state" — LIVE-NOT-STALE. Round-trip-avoidance plus data-loss-
 *  avoidance dual-rationale is upheld by the no-re-fetch single-line
 *  repository delegate — same posture as intra-cluster123 sibling
 *  ChangeComplaintStatusUseCase (sibling 71st) which articulates the
 *  identical why-pass-complaint-not-id rationale from the admin side.
 *  Two sibling user-side-versus-admin-side use cases independently
 *  arriving at the same round-trip-avoidance design — the asymmetric-
 *  metadata-treatment delta between them notwithstanding — corroborates
 *  the architectural soundness of the full-record-pass-through pattern.
 *  (c) "Validation policy — this use case does NOT pre-validate; the
 *  legacy UpdateComplaintUseCase itself does not validate either — it
 *  just calls repo.updateComplaint(complaint); validation is the UI's
 *  job (the legacy dialog enforces non-blank subject plus non-blank
 *  body plus ≤ 1000 chars before enabling the Save button); the rework
 *  `:ui` enforces the same guards" — LIVE-NOT-STALE. UI-side non-blank-
 *  plus-length-validation verified at cluster #468 sibling sweep
 *  (complaintactiondialog.staleKdocSweep) — the rework `:ui` Complaint-
 *  ActionDialog.EditComplaintContent enforces the same non-blank-
 *  subject + non-blank-body + ≤1000-char gating before enabling the
 *  Save button, matching legacy parity. Intra-cluster123 peer-sibling
 *  cross-ref to AdminEditComplaintUseCase (sibling 70th) documents the
 *  admin-distinct-metadata-preservation asymmetry on the admin side:
 *  the user-side impl HERE nulls metadata (user has no closure
 *  metadata to preserve), whereas the admin-side impl preserves it
 *  (closure-reason audit fields must survive subject/body edits). The
 *  Admin-prefix-naming-convention bullet on the cluster123 sibling 70th
 *  postscript explicitly notes that EditComplaintUseCase (THIS file)
 *  carries the unprefixed name because it predates the admin variant —
 *  it is the original user-side use case from which the prefixed
 *  admin variant was forked.
 *  (d) §6 SRP + §6 DIP + Koin factory lifecycle — LIVE-NOT-STALE.
 *  ComplaintReworkModule.kt L129 `factory { EditComplaintUseCase(get())
 *  }` realization; L8 import binds `:domain`-layer interface, not
 *  `:data`-layer impl.
 *  Four classifications STAND on their own merits. Original Phase
 *  7.x.complaint.actions-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class EditComplaintUseCase(
    private val repository: ComplaintActionRepository,
) {
    suspend operator fun invoke(
        original: ComplaintSummary,
        subject: String,
        body: String,
    ): Result<Unit> = repository.editComplaint(original, subject, body)
}
