package me.manga.kira.presentation.features.complaint.usecase

import me.manga.kira.presentation.features.complaint.model.Complaint
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository

// Migration note (Phase 4 batch 4.4): @Inject removed — Koin will wire the dependency via
// factory { GetUserComplaintUseCase(get()) } in Phase 5.
class GetUserComplaintUseCase(
    private val repo: ComplaintRepository,
) {
    suspend operator fun invoke(userId: String): List<Complaint> =
        repo.getComplaintsByUser(userId)
}

/*
 * Audit-trail postscript (Phase 9.x.cluster200.staleKdocSweep.cascade, Task #656, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster200 leaf 3/5 — :shared/complaint/usecase/ tier midbody, sibling 354. ONLY file in
 * the 5-file cohort with a surviving KDoc header — the rest are doc-lacuna (siblings 352, 353,
 * 355, 356 all stripped during the Phase 4 @Inject removal pass).
 *
 * File-shape note: 13-line user-id-filtered fetch. `invoke(userId): List<Complaint>` delegates
 * to legacy `ComplaintRepository.getComplaintsByUser(userId)`. User-facing fetch counterpart
 * to sibling 353's admin-facing all-fetch.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — strangler-fig SOURCE for user-side complaint list. Injected as
 *     `LegacyGetUserComplaintUseCase` at:
 *       - data/.../ComplaintListRepositoryImpl.kt L11
 *     Single consumer (not pair-used like sibling 353) — only the user-side list repo wraps
 *     this. The rework `ObserveUserComplaintsUseCase` at `:domain/.../usecase/complaint/`
 *     (cluster124 sibling 76th) drains through this strangler-fig source for the
 *     `Flow<List<Complaint>>` reactive transform. Koin binding in SharedModule.kt L14.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 2 imports: legacy `Complaint` model + legacy
 *     `ComplaintRepository` interface.
 *
 *   • FULFILLED-PORT — Phase 4 @Inject removal IS the documented migration axis here.
 *     LIVE-NOT-STALE per cluster57+ taxonomy: the migration note prose accurately describes
 *     the @Inject-to-Koin-factory transformation, and the file-level state (no @Inject
 *     annotations remain; SharedModule.kt L14 `factory { GetUserComplaintUseCase(get()) }`
 *     binding) confirms Phase 5 fulfilled the promise. Preserved per §253 — historical
 *     migration prose, point-in-time accurate at landing AND still factually current.
 *
 *   • PARALLEL-CLASS-DRIFT — rework counterpart `ObserveUserComplaintsUseCase` (Flow-shape,
 *     reactive) lives in :domain alongside the same-named-different-package rework
 *     `DeleteComplaintUseCase` / `EditComplaintUseCase` / `ReplyToComplaintUseCase` cohort.
 *     Naming convention split: legacy `Get*` (suspend one-shot) vs rework `Observe*`
 *     (Flow reactive) — applies to siblings 353 + 354 only; 352 + 355 + 356 keep their
 *     verb-form names in the rework (Delete + Send + Update remain action verbs since they
 *     are mutations, not reads).
 */
