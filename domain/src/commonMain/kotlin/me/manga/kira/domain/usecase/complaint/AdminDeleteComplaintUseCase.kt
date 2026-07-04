package me.manga.kira.domain.usecase.complaint

import me.manga.kira.domain.repository.AdminComplaintActionRepository

/**
 * Use case: delete a complaint with admin privileges.
 *
 * Phase 7.x.complaint.admin.actions rework. Thin pass-through over
 * [AdminComplaintActionRepository.deleteComplaint] — single argument (the id).
 *
 * **Why a separate admin variant of [DeleteComplaintUseCase]?**: ISP §6. The user-side
 * [DeleteComplaintUseCase] depends on [me.manga.kira.domain.repository.ComplaintActionRepository]
 * (the user-side WRITE surface, which also has reply/edit). The admin variant depends on
 * [AdminComplaintActionRepository] (the admin WRITE surface). Same legacy wire (`:shared/
 * DeleteComplaintUseCase`), different consumer-side dependency graph.
 *
 * The split prevents an admin-side VM from depending on user-side reply/edit it never uses,
 * and vice versa. Two clean siblings, same posture as the sibling repos themselves.
 *
 * **Caller obligation**: the `:ui` dialog enforces a confirmation step before dispatching the
 * delete intent. The VM short-circuits via [isSubmittingAction]. This use case does NOT
 * pre-validate.
 *
 * Contract §6 SRP: one rule — "issue an admin delete intent to the repository".
 *
 * Contract §6 DIP: depends on [AdminComplaintActionRepository], not on the `:data` impl.
 *
 * **Class name disambiguation**: the user-side [DeleteComplaintUseCase] shares the verb but
 * not the prefix. The `Admin` prefix makes the intended caller explicit at the import site
 * (rework admin VM imports `AdminDeleteComplaintUseCase`; user-side VM imports
 * `DeleteComplaintUseCase`). Different packages would also work but the prefix is clearer.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster123.staleKdocSweep.cascade,
 * Task #579, 2026-05-28): classified as follows after recursive symbol
 * verification (sixty-ninth sibling of the cluster57-122 sweep — first
 * file of the wave-21 `:domain/usecase/complaint/` admin-side 5-file
 * batch alongside AdminEdit plus ChangeStatus plus AddClosureReason plus
 * ObserveAll; user-side 4 files (Delete plus Edit plus ObserveUser plus
 * Reply) deferred to cluster124 follow-up to respect ≤5-file commit cap;
 * the 9-file complaint/ subpackage thus splits cleanly along the admin-
 * versus-user-side ISP §6 boundary that the original prose itself
 * articulates):
 *  (a) "Phase 7.x.complaint.admin.actions rework — thin pass-through over
 *  AdminComplaintActionRepository.deleteComplaint; single argument (the
 *  id)" — LIVE-NOT-STALE. AdminComplaintViewModel.kt L9 import, L86 ctor
 *  `private val adminDeleteComplaint: AdminDeleteComplaintUseCase`, L218
 *  realization `val result = adminDeleteComplaint(target.id)` inside the
 *  AdminComplaintIntent.OnDeleteConfirm branch. L36-37 single-line pass-
 *  through `repository.deleteComplaint(id)`.
 *  (b) "Why a separate admin variant of DeleteComplaintUseCase — ISP §6;
 *  the user-side DeleteComplaintUseCase depends on ComplaintAction-
 *  Repository (user-side WRITE surface, which also has reply/edit); the
 *  admin variant depends on AdminComplaintActionRepository (admin WRITE
 *  surface); same legacy wire (`:shared/DeleteComplaintUseCase`),
 *  different consumer-side dependency graph; the split prevents an admin-
 *  side VM from depending on user-side reply/edit it never uses, and
 *  vice versa; two clean siblings, same posture as the sibling repos
 *  themselves" — LIVE-NOT-STALE. ISP-clean-split posture verified at
 *  cluster #468 sibling sweep (complaintactionrepo.staleKdocSweep) — the
 *  two-sibling-repo posture exists; admin VM imports admin repo only;
 *  user-side VM imports user repo only; no cross-pollination. Legacy
 *  `:shared/DeleteComplaintUseCase` single-wire backing verified — both
 *  admin and user paths funnel through the same legacy delete operation
 *  on the strangler-fig data layer.
 *  (c) "Caller obligation — the `:ui` dialog enforces a confirmation step
 *  before dispatching the delete intent; the VM short-circuits via
 *  isSubmittingAction; this use case does NOT pre-validate" — LIVE-NOT-
 *  STALE. AdminComplaintIntent.kt L200 KDoc reference to AdminDelete-
 *  ComplaintUseCase confirms the dispatch site; AdminComplaintViewModel.
 *  kt isSubmittingAction in-flight gate verified at cluster #461 sibling
 *  sweep (complaintvm.staleKdocSweep.cascade). UI-dialog-not-domain-gate
 *  posture is upheld by the no-validate single-line repository delegate.
 *  (d) §6 SRP + §6 DIP + class name disambiguation + Koin factory
 *  lifecycle — LIVE-NOT-STALE. ComplaintAdminReworkModule.kt L151
 *  `factory { AdminDeleteComplaintUseCase(get()) }` realization; L3
 *  import binds `:domain`-layer interface, not `:data`-layer impl. The
 *  `Admin` prefix disambiguation is upheld at the AdminComplaintView-
 *  Model.kt L9 import site — the import line explicitly names the prefix
 *  variant, ruling out any accidental user-side DeleteComplaintUseCase
 *  shadowing.
 *  Four classifications STAND on their own merits. Original Phase
 *  7.x.complaint.admin.actions-era prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
class AdminDeleteComplaintUseCase(
    private val repository: AdminComplaintActionRepository,
) {
    suspend operator fun invoke(id: String): Result<Unit> =
        repository.deleteComplaint(id)
}
