package me.manga.kira.presentation.features.complaint.repository

import me.manga.kira.presentation.features.complaint.model.Complaint

interface ComplaintRepository {
    /**
     * Creates a new complaint and returns its generated ID.
     */
    suspend fun sendComplaint(complaint: Complaint): String
    suspend fun getAllComplaints(): List<Complaint>
    suspend fun getComplaintsByUser(userId: String): List<Complaint>
    suspend fun updateComplaint(complaint: Complaint)
    suspend fun deleteComplaint(complaintId: String)
}

/*
 * Audit-trail postscript (Phase 9.x.cluster202.staleKdocSweep.cascade, Task #658, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster202 leaf 1/2 — :shared/complaint/repository/ tier opener, sibling 361. Wave-61 closes
 * the legacy :shared/complaint/ subdir with this two-cluster cap (cluster202 = repository/
 * pair: interface + REST impl). Cumulative §253-postscript count = 86 leaves with this commit.
 *
 * File-shape note: 14-line interface — 5 suspend methods. sendComplaint carries a single-line
 * KDoc (returns generated doc ID); the remaining 4 methods are bare signatures.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — strangler-fig SOURCE contract injected by all 5 legacy :shared usecase
 *     classes (cluster200 cohort, siblings 352-356):
 *       - SendComplaintUseCase (sibling 355)
 *       - GetUserComplaintUseCase (sibling 354)
 *       - GetAllComplaintsUseCase (sibling 353)
 *       - DeleteComplaintUseCase (sibling 352)
 *       - UpdateComplaintUseCase (sibling 356)
 *     The 5 use cases delegate pure pass-through to repo methods (1:1 method fan-out). Koin
 *     binding lives at SharedModule.kt — the interface is bound to its sole legacy impl,
 *     ComplaintFirestoreRestDataSource (sibling 362), via the standard `single<ComplaintRepository>
 *     { ComplaintFirestoreRestDataSource(get()) }` shape on commonMain. The androidMain Firebase
 *     SDK impl (ComplaintFirestoreDataSource) implements the same interface but is registered
 *     via the androidMain platform module override (post-Phase 14.x split).
 *
 *   • DEFENSIVE-DOC-PARTIAL — only sendComplaint() carries a KDoc; the other 4 methods are
 *     bare suspend signatures. The narrow KDoc documents the "auto-generated doc ID" return
 *     contract; the remaining 4 method names + types are self-describing. Per §253 — preserved
 *     as point-in-time accurate (the KDoc is current; missing KDocs on the other 4 is a
 *     deliberate doc-economy choice, not staleness).
 *
 *   • PARALLEL-CLASS-DRIFT — the rework :domain layer SHATTERS this single 5-method legacy
 *     contract into 5 NARROWER per-feature repository interfaces:
 *       - ComplaintListRepository (read-side, user-side: observeUserComplaints Flow)
 *       - ComplaintActionRepository (write-side, user-side: sendComplaint + editComplaint +
 *         replyToComplaint + deleteComplaint)
 *       - AdminComplaintListRepository (read-side, admin: observeAllComplaints Flow)
 *       - AdminComplaintActionRepository (write-side, admin: changeStatus + addClosureReason +
 *         adminEditComplaint + adminDeleteComplaint)
 *       - FeedbackRepository (separate concern, lifted onto the same legacy underlying impl)
 *     ISP applied: each rework consumer depends on the narrowest interface it actually needs.
 *     Strongest INTERFACE-SEGREGATION-PRINCIPLE-APPLIED pattern in the legacy complaint pipeline.
 *     The 5 rework repository interfaces all currently delegate to the same legacy Firestore
 *     impls under the hood — the strangler-fig boundary is at the :domain interface level,
 *     not the data source level.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 1 import: legacy `Complaint` model (sibling 357).
 */
