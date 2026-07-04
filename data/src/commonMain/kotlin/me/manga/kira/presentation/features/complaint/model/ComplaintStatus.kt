package me.manga.kira.presentation.features.complaint.model

// Migration note (Phase 4 batch 4.4): source had an Android-bound getDisplayName(Context) method
// per case. Moved out to Phase 10 (Compose Multiplatform Resources). Enum constants preserved
// in identity and order.
enum class ComplaintStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED,
    PLANNED,
    PINNED,
    UNKNOWN,
    NOT_PLANNED,
}

/*
 * Audit-trail postscript (Phase 9.x.cluster201.staleKdocSweep.cascade, Task #657, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster201 leaf 3/4 — :shared/complaint/model/ tier midbody, sibling 359.
 *
 * File-shape note: 16-line enum class — 8 lifecycle variants (OPEN, IN_PROGRESS, RESOLVED,
 * CLOSED, PLANNED, PINNED, UNKNOWN, NOT_PLANNED). Carries the same Phase 4 batch 4.4 migration
 * prose as sibling 358 (ComplaintType) — Android Context method removal pattern.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — transitively consumed via Complaint.kt's `status: ComplaintStatus` field
 *     (with `OPEN` as default). Direct independent reference: sibling utils/toComplaintStatus.kt
 *     (cluster201 sibling 360) — the String → enum reverse map with UNKNOWN fallback.
 *
 *   • FULFILLED-PORT — same shape as sibling 358: `getDisplayName(Context)` removed, enum body
 *     is bare 8 variants. Per §253 — preserved.
 *
 *   • FORECAST-NOT-YET-FULFILLED — same Phase 10 deferral as sibling 358. No
 *     `Res.string.complaint_status_*` keys exist in :ui yet. ComplaintDisplayNames.kt in :ui
 *     carries the in-tree `when` branches with English strings. The per-status colored chip
 *     parity (cluster273 §273) carries colors but not localized status names.
 *
 *   • PARALLEL-CLASS-CLONE-NOT-DRIFT — rework counterpart at `:domain/model/complaint/`
 *     (inline in ComplaintSummary.kt L168-177) declares EXACTLY THE SAME 8 variants in
 *     EXACTLY THE SAME ORDER. The :data mapper relies on `enumValueOf<DomainComplaintStatus>(
 *     legacy.name)` — identity + order match REQUIRED. Includes the deliberate UNKNOWN
 *     fallback variant for forward-compat handling (server may emit new statuses ahead of
 *     client knowing them — cluster201 sibling 360 handles this in the reverse-map utility).
 *
 *   • DEFENSIVE-INVARIANT-LIVE — the UNKNOWN variant is a DELIBERATE escape hatch, not a
 *     placeholder waiting for cleanup. The reverse-map at utils/toComplaintStatus.kt catches
 *     IllegalArgumentException on unknown status strings and returns UNKNOWN — preserving
 *     forward-compat against server-emitted statuses that pre-date the current client. The
 *     rework :domain enum preserves UNKNOWN for the same reason. DO NOT remove during enum
 *     value cleanup passes.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — zero imports. Pure bare-Kotlin enum.
 */
