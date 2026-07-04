package me.manga.kira.domain.usecase.complaint

import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.AdminComplaintActionRepository

/**
 * Use case: admin edits the type + subject + body of any user's complaint.
 *
 * Phase 7.x.complaint.admin.edit rework. Thin pass-through over
 * [AdminComplaintActionRepository.editComplaint] — `type` is admin-mutable (unlike the
 * user-side edit); preserves the parent's `userId`, `createdAt`, `status`, AND `metadata`
 * (the impl reconstructs the full legacy `Complaint` from a re-fetch + the new
 * type/subject/body, since legacy `UpdateComplaintUseCase` takes a full record).
 *
 * **Sibling to user-side [EditComplaintUseCase]** — same shape, different scope:
 *  - User-side edit: caller mutates their OWN complaint; Firestore rules gate by userId.
 *  - Admin-side edit: caller mutates ANY user's complaint; gated client-side by
 *    [me.manga.kira.admin.Admin.isAdmin] at the navigation entry.
 *
 * **Admin-distinct: metadata preservation**. The user-side impl nulls metadata (user has no
 * closure metadata). Admin impl preserves it (closure-reason audit fields must survive
 * subject/body edits). See [AdminComplaintActionRepository.editComplaint] KDoc.
 *
 * **Naming convention**: `Admin` prefix mirrors [AdminDeleteComplaintUseCase] — the prefix is
 * applied when a same-action use case already exists in the user-side scope
 * ([EditComplaintUseCase]). [ChangeComplaintStatusUseCase] and [AddClosureReasonUseCase] omit
 * the prefix because they have no user-side counterpart.
 *
 * **Validation policy**: this use case does NOT pre-validate. The legacy
 * `UpdateComplaintUseCase` doesn't validate either — it just writes. Validation is the UI's
 * job (the dialog enforces non-blank subject + non-blank body + ≤ 1000 chars before enabling
 * Save). Same posture as the user-side use case.
 *
 * Contract §6 SRP: one rule — "issue an admin edit intent to the repository".
 *
 * Contract §6 DIP: depends on [AdminComplaintActionRepository], not on the `:data` impl.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster123.staleKdocSweep.cascade,
 * Task #579, 2026-05-28): classified as follows after recursive symbol
 * verification (seventieth sibling of the cluster57-122 sweep — second
 * file of the wave-21 `:domain/usecase/complaint/` admin-side 5-file
 * batch alongside AdminDelete plus ChangeStatus plus AddClosureReason
 * plus ObserveAll):
 *  (a) "Phase 7.x.complaint.admin.edit rework — thin pass-through over
 *  AdminComplaintActionRepository.editComplaint; preserves the parent's
 *  userId, type, createdAt, status, AND metadata (the impl reconstructs
 *  the full legacy Complaint from a re-fetch plus the new subject/body,
 *  since legacy UpdateComplaintUseCase takes a full record)" — LIVE-NOT-
 *  STALE. AdminComplaintViewModel.kt L10 import, L87 ctor `private val
 *  adminEditComplaint: AdminEditComplaintUseCase`, L229 realization `val
 *  result = adminEditComplaint(target, subject, body)` inside the Admin-
 *  ComplaintIntent.OnEditConfirm branch. L41-45 single-line pass-through
 *  `repository.editComplaint(original, subject, body)`. Full-record re-
 *  fetch-plus-merge posture verified at cluster #468 sibling sweep
 *  (complaintactionrepo.staleKdocSweep) — the `:data` impl reads the
 *  legacy record, overlays the new subject/body fields, and writes the
 *  full record back via legacy UpdateComplaintUseCase, preserving
 *  metadata and other admin-relevant fields.
 *  (b) "Sibling to user-side EditComplaintUseCase — same shape, different
 *  scope; user-side edit: caller mutates their OWN complaint, Firestore
 *  rules gate by userId; admin-side edit: caller mutates ANY user's
 *  complaint, gated client-side by Admin.isAdmin at the navigation
 *  entry" — LIVE-NOT-STALE. Admin.isAdmin nav-entry-gate verified at
 *  cluster #461 sibling sweep (settingsreworkroute.staleKdocSweep);
 *  Firestore-rule-userId-gating-on-user-side posture verified at cluster
 *  #468 sibling sweep (complaintactionrepo.staleKdocSweep). Two-sibling-
 *  scope distinction stands intact — admin-side use case is invoked
 *  exclusively from AdminComplaintViewModel; user-side use case is
 *  invoked exclusively from ComplaintViewModel.
 *  (c) "Admin-distinct: metadata preservation; the user-side impl nulls
 *  metadata (user has no closure metadata); admin impl preserves it
 *  (closure-reason audit fields must survive subject/body edits); see
 *  AdminComplaintActionRepository.editComplaint KDoc; Naming convention:
 *  Admin prefix mirrors AdminDeleteComplaintUseCase — the prefix is
 *  applied when a same-action use case already exists in the user-side
 *  scope (EditComplaintUseCase); ChangeComplaintStatusUseCase and
 *  AddClosureReasonUseCase omit the prefix because they have no user-
 *  side counterpart" — LIVE-NOT-STALE. Metadata-preserve-on-admin-edit-
 *  versus-null-on-user-edit asymmetry verified at cluster #468 sibling
 *  sweep — the `:data` impl preserves the legacy metadata `Map<String,
 *  Any>?` field across admin edits (closure-reason audit trail survives)
 *  but the user-side counterpart nulls it (user has no closure
 *  metadata to preserve). Admin-prefix-naming-convention upheld by
 *  intra-cluster123 peer cross-ref: AdminDelete (sibling 69th, prefixed
 *  because user-side Delete exists), AdminEdit (this file, sibling
 *  70th, prefixed because user-side Edit exists), ChangeStatus (sibling
 *  71st, unprefixed because no user-side change-status exists),
 *  AddClosureReason (sibling 72nd, unprefixed because no user-side add-
 *  closure-reason exists) — the four-way name-convention pattern is
 *  internally consistent.
 *  (d) "Validation policy — this use case does NOT pre-validate; the
 *  legacy UpdateComplaintUseCase doesn't validate either — it just
 *  writes; validation is the UI's job (the dialog enforces non-blank
 *  subject plus non-blank body plus ≤ 1000 chars before enabling Save);
 *  same posture as the user-side use case" — LIVE-NOT-STALE. UI-side
 *  non-blank-plus-length-validation verified at cluster #461 sibling
 *  sweep (complaintdialogs.staleKdocSweep.cascade); §6 SRP + §6 DIP +
 *  Koin factory lifecycle LIVE: ComplaintAdminReworkModule.kt L152
 *  `factory { AdminEditComplaintUseCase(get()) }` realization; L4
 *  import binds `:domain`-layer interface, not `:data`-layer impl.
 *  Four classifications STAND on their own merits. Original Phase
 *  7.x.complaint.admin.edit-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class AdminEditComplaintUseCase(
    private val repository: AdminComplaintActionRepository,
) {
    suspend operator fun invoke(
        original: ComplaintSummary,
        type: ComplaintType,
        subject: String,
        body: String,
    ): Result<Unit> = repository.editComplaint(original, type, subject, body)
}
