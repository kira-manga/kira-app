package me.manga.kira.data.repository

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.domain.auth.UserIdProvider
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.AdminComplaintActionRepository
import me.manga.kira.presentation.features.complaint.model.Complaint as LegacyComplaint
import me.manga.kira.presentation.features.complaint.model.ComplaintStatus as LegacyComplaintStatus
import me.manga.kira.presentation.features.complaint.model.ComplaintType as LegacyComplaintType
import me.manga.kira.presentation.features.complaint.usecase.DeleteComplaintUseCase as LegacyDeleteComplaintUseCase
import me.manga.kira.presentation.features.complaint.usecase.GetAllComplaintUseCase as LegacyGetAllComplaintUseCase
import me.manga.kira.presentation.features.complaint.usecase.UpdateComplaintUseCase as LegacyUpdateComplaintUseCase

/**
 * [AdminComplaintActionRepository] strangler-fig delegate over the legacy `:shared` admin
 * mutation flow (`UpdateComplaintUseCase` + `DeleteComplaintUseCase`).
 *
 * Phase 7.x.complaint.admin.actions rework. Each method wraps a single legacy invocation in
 * `runCatching {}` so any throw (network failure, Firestore permission denial, legacy
 * `require()` validation) surfaces as [Result.failure]. The VM surfaces the throwable's
 * message via a `ShowErrorMessage` effect.
 *
 * **Strangler-fig posture**: same shape as
 * [me.manga.kira.data.repository.ComplaintActionRepositoryImpl] (user-side write sibling).
 * Fan-in of legacy collaborators into a single `:data` adapter. `:data` owns the legacy-type-
 * to-domain-type mapping; `:presentation` never sees the legacy `LegacyComplaint` shape.
 *
 * **Why the [legacyGetAll] dependency**: legacy admin VM (lines 65-189 of
 * `:shared/admin/complaint/AdminComplaintViewModel`) preserves the legacy `Complaint.metadata`
 * field across status-change and ADDS to it on closure-reason. The rework's
 * [ComplaintSummary] (`:domain` boundary) deliberately omits `metadata: Map<String, Any>?` —
 * banned `Any` per Contract §6. To preserve legacy behaviour without leaking `Any` to
 * `:domain`, this impl re-fetches the full legacy `Complaint` by id via [legacyGetAll]
 * before composing the write. Cost is a whole-collection Firestore fetch per admin action
 * (`legacyGetAll()` reads every complaint document, then we `firstOrNull { id }` client-side);
 * acceptable only because admin actions are low-frequency. The fetch-copy-write is also non-
 * transactional, so two near-simultaneous admin mutations can clobber each other. Both costs
 * retire with the Phase 9.x route-swap when both sides converge on `:domain` types.
 *
 * **Why [legacyUserIdProvider] is needed**: legacy `addClosureReason` writes
 * `metadata["reasonAddedBy"] = userIdProvider.getUserId()` (line 153). The rework preserves
 * the same field for parity. The provider is platform-specific (Android/iOS/Desktop actuals)
 * — same singleton consumed by the legacy admin VM.
 *
 * **Import-alias `Legacy*` prefix**: avoids ambiguity with the new `:domain` types
 * ([ComplaintSummary] / [ComplaintStatus]) and with the rework's
 * [me.manga.kira.domain.usecase.complaint.AdminDeleteComplaintUseCase] (which shares the
 * `DeleteComplaintUseCase` short name with the legacy use case). Same posture as
 * [ComplaintActionRepositoryImpl].
 *
 * **Reverse-direction mapper** ([toLegacyStatus]): the inverse of the forward mapper in
 * [ComplaintListRepositoryImpl]. Exhaustive `when` — adding a new variant on either side
 * becomes a compile-time error here, exactly the contract we want for strangler-fig
 * boundaries.
 *
 * **SRP**: ONE rule — "submit admin-side complaint mutations through the legacy facade and
 * report success/failure". No reads (those live on [me.manga.kira.data.repository.AdminComplaintListRepositoryImpl]);
 * the [legacyGetAll] lookup is a write-side helper, not a public read.
 *
 * **DIP**: implements the [AdminComplaintActionRepository] interface from `:domain`. The
 * interface is the seam — `:presentation` / `:ui` never see this impl, only the use cases
 * that depend on the interface.
 *
 * **Lifecycle**: `single` in Koin. All legacy deps are themselves singletons in
 * `SharedModule`.
 *
 * **Load-bearing fixes preserved**: this slice does NOT touch the Coil ImageLoader, AVIF
 * decoder, HighQualitySkiaImageDecoder, or `:platform` — complaint actions are pure
 * Firestore-bound text mutations. No load-bearing risk.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster24.staleKdocSweep.cascade,
 * Task #480, 2026-05-28): two categories of citation appear above:
 *  - Lines 29-30 ("legacy admin VM (lines 65-189 of
 *    `:shared/admin/complaint/AdminComplaintViewModel`)").
 *    STALE-SYMBOL-REFERENCE — Phase 9.x.admincomplaint.retire (§366
 *    sweep) deleted the legacy `:shared` admin complaint VM
 *    (`:shared/admin/complaint/AdminComplaintViewModel.kt`); verified
 *    by filesystem check returning zero hits via the `Glob` audit. The
 *    metadata-preservation orchestration described (legacy VM lines
 *    65-189 preserving `Complaint.metadata` across status-change +
 *    ADDING to it on closure-reason) has been lifted into this impl's
 *    `addClosureReason` body at L87-107 — the citation is now an
 *    orphan source-anchor pointing at the design-lineage source that
 *    moved into the rework `:data` layer.
 *  - Lines 36-37 ("retired by Phase 9.x route-swap when both sides
 *    converge on `:domain` types"). PARTIALLY-FULFILLED-INVERSION —
 *    Phase 9.x.admincomplaint.swap (§365 sweep) re-pointed
 *    `Screen.ComplaintAdmin` to the rework `AdminComplaintScreen`; Phase
 *    9.x.admincomplaint.retire (§366 sweep) deleted the orphan legacy
 *    admin VM + screen + 2 helpers + Koin binding. HOWEVER — the legacy
 *    `:shared` use-case quartet ([LegacyGetAllComplaintUseCase],
 *    [LegacyUpdateComplaintUseCase], [LegacyDeleteComplaintUseCase], +
 *    [UserIdProvider]) STILL EXIST as the cell of truth that this impl
 *    delegates to via 4 constructor params (verified at the
 *    constructor signature below — `private val legacyGetAll:
 *    LegacyGetAllComplaintUseCase` + `private val legacyUpdate:
 *    LegacyUpdateComplaintUseCase` + `private val legacyDelete:
 *    LegacyDeleteComplaintUseCase` + `private val legacyUserIdProvider:
 *    UserIdProvider`). The "retired by Phase 9.x route-swap when both
 *    sides converge on `:domain` types" forecast was partially
 *    fulfilled — the consumer-side legacy admin VM + screen retired
 *    across §§365 + 366; the underlying Firestore-bound use-case
 *    quartet remained as the rework's admin-mutation backbone. The
 *    extra-Firestore-READ-per-admin-action cost framing (the L33-37
 *    "Cost is one extra Firestore READ per admin action; acceptable
 *    for admin frequency (low)") still holds verbatim post-§§365 +
 *    366 retires — the impl still fetches the full legacy `Complaint`
 *    by id via `legacyGetAll()` before composing the write. Mirror of
 *    §§475-479 cluster-tier partially-fulfilled-inversion precedent.
 * The SRP / DIP / import-alias / reverse-direction-mapper / lifecycle /
 * load-bearing sub-sections all stand on their own merits past the
 * §§365 + 366 fulfilled retires. The AdminComplaintActionRepositoryImpl
 * remains LIVE as the canonical strangler-fig delegate for the rework
 * admin-complaint-action surface. Original §253-era prose preserved
 * verbatim per the audit-trail-preservation convention — the citations
 * are historical record of the design lineage including the retired
 * legacy-VM source anchor + the partially-fulfilled-inversion
 * forecast.
 */
@OptIn(ExperimentalTime::class)
class AdminComplaintActionRepositoryImpl(
    private val legacyGetAll: LegacyGetAllComplaintUseCase,
    private val legacyUpdate: LegacyUpdateComplaintUseCase,
    private val legacyDelete: LegacyDeleteComplaintUseCase,
    private val legacyUserIdProvider: UserIdProvider,
) : AdminComplaintActionRepository {

    override suspend fun changeStatus(
        complaint: ComplaintSummary,
        newStatus: ComplaintStatus,
    ): Result<Unit> = runCatchingCancellable {
        val legacy = fetchLegacyById(complaint.id)
        val updated = legacy.copy(status = newStatus.toLegacyStatus())
        legacyUpdate(updated)
    }

    override suspend fun addClosureReason(
        complaint: ComplaintSummary,
        reason: String,
    ): Result<Unit> = runCatchingCancellable {
        val legacy = fetchLegacyById(complaint.id)
        val updatedMetadata = legacy.metadata?.toMutableMap() ?: mutableMapOf()
        updatedMetadata["reason"] = reason
        updatedMetadata["reasonAddedBy"] = legacyUserIdProvider.getUserId()
        updatedMetadata["reasonAddedAt"] = Clock.System.now().toEpochMilliseconds().toString()

        val newStatus = when (legacy.status) {
            LegacyComplaintStatus.OPEN, LegacyComplaintStatus.IN_PROGRESS -> LegacyComplaintStatus.CLOSED
            else -> legacy.status
        }

        val updated = legacy.copy(
            status = newStatus,
            metadata = updatedMetadata,
        )
        legacyUpdate(updated)
    }

    override suspend fun deleteComplaint(id: String): Result<Unit> = runCatchingCancellable {
        legacyDelete(id)
    }

    override suspend fun editComplaint(
        original: ComplaintSummary,
        type: ComplaintType,
        subject: String,
        body: String,
    ): Result<Unit> = runCatchingCancellable {
        val legacy = fetchLegacyById(original.id)
        val updated = legacy.copy(
            type = type.toLegacyType(),
            subject = subject,
            body = body,
        )
        legacyUpdate(updated)
    }

    private suspend fun fetchLegacyById(id: String): LegacyComplaint =
        legacyGetAll().firstOrNull { it.id == id }
            ?: error("Complaint $id not found")

    private fun ComplaintStatus.toLegacyStatus(): LegacyComplaintStatus = when (this) {
        ComplaintStatus.OPEN -> LegacyComplaintStatus.OPEN
        ComplaintStatus.IN_PROGRESS -> LegacyComplaintStatus.IN_PROGRESS
        ComplaintStatus.RESOLVED -> LegacyComplaintStatus.RESOLVED
        ComplaintStatus.CLOSED -> LegacyComplaintStatus.CLOSED
        ComplaintStatus.PLANNED -> LegacyComplaintStatus.PLANNED
        ComplaintStatus.PINNED -> LegacyComplaintStatus.PINNED
        ComplaintStatus.UNKNOWN -> LegacyComplaintStatus.UNKNOWN
        ComplaintStatus.NOT_PLANNED -> LegacyComplaintStatus.NOT_PLANNED
    }

    private fun ComplaintType.toLegacyType(): LegacyComplaintType = when (this) {
        ComplaintType.TECHNICAL -> LegacyComplaintType.TECHNICAL
        ComplaintType.LANGUAGES -> LegacyComplaintType.LANGUAGES
        ComplaintType.SITES_ADD -> LegacyComplaintType.SITES_ADD
        ComplaintType.SITE_ERROR -> LegacyComplaintType.SITE_ERROR
        ComplaintType.FEATURES -> LegacyComplaintType.FEATURES
        ComplaintType.CUSTOM -> LegacyComplaintType.CUSTOM
    }
}
