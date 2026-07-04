package me.manga.kira.domain.model.complaint

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * `:domain`-side projection of a user's submitted feedback/complaint record — the LIST item the
 * rework Feedback Manager screen renders.
 *
 * Phase 7.x.complaint.foundation rework: mirror of the legacy
 * `me.manga.kira.presentation.features.complaint.model.Complaint` data class, kept in
 * `:domain` to avoid a `:domain` -> `:shared` layer-hygiene violation. Mapping (legacy
 * `Complaint` -> [ComplaintSummary]) happens in
 * [me.manga.kira.data.repository.ComplaintListRepositoryImpl] — same posture as Phase 7.x.statistics'
 * [me.manga.kira.domain.model.statistics.ReadingStatistics] (separate from any legacy shape).
 *
 * Field parity with legacy `Complaint`:
 *  - [id]: Firestore-assigned document id (empty pre-write; populated on read).
 *  - [userId]: platform-stable identifier (Android: ANDROID_ID; iOS: identifierForVendor;
 *    Desktop: per-install UUID — see legacy [me.manga.kira.domain.auth.UserIdProvider]).
 *  - [type]: complaint category — see [ComplaintType].
 *  - [subject]: short summary line (Firestore-stored).
 *  - [body]: free-form user-typed description.
 *  - [createdAt]: server-written timestamp; nullable because pre-write records have no
 *    timestamp. `kotlin.time.Instant` per Phase 4 batch 4.4 migration (was `java.util.Date`).
 *  - [status]: current state — see [ComplaintStatus].
 *  - [appVersion]: client app version that produced the submission (e.g., `"1.2.3"`). Nullable
 *    — pre-existing complaints submitted before app-version metadata was emitted, plus
 *    test/fixture records, will carry `null`. Phase 7.x.complaint.admin.versionfilter rework:
 *    carved out of the legacy `Complaint.metadata: Map<String, Any>?` map's `"appVersion"`
 *    key as a single non-`Any` `String?` field — the carve-out keeps the `:domain` boundary
 *    clear of `Any` (contract §6) while still surfacing the one metadata field the admin
 *    dashboard needs for filter / sort / stats. Other legacy `metadata` keys (`platform`,
 *    `build`, etc.) stay in `:shared` until / unless future slices need them; each future
 *    carve-out follows the same single-typed-field posture.
 *
 * Differences vs legacy:
 *  - No `metadata: Map<String, Any>?` field. The legacy carries platform/version metadata for
 *    admin diagnostics; the rework carves specific fields out one-at-a-time as needed
 *    ([appVersion] is the first). Excluding `Map<String, Any>` keeps the `:domain` boundary
 *    free of banned `Any` per contract §6. If a future admin slice needs additional keys
 *    (e.g., `platform`, `build`), they extend this data class with new single-typed
 *    nullable fields — same carve-out posture.
 *
 * Contract §6 SRP: one rule — "a user's feedback record as a value". No methods, no
 * derivation; transformation lives in [me.manga.kira.data.repository.ComplaintListRepositoryImpl].
 *
 * Contract §6 DIP: `:domain` defines its own value type; consumers (`:presentation`/`:ui`)
 * depend on this, not on the legacy `Complaint`. Layer hygiene preserved.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster137.staleKdocSweep.cascade,
 * Task #593, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-twenty-sixth sibling of the cluster57-136
 * sweep — second file of the wave-24 fifth-cluster 5-subpackage joint
 * batch alongside AppMetadata plus HistoryEntry plus Language plus
 * SettingsSnapshot; postscript covers all three types in this file —
 * ComplaintSummary primary data class plus ComplaintType enum plus
 * ComplaintStatus enum):
 *  (a) "Phase-7.x.complaint.foundation-rework + mirror-of-the-legacy-
 *  presentation-features-complaint-model-Complaint-data-class + kept-
 *  in-:domain-to-avoid-a-:domain-:shared-layer-hygiene-violation +
 *  Mapping-legacy-Complaint-to-ComplaintSummary-happens-in-Complaint-
 *  ListRepositoryImpl + Field-parity-id-Firestore-document-id + userId-
 *  platform-stable-identifier-Android-ANDROID_ID-iOS-identifierFor-
 *  Vendor-Desktop-per-install-UUID + type-complaint-category + subject-
 *  short-summary-line + body-free-form-user-typed-description + create-
 *  dAt-server-written-timestamp-kotlin.time.Instant-per-Phase-4-batch-
 *  4.4-migration-was-java.util.Date + status-current-state + appVersion-
 *  client-app-version-that-produced-the-submission" — LIVE-NOT-STALE
 *  plus FULFILLED-PREDICTION. Verified via recursive grep: Complaint-
 *  Summary is consumed by 20+ files — ComplaintListRepositoryImpl plus
 *  AdminComplaintListRepositoryImpl plus 10+ admin/user use cases plus
 *  ComplaintState plus AdminComplaintState plus ComplaintScreen plus
 *  AdminComplaintScreen. Field declarations match the predicted shape
 *  byte-identical (id + userId + type + subject + body + createdAt
 *  Instant? + status + appVersion String?). The Phase 4 batch 4.4
 *  Instant migration (away from java.util.Date) is locked in via the
 *  @OptIn(ExperimentalTime::class) annotation on the data class.
 *  (b) "appVersion-Phase-7.x.complaint.admin.versionfilter-rework +
 *  carved-out-of-the-legacy-Complaint.metadata-Map-String-Any-map-
 *  appVersion-key-as-a-single-non-Any-String-field + the-carve-out-
 *  keeps-the-:domain-boundary-clear-of-Any-contract-§6 + Differences-vs-
 *  legacy-No-metadata-Map-String-Any-field + legacy-carries-platform-
 *  version-metadata-for-admin-diagnostics + rework-carves-specific-
 *  fields-out-one-at-a-time-as-needed-appVersion-is-the-first +
 *  Excluding-Map-String-Any-keeps-the-:domain-boundary-free-of-banned-
 *  Any-per-contract-§6 + future-admin-slice-needs-additional-keys-
 *  platform-build-they-extend-this-data-class-with-new-single-typed-
 *  nullable-fields + same-carve-out-posture" — LIVE-NOT-STALE plus
 *  FULFILLED-PREDICTION plus FORECAST-NOT-YET-FULFILLED-(platform-or-
 *  build-or-other-metadata-key-carve-outs). Verified: ComplaintSummary
 *  carries appVersion: String? = null exactly as predicted. The
 *  Phase 7.x.complaint.admin.versionfilter slice (Task #264 COMPLETE)
 *  landed the third filter axis + 2 deferred sort modes (semver +
 *  semverDesc) per #266 chip-row semver-sort follow-up. No additional
 *  metadata-key carve-outs have landed; the data class is ready to
 *  absorb them as new single-typed-nullable-fields without breaking
 *  consumers.
 *  (c) "ComplaintType-mirrors-the-legacy-ComplaintType-1-1 + Legacy-
 *  values-preserved-in-identity-and-order + so-the-mapper-in-Complaint-
 *  ListRepositoryImpl-is-a-pure-enumValueOf-DomainComplaintType-legacy.
 *  name-style-mapping-with-no-risk-of-misalignment + Display-name-
 *  resolution-stays-deferred-to-Phase-10-Compose-Multiplatform-Resources-
 *  lift-matching-the-legacy-enum-own-deferral + ComplaintStatus-mirrors-
 *  the-legacy-ComplaintStatus-1-1 + Legacy-values-preserved-in-identity-
 *  and-order + Display-name-resolution-stays-deferred-to-Phase-10" —
 *  LIVE-NOT-STALE plus FULFILLED-PREDICTION plus FORECAST-NOT-YET-
 *  FULFILLED-(Phase-10-Compose-Multiplatform-Resources-stringResource-
 *  lift). Verified via grep: ComplaintType declares exactly 6 variants
 *  (TECHNICAL + LANGUAGES + SITES_ADD + SITE_ERROR + FEATURES + CUSTOM)
 *  matching legacy identity+order; ComplaintStatus declares exactly 8
 *  variants (OPEN + IN_PROGRESS + RESOLVED + CLOSED + PLANNED + PINNED
 *  + UNKNOWN + NOT_PLANNED) matching legacy identity+order. The :ui
 *  displayName() helpers in ComplaintDisplayNames.kt currently use
 *  in-tree when-branch strings; the Phase 10 stringResource lift remains
 *  forecast — no Res.string.complaint_type_* keys exist yet. Per-cluster
 *  267 the displayName helpers landed in :ui (not :presentation) per
 *  layer-hygiene.
 *  Three classifications STAND on their own merits. Original Phase
 *  7.x.complaint.foundation-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
@OptIn(ExperimentalTime::class)
data class ComplaintSummary(
    val id: String,
    val userId: String,
    val type: ComplaintType,
    val subject: String,
    val body: String,
    val createdAt: Instant?,
    val status: ComplaintStatus,
    val appVersion: String? = null,
    /**
     * Admin-authored closure reason carved out of the legacy `Complaint.metadata["reason"]` key
     * — same single-typed-`String?`-field carve-out posture as [appVersion] (keeps the `:domain`
     * boundary free of banned `Any`). Populated by the `:data` mapper from the legacy metadata map
     * and on the static admin-pinned FAQ entries; `null` for records with no closure reason. The
     * `:ui` user-side card renders it as a `ClosureReasonCard` (icon + label + optional type chip +
     * reason text) on CLOSED / PINNED complaints — GAP-CMP-02 / GAP-CMP-23. The stored format is
     * `"${ClosureReasonType.key}: ${reason}"` (except OTHER, which stores the raw reason); the card
     * re-derives the type via `ClosureReasonType.fromString(...)`.
     */
    val reason: String? = null,
    /**
     * Parent-complaint id carved out of the legacy `Complaint.metadata["replyto"]` key when this
     * record is a user reply (a fresh OPEN complaint correlated to its parent). `null` for non-reply
     * records. Surfaced on the admin row as a "Reply to Complaint ID: …" reference line — GAP-CMP-12.
     * Same single-typed-`String?`-field carve-out posture as [appVersion] / [reason].
     */
    val replyToId: String? = null,
    /**
     * Device-OS API level carved out of the legacy `Complaint.metadata["osVersion"]` key — the
     * Android API level (e.g. `"34"`) recorded at submission time. Surfaced as a `String?` (the
     * raw stored value; the `:ui` card parses it to `Int` and maps it to a human-readable Android
     * version via `apiLevelToAndroidVersion`). `null` for records with no device metadata (e.g.,
     * the static admin-pinned FAQ entries, or pre-metadata submissions). Same single-typed-
     * `String?`-field carve-out posture as [appVersion] / [reason] / [replyToId] — keeps the
     * `:domain` boundary free of banned `Any`. Native parity: `ComplaintCard.kt:69-79` renders an
     * `InfoItem` (Android icon) from this field — GAP-CMP device-metadata row.
     */
    val osVersion: String? = null,
    /**
     * Device manufacturer carved out of the legacy `Complaint.metadata["manufacturer"]` key (e.g.
     * `"Samsung"`) recorded at submission time. `null`/blank for records with no device metadata.
     * Same single-typed-`String?`-field carve-out posture as [appVersion] / [reason] / [replyToId]
     * / [osVersion]. Native parity: `ComplaintCard.kt:82-94` renders an `InfoItem` (PhoneAndroid
     * icon) from this field — GAP-CMP device-metadata row.
     */
    val manufacturer: String? = null,
)

/**
 * Complaint category — mirrors the legacy
 * `me.manga.kira.presentation.features.complaint.model.ComplaintType` 1:1.
 *
 * Phase 7.x.complaint.foundation: introduced in `:domain` to avoid `:domain` -> `:shared`
 * reach. Legacy values preserved in identity and order (so the mapper in
 * [me.manga.kira.data.repository.ComplaintListRepositoryImpl] is a pure
 * `enumValueOf<DomainComplaintType>(legacy.name)`-style mapping with no risk of misalignment).
 *
 * Display-name resolution stays deferred to Phase 10 (Compose Multiplatform Resources lift),
 * matching the legacy enum's own deferral. The rework `:ui` foundation renders the enum's
 * `name` directly for now; the Phase 10 lift will swap both legacy and rework consumers to a
 * shared `stringResource(Res.string.complaint_type_*)` lookup.
 */
enum class ComplaintType {
    TECHNICAL,
    LANGUAGES,
    SITES_ADD,
    SITE_ERROR,
    FEATURES,
    CUSTOM,
}

/**
 * Complaint lifecycle status — mirrors the legacy
 * `me.manga.kira.presentation.features.complaint.model.ComplaintStatus` 1:1.
 *
 * Phase 7.x.complaint.foundation: introduced in `:domain` to avoid `:domain` -> `:shared`
 * reach. Legacy values preserved in identity and order.
 *
 * Display-name resolution stays deferred to Phase 10 (Compose Multiplatform Resources lift),
 * matching the legacy enum's own deferral.
 */
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
