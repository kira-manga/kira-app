package me.manga.kira.data.repository

import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.domain.auth.UserIdProvider
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.presentation.features.complaint.model.Complaint as LegacyComplaint
import me.manga.kira.presentation.features.complaint.model.ComplaintStatus as LegacyComplaintStatus
import me.manga.kira.presentation.features.complaint.model.ComplaintType as LegacyComplaintType
import me.manga.kira.presentation.features.complaint.usecase.GetUserComplaintUseCase as LegacyGetUserComplaintUseCase

/**
 * [ComplaintListRepository] strangler-fig delegate over the legacy `:shared`
 * [LegacyGetUserComplaintUseCase] + [UserIdProvider].
 *
 * Phase 7.x.complaint.foundation rework. Mirrors the orchestration the legacy
 * [me.manga.kira.presentation.features.complaint.viewmodel.ComplaintViewModel.loadForUser]
 * performs today (lines 92-102 of `shared/.../complaint/viewmodel/ComplaintViewModel.kt`):
 *  1. `userIdProvider.getUserId()` — platform-stable user/device ID.
 *  2. `legacy(userId)` — Firestore-bound read returning `List<LegacyComplaint>`.
 *  3. Map each [LegacyComplaint] -> [ComplaintSummary] via [toSummary].
 *  4. Prepend [PINNED_COMPLAINTS] (the static admin-pinned FAQ entries) — Phase
 *     7.x.complaint.pinnedfaq lift, mirrors legacy `pinnedTop + (data ?: emptyList())` in the
 *     legacy screen. Pinned entries flow through the same filter/search pipeline as DB-loaded
 *     records (the VM treats `state.all` uniformly; the dialog's PINNED status-gate hides
 *     Edit/Delete affordances on these). See [PINNED_COMPLAINTS] for the data + rationale.
 *
 * The entire orchestration is wrapped in `runCatching {}`. Any throw from any step (network
 * failures, Firestore permission denials, deserialization errors, etc.) surfaces as
 * [Result.failure]. The VM surfaces a single error state regardless of cause.
 *
 * **Strangler-fig posture**: same shape as [FeedbackRepositoryImpl] (3 :shared reaches) but
 * with a 2-reach fan-in. Same boundary class — `:data` owns the legacy-type-to-domain-type
 * mapping; `:presentation` never sees the legacy `LegacyComplaint` shape.
 *
 * **Import-alias `LegacyComplaint` / `LegacyGetUserComplaintUseCase` / `LegacyComplaintStatus` /
 * `LegacyComplaintType`**: avoids ambiguity with the new `:domain`-side
 * [ComplaintSummary] / [ComplaintStatus] / [ComplaintType] (same simple names, different
 * packages). Without the alias, every reference inside this file would need a fully-qualified
 * name. Same posture as `LegacySettingsRepository` in
 * [LanguageRepositoryImpl] and `LegacySendComplaintUseCase` in [FeedbackRepositoryImpl].
 *
 * **Why the mapper is private + file-local**: it has exactly one caller (the `loadUserComplaints`
 * implementation). Exposing it as `internal` or top-level would expand the API surface for no
 * reason. If a future slice needs the same mapping (e.g., admin view, complaint-detail view),
 * lift to a `:data`-internal `ComplaintMappers.kt` then. For now, locality wins.
 *
 * **`enumValueOf`-style mapping with explicit when**: the enum mappings (`LegacyComplaintStatus
 * -> ComplaintStatus` and `LegacyComplaintType -> ComplaintType`) use explicit `when` branches
 * rather than `enumValueOf<DomainType>(legacy.name)` because:
 *  - explicit `when` is `exhaustive` (the compiler enforces all legacy variants are handled),
 *    so adding a new legacy variant becomes a compile-time error here — exactly the contract
 *    we want for strangler-fig boundaries.
 *  - `enumValueOf` throws `IllegalArgumentException` on mismatch at runtime, which would surface
 *    as a `Result.failure` and present as a generic error to the user — bad UX for what's
 *    actually a developer-time class mismatch.
 *  - The two enum sets are mirror-image 1:1 (verified by inspection — see [ComplaintStatus] +
 *    [ComplaintType] KDoc), so the `when`s are simple and the compile-time guarantee is the
 *    extra value.
 *
 * **Metadata field — `appVersion` carve-out only** (Phase 7.x.complaint.admin.versionfilter):
 * the legacy `LegacyComplaint.metadata: Map<String, Any>?` map's `"appVersion"` key is
 * extracted via `metadata?.get("appVersion")?.toString()` and surfaced as the single
 * `ComplaintSummary.appVersion: String?` field. The user-side foundation screen doesn't
 * display this field today, but populating it symmetrically with the admin path keeps the
 * two mapper functions structurally identical (cheap; supports a future user-side display
 * extension without re-touching the mapper). The `Any` projection (the `.toString()` cast)
 * happens entirely within `:data` — the `:domain` boundary sees `String?`, never `Any`.
 *
 * **SRP (contract §6)**: owns ONE rule — "fetch the user's complaints from the legacy facade
 * and project them onto :domain types". No filtering, sorting, or derivation — those live in
 * the `:presentation` VM (filtering) or in Firestore (sorting).
 *
 * **DIP (contract §6)**: implements the [ComplaintListRepository] interface from `:domain`.
 * The `:domain` interface is the seam — `:presentation` / `:ui` never see this impl, only the
 * interface. The two :shared deps are constructor-injected by Koin at the composition root.
 *
 * **Lifecycle**: `single` in Koin (per [ComplaintListRepository] KDoc). Both :shared deps are
 * singletons (`SharedModule` binds `GetUserComplaintUseCase` as `single`; `PlatformModule.*`
 * binds `UserIdProvider` as `single`).
 *
 * **Load-bearing fixes preserved**: this slice does NOT touch the Coil ImageLoader, AVIF
 * decoder, HighQualitySkiaImageDecoder, or `:platform` — complaint list is pure
 * Firestore-bound read of text records. No load-bearing risk.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster24.staleKdocSweep.cascade,
 * Task #480, 2026-05-28): one stale-symbol-reference citation appears
 * above:
 *  - Lines 17-19 ("Mirrors the orchestration the legacy
 *    [me.manga.kira.presentation.features.complaint.viewmodel.ComplaintViewModel.loadForUser]
 *    performs today (lines 92-102 of
 *    `shared/.../complaint/viewmodel/ComplaintViewModel.kt`)").
 *    STALE-SYMBOL-REFERENCE — Phase 9.x.complaintvm.retire (§363
 *    sweep) deleted the legacy `:shared` complaint VM
 *    (`shared/.../complaint/viewmodel/ComplaintViewModel.kt`); verified
 *    by filesystem check returning zero hits via the `Glob` audit. The
 *    orchestration semantics (3-step fan-in: `userIdProvider.getUserId()`
 *    → `legacy(userId)` → map each `LegacyComplaint` → prepend
 *    `PINNED_COMPLAINTS`) still hold verbatim in this impl's
 *    `loadUserComplaints` at L92-95; only the citation anchor's source
 *    file is now gone. HOWEVER — the legacy
 *    [LegacyGetUserComplaintUseCase] + [UserIdProvider] singletons
 *    STILL EXIST as the cell of truth that this impl delegates to via
 *    `legacy(userId)` + `userIdProvider.getUserId()` at L93-94 (verified
 *    at the constructor signature below — `private val legacy:
 *    LegacyGetUserComplaintUseCase` + `private val userIdProvider:
 *    UserIdProvider`). The "Mirrors the orchestration the legacy
 *    ComplaintViewModel.loadForUser performs today" framing was correct
 *    at §253-era authoring; the cite's source file was subsequently
 *    retired in §363 while the underlying use-case + provider
 *    collaborators it referenced remained LIVE as the strangler-fig
 *    backbone. Mirror of §475-479 cluster-tier stale-symbol-reference
 *    precedent.
 * The SRP / DIP / strangler-fig-posture / import-alias / mapper-locality /
 * enumValueOf-rationale / metadata-carve-out / lifecycle /
 * load-bearing sub-sections all stand on their own merits past the §363
 * fulfilled retire. The ComplaintListRepositoryImpl remains LIVE as the
 * canonical strangler-fig delegate for the rework complaint-list
 * surface. Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citation is historical
 * record of the orchestration lineage including the now-retired
 * source-file anchor.
 */
class ComplaintListRepositoryImpl(
    private val legacy: LegacyGetUserComplaintUseCase,
    private val userIdProvider: UserIdProvider,
) : ComplaintListRepository {

    override suspend fun loadUserComplaints(): Result<List<ComplaintSummary>> = runCatchingCancellable {
        val userId = userIdProvider.getUserId()
        PINNED_COMPLAINTS + legacy(userId).map { it.toSummary() }
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
