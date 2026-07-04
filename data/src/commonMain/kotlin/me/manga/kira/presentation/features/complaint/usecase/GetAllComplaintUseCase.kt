package me.manga.kira.presentation.features.complaint.usecase

import me.manga.kira.presentation.features.complaint.model.Complaint
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository

class GetAllComplaintUseCase(
    private val repo: ComplaintRepository,
) {
    suspend operator fun invoke(): List<Complaint> = repo.getAllComplaints()
}

/*
 * Audit-trail postscript (Phase 9.x.cluster200.staleKdocSweep.cascade, Task #656, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster200 leaf 2/5 — :shared/complaint/usecase/ tier midbody, sibling 353.
 *
 * File-shape note: 10-line zero-arg use-case class. `invoke()` returns `List<Complaint>` from
 * legacy `ComplaintRepository.getAllComplaints()`. Admin-facing fetch — no user-id filter.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — strangler-fig SOURCE for both admin-side flows. Injected as
 *     `LegacyGetAllComplaintUseCase` at:
 *       - data/.../AdminComplaintActionRepositoryImpl.kt L12
 *       - data/.../AdminComplaintListRepositoryImpl.kt L10
 *     The pair-use posture (action repo + list repo both consume the same Get-All source)
 *     mirrors the ISP-clean-split documented at the rework `:domain` cluster123 sibling 69th
 *     (AdminDeleteComplaintUseCase) postscript — two-sibling-repo split, single legacy fetch.
 *     Koin binding in `:shared/di/SharedModule.kt` L13.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 2 imports: legacy `Complaint` model (sibling
 *     :shared/.../model/Complaint.kt) and legacy `ComplaintRepository` interface.
 *
 *   • PARALLEL-CLASS-DRIFT — rework counterpart is `ObserveAllComplaintsUseCase` at
 *     `domain/.../usecase/complaint/ObserveAllComplaintsUseCase.kt` (cluster123 sibling 73rd,
 *     Task #579). Names differ deliberately: legacy is `suspend List<Complaint>` (one-shot
 *     fetch), rework is `Flow<List<Complaint>>` (reactive observation). The rework :data
 *     impl `AdminComplaintListRepositoryImpl` wraps the legacy one-shot fetch into a
 *     reactive flow via the cell-of-truth pattern documented at cluster153 sibling sweep
 *     (:data/repository complaint trio). Strangler-fig transform — fetch becomes observe.
 *
 *   • DOC-LACUNA — no KDoc header. Same shape-stripped state as sibling 352.
 *
 *   • FULFILLED-PORT (vacuously) — Phase 4 @Inject removal axis applies (no @Inject remains;
 *     Koin manual-wires the dependency).
 */
