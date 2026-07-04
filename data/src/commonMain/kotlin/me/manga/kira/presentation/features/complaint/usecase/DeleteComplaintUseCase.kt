package me.manga.kira.presentation.features.complaint.usecase

import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository

class DeleteComplaintUseCase(
    private val repo: ComplaintRepository,
) {
    suspend operator fun invoke(complaintId: String) = repo.deleteComplaint(complaintId)
}

/*
 * Audit-trail postscript (Phase 9.x.cluster200.staleKdocSweep.cascade, Task #656, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster200 leaf 1/5 — :shared/complaint/usecase/ tier opener, sibling 352. Wave-61 opens
 * AFTER the 2026-05-29 user-directive redirect away from sources_repositry/ (which is being
 * architecturally reworked — any §253 sweep landed there would be discarded; see
 * feedback_no_sources_repositry_sweep.md). Cumulative §253-postscript count = 77 leaves.
 *
 * File-shape note: 9-line thin pass-through use-case class. Single-arg `invoke(complaintId)`
 * delegates straight to `ComplaintRepository.deleteComplaint(complaintId)`. No KDoc header,
 * no migration-note prose — file is pure-shape.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — strangler-fig SOURCE. The rework :data impls inject this class via
 *     `as LegacyDeleteComplaintUseCase` import alias at:
 *       - data/.../ComplaintActionRepositoryImpl.kt L10
 *       - data/.../AdminComplaintActionRepositoryImpl.kt L11
 *     Both impls wrap the call inside their own `Result<Unit>` envelope to match the rework
 *     :domain `ComplaintActionRepository.deleteComplaint(id): Result<Unit>` + corresponding
 *     `AdminComplaintActionRepository.deleteComplaint(id): Result<Unit>` contract shape.
 *     Koin binding lives in `:shared/di/SharedModule.kt` L12 (legacy module side).
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — single import: legacy `ComplaintRepository` interface
 *     at `me.manga.kira.presentation.features.complaint.repository.ComplaintRepository`
 *     (verified at sibling :shared/.../repository/ComplaintRepository.kt — 5-method legacy
 *     contract: send/getAll/getByUser/update/delete).
 *
 *   • PARALLEL-CLASS-CLONE-NOT-DRIFT — same-named rework sibling at
 *     `domain/.../usecase/complaint/DeleteComplaintUseCase.kt` exists and was swept by
 *     cluster124 (sibling 74th, Task #580). The two classes share the name but live in
 *     different packages (legacy `:shared/presentation/features/complaint/usecase` vs rework
 *     `:domain/usecase/complaint`) and have different contract signatures: legacy returns
 *     `Unit`, rework returns `Result<Unit>`. The rework's own KDoc explicitly documents the
 *     name-disambiguation via the `as LegacyDeleteComplaintUseCase` import-alias convention
 *     used in the :data impl. NOT a duplication-drift bug — intentional strangler-fig posture.
 *
 *   • DOC-LACUNA — file has no KDoc header at all. Unlike sibling 354 (GetUserComplaintUseCase)
 *     which carries a "Phase 4 batch 4.4: @Inject removed" migration note, this file's header
 *     was either never written or stripped during the @Inject removal pass. Preserved per §253
 *     — adding a synthetic header now would falsify the audit trail.
 *
 *   • FULFILLED-PORT (vacuously) — the @Inject ctor-injection removal axis applies (no @Inject
 *     remains). The constructor relies on Koin manual-wiring per SharedModule.kt L12 `factory {
 *     DeleteComplaintUseCase(get()) }` style binding (KMP-port version of the original Hilt
 *     `@Inject constructor` annotation).
 */
