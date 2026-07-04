package me.manga.kira.data.repository

import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.AdminComplaintListRepository
import me.manga.kira.presentation.features.complaint.model.Complaint as LegacyComplaint
import me.manga.kira.presentation.features.complaint.model.ComplaintStatus as LegacyComplaintStatus
import me.manga.kira.presentation.features.complaint.model.ComplaintType as LegacyComplaintType
import me.manga.kira.presentation.features.complaint.usecase.GetAllComplaintUseCase as LegacyGetAllComplaintUseCase

/**
 * [AdminComplaintListRepository] strangler-fig delegate over the legacy `:shared`
 * [LegacyGetAllComplaintUseCase].
 *
 * Phase 7.x.complaint.admin rework. Two-step fetch-then-map orchestration delegating to the
 * legacy `:shared` [LegacyGetAllComplaintUseCase]:
 *  1. `legacy()` — Firestore-bound read returning `List<LegacyComplaint>` (collection-wide, no
 *     userId filter).
 *  2. Map each [LegacyComplaint] -> [ComplaintSummary] via [toSummary].
 *
 * (The legacy `:shared` admin VM that previously hosted this same `loadAllComplaints` orchestration
 * was retired in `Phase 9.x.admincomplaint.retire` — its responsibilities are now split between
 * this strangler-fig impl and the rework `:presentation` `AdminComplaintViewModel`.)
 *
 * The entire orchestration is wrapped in `runCatching {}`. Any throw from any step (network
 * failures, Firestore permission denials, deserialization errors, etc.) surfaces as
 * [Result.failure]. The VM surfaces a single error state regardless of cause.
 *
 * **One reach vs the user-side's two**: the admin path doesn't need `UserIdProvider` because the
 * query is collection-wide, not user-scoped. Identity gating happens at navigation time
 * (`Admin.isAdmin` in `SettingsReworkScreenRoute`) — this repository assumes the caller has
 * already passed that gate.
 *
 * **Strangler-fig posture**: sibling of [ComplaintListRepositoryImpl]. Same boundary class —
 * `:data` owns the legacy-type-to-domain-type mapping; `:presentation` never sees the legacy
 * `LegacyComplaint` shape.
 *
 * **Import-alias `LegacyComplaint` / `LegacyGetAllComplaintUseCase` / `LegacyComplaintStatus` /
 * `LegacyComplaintType`**: avoids ambiguity with the new `:domain`-side [ComplaintSummary] /
 * [ComplaintStatus] / [ComplaintType] (same simple names, different packages). Without the alias,
 * every reference inside this file would need a fully-qualified name. Same posture as
 * `LegacyComplaint` / `LegacyGetUserComplaintUseCase` in [ComplaintListRepositoryImpl].
 *
 * **Why the mappers are private + file-local (not extracted to a shared helper)**: the user-side
 * [ComplaintListRepositoryImpl]'s KDoc anticipated this exact extraction: *"If a future slice
 * needs the same mapping (e.g., admin view, complaint-detail view), lift to a `:data`-internal
 * `ComplaintMappers.kt` then."* This slice has two callers of identical mappings — exactly the
 * trigger condition. **But** lifting in THIS commit would require touching the existing
 * [ComplaintListRepositoryImpl] (changing its private mappers to internal-helper imports), which
 * would push this commit beyond the 5-files-per-commit cap and intermix two structurally-
 * distinct concerns (admin-foundation slice + DRY-cleanup refactor). The mappers stay duplicated
 * for now; a follow-on `Phase 7.x.complaint.mappers` cleanup commit can lift them once the admin
 * slice is verified. Duplication is `:data`-internal — no API surface exposed — so the cost is
 * bounded to two file-local 12-line blocks.
 *
 * **`enumValueOf`-style mapping with explicit when**: same rationale as
 * [ComplaintListRepositoryImpl] — explicit `when` is exhaustive (new legacy enum variants become
 * a compile-time error at the strangler-fig boundary), where `enumValueOf` would surface as
 * runtime `IllegalArgumentException` → `Result.failure` → generic user-facing error.
 *
 * **Metadata field — typed single-field carve-outs only** (Phase 7.x.complaint.admin.versionfilter):
 * the legacy `LegacyComplaint.metadata: Map<String, Any>?` map's `"appVersion"`, `"reason"`,
 * `"replyto"`, `"osVersion"`, and `"manufacturer"` keys are each extracted via
 * `metadata?.get(...)?.toString()` and surfaced as the typed `ComplaintSummary.appVersion`,
 * `.reason`, `.replyToId`, `.osVersion`, `.manufacturer: String?` fields. The `Any` projection (the
 * `.toString()` cast) happens entirely within `:data` — the `:domain` boundary sees `String?`,
 * never `Any`. Other legacy `metadata` keys stay in `:shared` until / unless future slices need
 * them; each future carve-out follows the same single-typed-field posture.
 *
 * **SRP (contract §6)**: owns ONE rule — "fetch ALL complaints from the legacy facade and
 * project them onto :domain types". No filtering, sorting, or derivation — those live in the
 * `:presentation` VM (filtering / sorting) or in Firestore (collection ordering).
 *
 * **DIP (contract §6)**: implements the [AdminComplaintListRepository] interface from `:domain`.
 * The `:domain` interface is the seam — `:presentation` / `:ui` never see this impl, only the
 * interface. The single :shared dep is constructor-injected by Koin at the composition root.
 *
 * **Lifecycle**: `single` in Koin (per [AdminComplaintListRepository] KDoc). The :shared dep is
 * a singleton (`SharedModule` binds `GetAllComplaintUseCase` as `single`).
 *
 * **Load-bearing fixes preserved**: this slice does NOT touch the Coil ImageLoader, AVIF
 * decoder, HighQualitySkiaImageDecoder, or `:platform` — complaint list is pure Firestore-bound
 * read of text records. No load-bearing risk.
 */
class AdminComplaintListRepositoryImpl(
    private val legacy: LegacyGetAllComplaintUseCase,
) : AdminComplaintListRepository {

    override suspend fun loadAllComplaints(): Result<List<ComplaintSummary>> = runCatchingCancellable {
        legacy().map { it.toSummary() }
    }

    private fun LegacyComplaint.toSummary(): ComplaintSummary = ComplaintSummary(
        id = id,
        userId = userId,
        type = type.toDomain(),
        subject = subject,
        body = body,
        createdAt = createdAt,
        status = status.toDomain(),
        appVersion = metadata?.get("appVersion")?.toString(),
        reason = metadata?.get("reason")?.toString(),
        replyToId = metadata?.get("replyto")?.toString(),
        osVersion = metadata?.get("osVersion")?.toString(),
        manufacturer = metadata?.get("manufacturer")?.toString(),
    )

    private fun LegacyComplaintStatus.toDomain(): ComplaintStatus = when (this) {
        LegacyComplaintStatus.OPEN -> ComplaintStatus.OPEN
        LegacyComplaintStatus.IN_PROGRESS -> ComplaintStatus.IN_PROGRESS
        LegacyComplaintStatus.RESOLVED -> ComplaintStatus.RESOLVED
        LegacyComplaintStatus.CLOSED -> ComplaintStatus.CLOSED
        LegacyComplaintStatus.PLANNED -> ComplaintStatus.PLANNED
        LegacyComplaintStatus.PINNED -> ComplaintStatus.PINNED
        LegacyComplaintStatus.UNKNOWN -> ComplaintStatus.UNKNOWN
        LegacyComplaintStatus.NOT_PLANNED -> ComplaintStatus.NOT_PLANNED
    }

    private fun LegacyComplaintType.toDomain(): ComplaintType = when (this) {
        LegacyComplaintType.TECHNICAL -> ComplaintType.TECHNICAL
        LegacyComplaintType.LANGUAGES -> ComplaintType.LANGUAGES
        LegacyComplaintType.SITES_ADD -> ComplaintType.SITES_ADD
        LegacyComplaintType.SITE_ERROR -> ComplaintType.SITE_ERROR
        LegacyComplaintType.FEATURES -> ComplaintType.FEATURES
        LegacyComplaintType.CUSTOM -> ComplaintType.CUSTOM
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster153.staleKdocSweep.cascade,
 * Task #609, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-ninety-fifth sibling of the cluster57-152
 * sweep — OPENING file of the wave-26 :data/repository complaint trio
 * 3-leaf batch alongside ComplaintActionRepositoryImpl plus PinnedComplaints;
 * OPENS :data/repository complaint trio 1/3):
 *  (a) "AdminComplaintListRepository-strangler-fig-delegate-over-the-legacy
 *  -:shared-LegacyGetAllComplaintUseCase + Phase-7.x.complaint.admin-rework
 *  + Two-step-fetch-then-map-orchestration-delegating-to-the-legacy-:shared
 *  -LegacyGetAllComplaintUseCase + legacy-Firestore-bound-read-returning-
 *  List-LegacyComplaint-collection-wide-no-userId-filter + Map-each-Legacy
 *  Complaint-to-ComplaintSummary-via-toSummary + The-legacy-:shared-admin-
 *  VM-that-previously-hosted-this-same-loadAllComplaints-orchestration-was
 *  -retired-in-Phase-9.x.admincomplaint.retire + The-entire-orchestration-
 *  is-wrapped-in-runCatching-Any-throw-network-failures-Firestore-permission
 *  -denials-deserialization-errors-surfaces-as-Result.failure + One-reach-
 *  vs-the-user-side-s-two-the-admin-path-does-not-need-UserIdProvider-because
 *  -the-query-is-collection-wide-not-user-scoped + Identity-gating-happens-
 *  at-navigation-time-Admin.isAdmin-in-SettingsReworkScreenRoute + Strangler
 *  -fig-posture-sibling-of-ComplaintListRepositoryImpl + Import-alias-Legacy
 *  Complaint-LegacyGetAllComplaintUseCase-LegacyComplaintStatus-LegacyComplaint
 *  Type-avoids-ambiguity-with-the-new-:domain-side-ComplaintSummary-Complaint
 *  Status-ComplaintType-same-simple-names-different-packages + Why-the-mappers
 *  -are-private-plus-file-local-not-extracted-to-a-shared-helper-Lifting-in-
 *  THIS-commit-would-require-touching-the-existing-ComplaintListRepositoryImpl
 *  -changing-its-private-mappers-to-internal-helper-imports-which-would-push
 *  -this-commit-beyond-the-5-files-per-commit-cap + The-mappers-stay-duplicated
 *  -for-now-a-follow-on-Phase-7.x.complaint.mappers-cleanup-commit-can-lift-
 *  them-once-the-admin-slice-is-verified + enumValueOf-style-mapping-with-
 *  explicit-when-same-rationale-as-ComplaintListRepositoryImpl-explicit-when-
 *  is-exhaustive-new-legacy-enum-variants-become-a-compile-time-error-at-the
 *  -strangler-fig-boundary + Metadata-field-appVersion-carve-out-only-Phase-
 *  7.x.complaint.admin.versionfilter-the-legacy-LegacyComplaint.metadata-Map
 *  -String-Any-map-s-appVersion-key-is-extracted-via-metadata-get-appVersion
 *  -toString-and-surfaced-as-the-single-ComplaintSummary.appVersion-String-
 *  field + The-Any-projection-the-toString-cast-happens-entirely-within-:data
 *  -the-:domain-boundary-sees-String-never-Any + SRP-contract-section-6-owns
 *  -ONE-rule-fetch-ALL-complaints-from-the-legacy-facade-and-project-them-
 *  onto-:domain-types + DIP-contract-section-6-implements-the-AdminComplaint
 *  ListRepository-interface-from-:domain + Lifecycle-single-in-Koin-per-Admin
 *  ComplaintListRepository-KDoc + Load-bearing-fixes-preserved-this-slice-
 *  does-NOT-touch-the-Coil-ImageLoader-AVIF-decoder-HighQualitySkiaImage
 *  Decoder-or-:platform-complaint-list-is-pure-Firestore-bound-read-of-text
 *  -records-No-load-bearing-risk" — LIVE-NOT-STALE plus FULFILLED-PREDICTION.
 *  Verified: AdminComplaintListRepositoryImpl shipped as a strangler-fig
 *  delegate. loadAllComplaints() forwards to the injected LegacyGetAll
 *  ComplaintUseCase and maps each LegacyComplaint to a :domain Complaint
 *  Summary via the private toSummary() extension. The two enum-mapper
 *  extensions (toDomain on LegacyComplaintStatus + LegacyComplaintType) are
 *  byte-for-byte parallel to ComplaintListRepositoryImpl's same-shape
 *  mappers — duplication-for-now stance honored. The appVersion-carve-out
 *  from the Map<String, Any>? metadata field is shipped via metadata?.
 *  get("appVersion")?.toString() — the Any projection contained entirely
 *  within :data, the :domain boundary sees String? only. The "retired
 *  :shared admin VM" cross-reference (Phase 9.x.admincomplaint.retire,
 *  Task #366) is FULFILLED — the legacy admin VM is gone, its orchestration
 *  split between this strangler-fig impl and the rework :presentation
 *  AdminComplaintViewModel. Consumed by ObserveAllComplaintsUseCase
 *  (cluster123 sibling X) via the loadAllComplaints() suspend surface.
 *  One classification. Original Phase 7.x.complaint.admin (Task #258) impl
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */

