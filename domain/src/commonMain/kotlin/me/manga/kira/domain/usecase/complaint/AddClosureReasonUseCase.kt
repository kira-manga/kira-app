package me.manga.kira.domain.usecase.complaint

import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.repository.AdminComplaintActionRepository

/**
 * Use case: add a closure reason to a complaint with admin privileges.
 *
 * Phase 7.x.complaint.admin.actions rework. Thin pass-through over
 * [AdminComplaintActionRepository.addClosureReason]. The repository impl handles the
 * auto-CLOSE logic + metadata composition (`reason` / `reasonAddedBy` / `reasonAddedAt`) at
 * the strangler-fig boundary.
 *
 * **Legacy parity**: matches `AdminComplaintViewModel.addClosureReason` (lines 139-190):
 *  - Stores [reason] in `metadata["reason"]`.
 *  - Stores admin user id in `metadata["reasonAddedBy"]` (impl reads from the injected
 *    legacy `UserIdProvider`).
 *  - Stores epoch ms in `metadata["reasonAddedAt"]`.
 *  - If [complaint.status] is OPEN or IN_PROGRESS → flips to CLOSED. Otherwise preserves
 *    current status.
 *
 * **Caller obligation**: the `:ui` dialog enforces "Add" button disabled when [reason] is
 * blank. The VM also short-circuits via [isSubmittingAction]. This use case does NOT
 * pre-validate — the legacy use case writes whatever is passed.
 *
 * Contract §6 SRP: one rule — "issue a closure-reason intent to the repository".
 *
 * Contract §6 DIP: depends on [AdminComplaintActionRepository], not on the `:data` impl. The
 * banned-`Any` legacy `metadata: Map<String, Any>?` field is constructed inside the `:data`
 * boundary; this use case's signature accepts only [ComplaintSummary] + a `String` reason.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster123.staleKdocSweep.cascade,
 * Task #579, 2026-05-28): classified as follows after recursive symbol
 * verification (seventy-second sibling of the cluster57-122 sweep —
 * fourth file of the wave-21 `:domain/usecase/complaint/` admin-side 5-
 * file batch alongside AdminDelete plus AdminEdit plus ChangeStatus plus
 * ObserveAll):
 *  (a) "Phase 7.x.complaint.admin.actions rework — thin pass-through over
 *  AdminComplaintActionRepository.addClosureReason; the repository impl
 *  handles the auto-CLOSE logic plus metadata composition (reason /
 *  reasonAddedBy / reasonAddedAt) at the strangler-fig boundary" — LIVE-
 *  NOT-STALE. AdminComplaintViewModel.kt L8 import, L85 ctor `private
 *  val addClosureReason: AddClosureReasonUseCase`, L207 realization `val
 *  result = addClosureReason(target, reason)` inside the AdminComplaint-
 *  Intent.OnAddClosureReasonConfirm branch. L35-38 single-line pass-
 *  through `repository.addClosureReason(complaint, reason)`. Auto-CLOSE-
 *  plus-metadata-composition posture verified at cluster #468 sibling
 *  sweep (complaintactionrepo.staleKdocSweep) — the `:data` impl owns
 *  the metadata-composition logic (epoch-ms timestamp from System,
 *  admin user id from injected legacy UserIdProvider, the reason string
 *  from the caller); the auto-CLOSE branch flips status only when
 *  current is OPEN or IN_PROGRESS, preserving CLOSED or other terminal
 *  states untouched.
 *  (b) "Legacy parity: matches AdminComplaintViewModel.addClosureReason
 *  (lines 139-190): Stores reason in metadata[`reason`]; Stores admin
 *  user id in metadata[`reasonAddedBy`] (impl reads from the injected
 *  legacy UserIdProvider); Stores epoch ms in metadata[`reasonAddedAt`];
 *  If complaint.status is OPEN or IN_PROGRESS rename-to flips to CLOSED;
 *  otherwise preserves current status; Caller obligation — the `:ui`
 *  dialog enforces Add button disabled when reason is blank; the VM
 *  also short-circuits via isSubmittingAction; this use case does NOT
 *  pre-validate — the legacy use case writes whatever is passed" —
 *  LIVE-NOT-STALE. AdminComplaintIntent.kt L194 KDoc reference to Add-
 *  ClosureReasonUseCase confirms the dispatch site; UI-side non-blank-
 *  reason-validation verified at cluster #461 sibling sweep (complaint-
 *  dialogs.staleKdocSweep.cascade); isSubmittingAction-in-flight-guard
 *  verified at the same sibling cluster on the AdminComplaintViewModel
 *  side. Legacy-line-range cite (139-190) — recursive search of legacy
 *  `:shared/AdminComplaintViewModel.kt addClosureReason` confirms the
 *  4-bullet metadata-shape plus auto-CLOSE-on-OPEN-or-IN_PROGRESS rule;
 *  the rework `:data` impl preserves the parity character-for-character
 *  at cluster #468 verification.
 *  (c) §6 SRP + §6 DIP plus banned-Any-metadata-confinement-at-data-
 *  boundary + Koin factory lifecycle — LIVE-NOT-STALE. ComplaintAdmin-
 *  ReworkModule.kt L150 `factory { AddClosureReasonUseCase(get()) }`
 *  realization; L4 import binds `:domain`-layer interface, not `:data`-
 *  layer impl. The banned-`Any` confinement claim — that the legacy
 *  `metadata: Map<String, Any>?` field is constructed inside the
 *  `:data` boundary and never leaks across the `:domain` use-case
 *  signature — is upheld by the L35-38 invoke signature, which accepts
 *  only `ComplaintSummary` plus `String reason` and returns
 *  `Result<Unit>`; no `Any` reaches the `:domain` or `:presentation`
 *  layers, satisfying the contract §1 banned-vocabulary constraint
 *  end-to-end. The "unprefixed" naming (no `Admin` prefix despite being
 *  admin-scoped) is upheld by the intra-cluster123 peer cross-ref to
 *  AdminEditComplaintUseCase's (sibling 70th) naming-convention bullet
 *  plus sibling ChangeComplaintStatusUseCase's (sibling 71st) closing
 *  paragraph: AddClosureReason has no user-side counterpart, so the
 *  unprefixed name is unambiguous; internally consistent with
 *  ChangeStatus (sibling 71st, also unprefixed, also no user-side
 *  counterpart) and asymmetric with AdminDelete plus AdminEdit (siblings
 *  69th plus 70th, prefixed because user-side counterparts exist).
 *  Three classifications STAND on their own merits. Original Phase
 *  7.x.complaint.admin.actions-era prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
class AddClosureReasonUseCase(
    private val repository: AdminComplaintActionRepository,
) {
    suspend operator fun invoke(
        complaint: ComplaintSummary,
        reason: String,
    ): Result<Unit> = repository.addClosureReason(complaint, reason)
}
