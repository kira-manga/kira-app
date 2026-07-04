package me.manga.kira.presentation.features.complaint.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Migration note (Phase 4 batch 4.4): java.util.Date -> kotlin.time.Instant? (KMP).
// kotlin.time.Instant is the KMP-portable timestamp type. Same observable value (epoch nanos
// instead of millis but interchangeable). ComplaintFirestoreDataSource maps Firestore's
// server-provided Timestamp to Instant on read and back on write (Android-only impl, Phase 11).
//
// ComplaintStatus and ComplaintType still live in source — they carry Android-only @StringRes /
// R.string lookups for display names and are deferred to Phase 10 (compose-resources). For now
// this commonMain Complaint references only the names — wherever the source uses ComplaintStatus
// it will be imported from the source package until the enums move.
@OptIn(ExperimentalTime::class)
data class Complaint(
    val id: String = "",
    val userId: String,
    val type: ComplaintType,
    val subject: String,
    val body: String,
    val createdAt: Instant? = null,
    val status: ComplaintStatus = ComplaintStatus.OPEN,
    val metadata: Map<String, Any>? = null,
)

/*
 * Audit-trail postscript (Phase 9.x.cluster201.staleKdocSweep.cascade, Task #657, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster201 leaf 1/4 — :shared/complaint/model/ + utils/ tier opener, sibling 357. Wave-61
 * continues the legacy complaint/ subdir close-out begun in cluster200 (which closed the
 * usecase/ tier). Cumulative §253-postscript count = 82 leaves.
 *
 * File-shape note: 26-line @OptIn(ExperimentalTime::class) data class with 8 fields including
 * `metadata: Map<String, Any>?` and surviving Phase 4 batch 4.4 migration prose. The Complaint
 * model is the keystone legacy type — referenced by ALL 5 legacy use cases (cluster200 siblings
 * 352-356), all 4 :data repository impls, and both Firestore data source impls (commonMain
 * REST + androidMain Firebase SDK).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — keystone legacy type, consumed by 15 importers spanning:
 *       - 5 :shared usecase classes (cluster200 cohort)
 *       - 4 :data repository impls (ComplaintActionRepositoryImpl + AdminComplaintActionRepositoryImpl
 *         + ComplaintListRepositoryImpl + AdminComplaintListRepositoryImpl + FeedbackRepositoryImpl)
 *       - 2 Firestore data source impls (commonMain REST + androidMain Firebase SDK)
 *       - 1 :domain rework counterpart KDoc reference (ComplaintSummary)
 *       - 1 :shared repository interface (ComplaintRepository, sibling)
 *     Sole carrier of `metadata: Map<String, Any>?` in the complaint pipeline.
 *
 *   • FULFILLED-PORT — the inline Phase 4 batch 4.4 migration note documenting
 *     `java.util.Date -> kotlin.time.Instant?` IS factually accurate AND fully landed.
 *     Verified: import statement reads `kotlin.time.Instant`; `@OptIn(ExperimentalTime::class)`
 *     class-level annotation is in place; the createdAt field carries `Instant?`. Per §253 —
 *     point-in-time accurate at landing AND still factually current. Preserved.
 *
 *   • FORECAST-NOT-YET-FULFILLED — the inline KDoc cites Phase 10 (compose-resources) deferral
 *     for moving the Android-only @StringRes / R.string display-name lookups for ComplaintStatus
 *     + ComplaintType out of :shared. Verified via grep: no `Res.string.complaint_type_*` keys
 *     exist in :ui yet. The displayName() helpers in :ui ComplaintDisplayNames.kt currently use
 *     in-tree `when`-branch English strings (per cluster267 §267 placement decision — helpers
 *     landed in :ui, not :presentation, per layer-hygiene). Phase 10 lift remains pending.
 *
 *   • POTENTIAL-BUG-NOT-PRESERVED-IN-REWORK — the `metadata: Map<String, Any>?` field carries
 *     `Any` (the contract §6 banned type for the rework `:domain` boundary). NOT a bug here
 *     because this file lives in `:shared`, which the contract does NOT gate. The rework
 *     `:domain` counterpart (ComplaintSummary at domain/.../complaint/ComplaintSummary.kt) drops
 *     the field entirely and carves out individual typed keys as needed — the first carve-out
 *     is `appVersion: String?` (Phase 7.x.complaint.admin.versionfilter, Task #264). Future
 *     carve-outs (`platform`, `build`, etc.) extend ComplaintSummary with new single-typed
 *     nullable fields — same posture, never widening `Any` into :domain.
 *
 *   • PARALLEL-CLASS-DRIFT — rework counterpart is RENAMED + RESHAPED:
 *       legacy `Complaint` (8 fields, has metadata Map)
 *       → rework `ComplaintSummary` (8 fields, NO metadata, ADDS appVersion)
 *     Mapping at ComplaintListRepositoryImpl.kt is legacy → rework via field-by-field copy +
 *     metadata["appVersion"] extraction. Different parallel posture from sibling enums 358 +
 *     359 (which are PARALLEL-CLASS-CLONE-NOT-DRIFT — 1:1 identity + order).
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 2 imports: `kotlin.time.Instant` + `kotlin.time.ExperimentalTime`
 *     (KMP-portable time, no platform reach). Sibling references: ComplaintType + ComplaintStatus
 *     (same package).
 */
