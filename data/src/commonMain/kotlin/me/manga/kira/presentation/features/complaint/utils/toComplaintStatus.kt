package me.manga.kira.presentation.features.complaint.utils

import me.manga.kira.presentation.features.complaint.model.ComplaintStatus

fun String.toComplaintStatus(): ComplaintStatus =
    try {
        ComplaintStatus.valueOf(this)
    } catch (e: IllegalArgumentException) {
        ComplaintStatus.UNKNOWN
    }

/*
 * Audit-trail postscript (Phase 9.x.cluster201.staleKdocSweep.cascade, Task #657, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster201 leaf 4/4 — :shared/complaint/utils/ tier closer, sibling 360. With this leaf,
 * the legacy :shared/.../complaint/ subdirectory non-repository tier (model/ trio + utils/
 * singleton, 4 files total) is FULLY SWEPT. The complaint/ subdir close-out continues —
 * complaint/repository/ pair (2 files: ComplaintRepository interface +
 * ComplaintFirestoreRestDataSource) is the cluster202 target.
 *
 * File-shape note: 11-line top-level extension function `String.toComplaintStatus(): ComplaintStatus`.
 * Reverse-map from Firestore-stored status string to the enum, with try/catch IllegalArgumentException
 * fallback to ComplaintStatus.UNKNOWN. No KDoc header.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — narrow two-consumer fan: ComplaintFirestoreRestDataSource.kt (commonMain
 *     — REST API path used on iOS + Desktop + Android-without-Firebase-SDK) + ComplaintFirestoreDataSource.kt
 *     (androidMain — Firebase SDK path). Both data sources call `.toComplaintStatus()` when
 *     deserializing Firestore document fields' "status" key back into the typed enum.
 *
 *   • DEFENSIVE-FALLBACK-NOT-STALE — the try/catch is NOT defensive over-engineering. It is the
 *     LOAD-BEARING forward-compat hook: server-emitted statuses can include strings the current
 *     client binary does not declare (e.g., a future "ARCHIVED" or "DUPLICATE" variant introduced
 *     server-side ahead of a client release). Falling to ComplaintStatus.UNKNOWN keeps the
 *     deserialization path from crashing the admin/user list screens. This pairs with sibling
 *     359 (ComplaintStatus)'s deliberate UNKNOWN variant. DO NOT collapse the try/catch into a
 *     `ComplaintStatus.valueOf(this)` direct call during error-handling cleanup passes — that
 *     would re-introduce a regression already-defended-against.
 *
 *   • INVERTED-PARALLEL — no same-named rework counterpart at `:domain/util/` or `:data/mapper/`.
 *     The rework data layer's :data mapper goes through `enumValueOf<DomainComplaintStatus>(legacy
 *     .name)` directly per the :domain ComplaintSummary KDoc (verified at L141-142: "mapper in
 *     ComplaintListRepositoryImpl is a pure `enumValueOf<DomainComplaintType>(legacy.name)`-style
 *     mapping"). The :data path does NOT route through this legacy utility — the strangler-fig
 *     pattern terminates at the Complaint model boundary; the Firestore deserialization is :shared-
 *     internal. The rework reverse-map collapses the legacy two-hop (Firestore-string →
 *     legacy enum → :domain enum) into a one-hop (legacy enum → :domain enum) via the
 *     enumValueOf mapper, leveraging the PARALLEL-CLASS-CLONE-NOT-DRIFT identity guarantee on
 *     ComplaintStatus.
 *
 *   • DOC-LACUNA — no KDoc header. Same shape-stripped state as cluster200 siblings 352 + 353 +
 *     355 + 356. Preserved per §253 — adding a synthetic header would falsify the audit trail.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 1 import: legacy `ComplaintStatus` enum (sibling 359).
 *
 * Cross-cluster :shared/complaint/ subdirectory tier-close pattern register (cluster201 closer):
 *
 *   • Two-cluster sweep — cluster200 covered usecase/ (5 leaves, siblings 352-356); cluster201
 *     covers model/ + utils/ (4 leaves, siblings 357-360). Combined coverage: 9 of 11 files
 *     in :shared/complaint/ swept. Remaining 2 files (repository/ pair) deferred to cluster202.
 *
 *   • Naming-axis patterns across the subdir:
 *       - Data carriers: legacy `Complaint` data class + Firestore-Map metadata
 *         → rework `ComplaintSummary` (renamed + reshaped, Map dropped, single-typed keys
 *         carved out one-by-one starting with appVersion).
 *       - Enums: legacy `ComplaintType` + `ComplaintStatus` → rework counterparts
 *         (clone-not-drift, 1:1 identity + order required for enumValueOf mapping safety).
 *       - Extension utilities: legacy `String.toComplaintStatus()` → rework collapses to
 *         direct enumValueOf in the mapper (INVERTED-PARALLEL — no same-named successor).
 *
 *   • Migration-prose coverage across the model trio (siblings 357-358-359):
 *       - All 3 model files carry surviving Phase 4 batch 4.4 migration notes (java.util.Date
 *         → kotlin.time.Instant?, Android Context method removal). The doc-tidy pass that
 *         stripped 4-of-5 usecase headers in cluster200 did NOT touch the model trio — all 3
 *         retain their headers. Selectivity pattern matches sibling 354 (GetUserComplaintUseCase
 *         — only usecase to retain header).
 *
 *   • Strangler-fig boundary line: the Complaint model itself is the strangler-fig boundary.
 *     :data impls consume legacy Complaint and translate (via :data/mapper) to :domain
 *     ComplaintSummary. The legacy enum + utility helpers serve the legacy-side of the
 *     boundary (Firestore deserialization); the rework :domain side never reaches into this
 *     subdir for utility functions — it only references the model contract by name through
 *     the :data layer.
 *
 *   • Doc-lacuna ratio: 3-of-4 retain prose (3 model files); 1-of-4 stripped (utils/).
 *     Opposite skew from cluster200's 1-of-5 retention.
 *
 *   • Wave-61 second-leaf cohort (cluster201) maintains the clean LIVE-NOT-STALE posture —
 *     no orphans, no drifted prose, no dead code. Legacy model + utils are pure
 *     architectural-strangler-fig sources, deliberately preserved.
 */
