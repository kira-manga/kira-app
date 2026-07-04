package me.manga.kira.presentation.features.complaint.usecase

import me.manga.kira.presentation.features.complaint.model.Complaint
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository

class UpdateComplaintUseCase(
    private val repo: ComplaintRepository,
) {
    suspend operator fun invoke(complaint: Complaint) = repo.updateComplaint(complaint)
}

/*
 * Audit-trail postscript (Phase 9.x.cluster200.staleKdocSweep.cascade, Task #656, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster200 leaf 5/5 — :shared/complaint/usecase/ tier closer, sibling 356. With this leaf,
 * the legacy :shared/.../complaint/usecase/ subdirectory is FULLY SWEPT (5 of 5 files).
 *
 * File-shape note: 9-line full-record-update use-case. `invoke(complaint): Unit` delegates to
 * legacy `ComplaintRepository.updateComplaint(complaint)`. Bulk-mutation counterpart to the
 * narrower :data-layer rework mutations (admin status change, closure reason, reply, edit
 * — each of which uses a NARROWER input shape than a full-Complaint replace).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — strangler-fig SOURCE for two admin mutations + user-side edit.
 *     Injected as `LegacyUpdateComplaintUseCase` at:
 *       - data/.../ComplaintActionRepositoryImpl.kt L12 (user-side edit flow)
 *       - data/.../AdminComplaintActionRepositoryImpl.kt L13 (admin status change + edit)
 *     Pair-use admin/user split mirrors siblings 352 (Delete) and 353 (GetAll) posture.
 *     Koin binding in SharedModule.kt L16.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 2 imports: legacy `Complaint` model + legacy
 *     `ComplaintRepository` interface.
 *
 *   • PARALLEL-CLASS-DRIFT — rework counterparts at :domain/.../usecase/complaint/ split the
 *     legacy full-record update into 3 narrower use cases:
 *       - `EditComplaintUseCase` (user-side, subject + body only — cluster124 sibling 75th)
 *       - `AdminEditComplaintUseCase` (admin, all editable fields — cluster123 sibling 70th)
 *       - `ChangeComplaintStatusUseCase` (status-only narrow mutation — cluster123 sibling 71st)
 *       - `AddClosureReasonUseCase` (closure-reason narrow mutation — cluster123 sibling 72nd)
 *     Per-field ISP-split — narrower than the legacy single full-record update. The legacy
 *     UpdateComplaintUseCase remains as the underlying mutation channel; the 4 rework use
 *     cases construct progressively-narrower input wrappers that all funnel through this same
 *     legacy strangler-fig source. Strongest INPUT-SHAPE-NARROWING pattern in the cluster200
 *     batch.
 *
 *   • DOC-LACUNA — no KDoc header (same shape-stripped state as siblings 352 + 353 + 355).
 *
 *   • FULFILLED-PORT (vacuously) — Phase 4 @Inject removal axis applies.
 *
 * Cross-cluster :shared/complaint/usecase/ tier-close pattern register (cluster200 closer):
 *
 *   • Five strangler-fig SOURCES all wrapped by the :data impls via the `as Legacy*UseCase`
 *     import-alias convention. The 5-file legacy cohort drives 5 :data/repository impls + 1
 *     legacy Koin module (SharedModule.kt L12-16).
 *
 *   • Doc-lacuna ratio: 4-of-5 stripped (only sibling 354 retains the original Phase 4
 *     migration note). The stripping pattern suggests a doc-tidy pass that touched 4 of 5
 *     files but missed the GetUser one — a single-developer signature.
 *
 *   • One file carries non-trivial body (sibling 355 — Send, with `require(...)` validation
 *     gates). Other 4 are pure pass-through.
 *
 *   • Naming-axis split between legacy and rework: legacy `Get*` (suspend) → rework `Observe*`
 *     (Flow), legacy `Update*` (full-record) → rework `Edit*` + `Change*` + `Add*` (per-field
 *     ISP-split). Two read-side renames + one write-side ISP-fan; Delete / Send keep verb
 *     names across the rework boundary.
 *
 *   • Wave-61 opens with a clean LIVE-NOT-STALE cohort (no orphans, no drifted prose, no
 *     dead code) — a sharp contrast to the cluster192-199 :ar/:en/:es/ Repository tier sweeps
 *     which mixed live impls with stale Phase 7.9 migration prose. Legacy usecases here are
 *     pure architectural-strangler-fig sources, deliberately preserved.
 */
