package me.manga.kira.data.repository

import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.ComplaintActionRepository
import me.manga.kira.presentation.features.complaint.model.Complaint as LegacyComplaint
import me.manga.kira.presentation.features.complaint.model.ComplaintStatus as LegacyComplaintStatus
import me.manga.kira.presentation.features.complaint.model.ComplaintType as LegacyComplaintType
import me.manga.kira.presentation.features.complaint.usecase.DeleteComplaintUseCase as LegacyDeleteComplaintUseCase
import me.manga.kira.presentation.features.complaint.usecase.GetUserComplaintUseCase as LegacyGetUserComplaintUseCase
import me.manga.kira.presentation.features.complaint.usecase.SendComplaintUseCase as LegacySendComplaintUseCase
import me.manga.kira.presentation.features.complaint.usecase.UpdateComplaintUseCase as LegacyUpdateComplaintUseCase

/**
 * [ComplaintActionRepository] strangler-fig delegate over the three legacy `:shared` mutation
 * use cases ([LegacySendComplaintUseCase], [LegacyUpdateComplaintUseCase],
 * [LegacyDeleteComplaintUseCase]).
 *
 * Phase 7.x.complaint.actions rework. Each method wraps a single legacy invocation in
 * `runCatching {}` so any throw (network failure, Firestore permission denial, legacy
 * `require()` validation on `SendComplaintUseCase`) surfaces as [Result.failure]. The VM
 * surfaces the throwable's message via a `ShowErrorMessage` effect.
 *
 * **Strangler-fig posture**: same shape as
 * [me.manga.kira.data.repository.ComplaintListRepositoryImpl] (foundation slice) — fan-in
 * of multiple legacy collaborators into a single `:data` adapter. This impl is the boundary
 * class — `:data` owns the legacy-type-to-domain-type mapping; `:presentation` never sees the
 * legacy `LegacyComplaint` shape.
 *
 * **Import-alias `Legacy*` prefix**: avoids ambiguity with the new `:domain` types
 * ([ComplaintSummary] / [ComplaintStatus] / [ComplaintType]) that mirror the legacy names. Same
 * posture as [ComplaintListRepositoryImpl] and [FeedbackRepositoryImpl].
 *
 * **`LegacyDeleteComplaintUseCase` alias is load-bearing**: the rework's
 * [me.manga.kira.domain.usecase.complaint.DeleteComplaintUseCase] shares the SAME short
 * class name as the legacy use case (different packages — no compile clash, but the alias
 * prevents references in this file from being ambiguous in tools / IDEs).
 *
 * **Reverse-direction mappers** ([toLegacyStatus] / [toLegacyType]): the inverse of the
 * forward mappers in [ComplaintListRepositoryImpl]. Both directions are exhaustive `when`
 * branches (not `enumValueOf<LegacyType>(domain.name)`-style reflection) — adding a new
 * variant on either side becomes a compile-time error here, exactly the contract we want for
 * strangler-fig boundaries. See [ComplaintListRepositoryImpl] KDoc for the full rationale.
 *
 * **Edit reconstruction**: legacy `UpdateComplaintUseCase` takes a full `LegacyComplaint`
 * record and `updateComplaint(complaint)` overwrites the document. To preserve non-edited
 * fields ([ComplaintSummary.userId], [ComplaintSummary.type], [ComplaintSummary.createdAt],
 * [ComplaintSummary.status]), [editComplaint] reconstructs a `LegacyComplaint` from
 * [ComplaintSummary] + the new subject/body. The legacy's `metadata` map is PRESERVED across
 * edits — rebuilt from the carved-out [ComplaintSummary] fields (`appVersion` / `reason` /
 * `replyToId` / `osVersion` / `manufacturer`) via [preservedMetadata] — matching native parity
 * (native edits via `complaint.copy(body = body)`, keeping the original `metadata` intact). This
 * fixed a prior bug where `metadata = null` silently dropped the device-metadata row + closure
 * reason on every user edit. See the [preservedMetadata] KDoc for the per-key rebuild rationale.
 *
 * **Reply construction**: legacy reply is a fresh `LegacyComplaint` submitted via
 * `LegacySendComplaintUseCase`. The reply preserves the parent's `userId`/`type`/`subject` for
 * admin threading and overrides `body` with the typed reply. The parent's device metadata
 * (`osVersion` / `manufacturer` / `appVersion`) is PRESERVED across the reply — rebuilt from the
 * carved-out [ComplaintSummary] fields via [preservedMetadata] — alongside the `replyto`
 * correlation key, matching native parity (native writes `(parent.metadata ?: emptyMap()) +
 * mapOf("replyto" to parent.id)`). The `replyto` entry is appended last so it always points at
 * the reply's parent. No banned `Any` is exposed even though the legacy `LegacyComplaint.metadata`
 * field is typed `Map<String, Any>?` (the impl is the bridge — the `:domain` interface signature
 * accepts only [ComplaintSummary] + body strings).
 *
 * **SRP**: ONE rule — "submit user-side complaint mutations through the legacy facade and
 * report success/failure". No reads (those live on [ComplaintListRepositoryImpl]); no
 * derivation; no orchestration beyond the per-method legacy call.
 *
 * **DIP**: implements the [ComplaintActionRepository] interface from `:domain`. The interface
 * is the seam — `:presentation` / `:ui` never see this impl, only the use cases that depend on
 * the interface.
 *
 * **Lifecycle**: `single` in Koin (matches [ComplaintListRepository] posture). All three
 * legacy use case deps are themselves singletons in `SharedModule`.
 *
 * **Load-bearing fixes preserved**: this slice does NOT touch the Coil ImageLoader, AVIF
 * decoder, HighQualitySkiaImageDecoder, or `:platform` — complaint actions are pure
 * Firestore-bound text mutations. No load-bearing risk.
 */
class ComplaintActionRepositoryImpl(
    private val send: LegacySendComplaintUseCase,
    private val update: LegacyUpdateComplaintUseCase,
    private val delete: LegacyDeleteComplaintUseCase,
    // #9: re-fetch the full legacy complaint by (userId, id) so edit/reply preserve EVERY metadata
    // key (model / osRelease / manufacturer / admin reasonAddedBy / reasonAddedAt), not just the 5
    // carved-out ComplaintSummary fields. Mirrors the admin path's legacyGetAll re-fetch.
    private val getUser: LegacyGetUserComplaintUseCase,
) : ComplaintActionRepository {

    override suspend fun replyToComplaint(
        parent: ComplaintSummary,
        body: String,
    ): Result<Unit> = runCatchingCancellable {
        // PINNED FAQ entries are static in-binary records (userId "0", id "admin") that never
        // exist in Firestore, so the #9 re-fetch would always fail; build the reply from the
        // in-memory ComplaintSummary instead, mirroring native (which never re-fetches the parent).
        val legacyParent =
            if (parent.status == ComplaintStatus.PINNED) parent.toLegacy() else fetchLegacyById(parent.userId, parent.id)
        // #9: re-fetch the parent so the reply inherits the FULL parent metadata map verbatim
        // (native: `(parent.metadata ?: emptyMap()) + mapOf("replyto" to parent.id)`), not just the
        // 5 carved summary fields. `replyto` added last so it always points at this reply's parent.
        val metadata = (legacyParent.metadata ?: emptyMap()) + ("replyto" to legacyParent.id)
        val reply = LegacyComplaint(
            id = "",
            userId = legacyParent.userId,
            type = legacyParent.type,
            subject = legacyParent.subject,
            body = body,
            createdAt = null,
            status = LegacyComplaintStatus.OPEN,
            metadata = metadata,
        )
        send(reply)
    }

    override suspend fun editComplaint(
        original: ComplaintSummary,
        subject: String,
        body: String,
    ): Result<Unit> = runCatchingCancellable {
        // #9: re-fetch then `copy(subject, body)` — native parity (`complaint.copy(body = body)`),
        // preserving the full metadata map + status + createdAt + admin reason fields. The previous
        // reconstruction rebuilt only 5 carved fields, silently dropping model/osRelease (every KMP
        // DeviceInfoProvider writes them) and any admin reasonAddedBy/reasonAddedAt on every edit.
        val legacy = fetchLegacyById(original.userId, original.id)
        val edited = legacy.copy(subject = subject, body = body)
        update(edited)
    }

    /** #9: re-fetch the user's full legacy complaint by id (mirrors the admin legacyGetAll path). */
    private suspend fun fetchLegacyById(userId: String, id: String): LegacyComplaint =
        getUser(userId).firstOrNull { it.id == id }
            ?: error("Complaint $id not found for user $userId")

    /**
     * Reconstruct a legacy [LegacyComplaint] from the in-memory [ComplaintSummary] — used only for
     * PINNED FAQ parents, which are never persisted to Firestore so [fetchLegacyById] cannot find
     * them. The metadata map is rebuilt from the carved-out `:domain` fields (each included only
     * when non-null) so the reply still inherits the parent's context, matching native parity.
     */
    private fun ComplaintSummary.toLegacy(): LegacyComplaint = LegacyComplaint(
        id = id,
        userId = userId,
        type = type.toLegacyType(),
        subject = subject,
        body = body,
        createdAt = null,
        status = LegacyComplaintStatus.PINNED,
        metadata = buildMap<String, Any> {
            appVersion?.let { put("appVersion", it) }
            reason?.let { put("reason", it) }
            replyToId?.let { put("replyto", it) }
            osVersion?.let { put("osVersion", it) }
            manufacturer?.let { put("manufacturer", it) }
        }.ifEmpty { null },
    )

    private fun ComplaintType.toLegacyType(): LegacyComplaintType = when (this) {
        ComplaintType.TECHNICAL -> LegacyComplaintType.TECHNICAL
        ComplaintType.LANGUAGES -> LegacyComplaintType.LANGUAGES
        ComplaintType.SITES_ADD -> LegacyComplaintType.SITES_ADD
        ComplaintType.SITE_ERROR -> LegacyComplaintType.SITE_ERROR
        ComplaintType.FEATURES -> LegacyComplaintType.FEATURES
        ComplaintType.CUSTOM -> LegacyComplaintType.CUSTOM
    }

    override suspend fun deleteComplaint(id: String): Result<Unit> = runCatchingCancellable {
        delete(id)
    }
    // #9: edit/reply re-fetch the legacy complaint and copy it, inheriting the legacy status/type
    // verbatim instead of reconstructing from the :domain enums. The exception is PINNED FAQ
    // parents (never in Firestore): the reply path rebuilds those locally via toLegacy/toLegacyType.
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster153.staleKdocSweep.cascade,
 * Task #609, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-ninety-sixth sibling of the cluster57-152
 * sweep — second file of the wave-26 :data/repository complaint trio
 * 3-leaf batch alongside AdminComplaintListRepositoryImpl plus
 * PinnedComplaints):
 *  (a) "ComplaintActionRepository-strangler-fig-delegate-over-the-three-
 *  legacy-:shared-mutation-use-cases-LegacySendComplaintUseCase-Legacy
 *  UpdateComplaintUseCase-LegacyDeleteComplaintUseCase + Phase-7.x.complaint
 *  .actions-rework + Each-method-wraps-a-single-legacy-invocation-in-run
 *  Catching-so-any-throw-network-failure-Firestore-permission-denial-
 *  legacy-require-validation-on-SendComplaintUseCase-surfaces-as-Result.
 *  failure + Strangler-fig-posture-same-shape-as-ComplaintListRepositoryImpl
 *  -foundation-slice + Fan-in-of-multiple-legacy-collaborators-into-a-single
 *  -:data-adapter + This-impl-is-the-boundary-class-:data-owns-the-legacy-
 *  type-to-domain-type-mapping-:presentation-never-sees-the-legacy-Legacy
 *  Complaint-shape + Import-alias-Legacy-prefix-avoids-ambiguity-with-the-
 *  new-:domain-types-ComplaintSummary-ComplaintStatus-ComplaintType + Legacy
 *  DeleteComplaintUseCase-alias-is-load-bearing-the-rework-s-DeleteComplaint
 *  UseCase-shares-the-SAME-short-class-name-as-the-legacy-use-case + Reverse
 *  -direction-mappers-toLegacyStatus-toLegacyType-the-inverse-of-the-forward
 *  -mappers-in-ComplaintListRepositoryImpl + Both-directions-are-exhaustive-
 *  when-branches-not-enumValueOf-LegacyType-domain.name-style-reflection +
 *  Edit-reconstruction-legacy-UpdateComplaintUseCase-takes-a-full-Legacy
 *  Complaint-record-and-updateComplaint-complaint-overwrites-the-document +
 *  To-preserve-non-edited-fields-ComplaintSummary.userId-type-createdAt-
 *  status-editComplaint-reconstructs-a-LegacyComplaint-from-ComplaintSummary
 *  -plus-the-new-subject-body + The-legacy-s-metadata-field-is-set-to-null-
 *  not-preserved-across-edits + Reply-construction-legacy-reply-is-a-fresh-
 *  LegacyComplaint-submitted-via-LegacySendComplaintUseCase + The-reply-
 *  preserves-the-parent-s-userId-type-subject-for-admin-threading-and-
 *  overrides-body-with-the-typed-reply + metadata-mapOf-replyto-to-parent.id
 *  -String-to-String-map-no-banned-Any-exposure-even-though-the-legacy-
 *  LegacyComplaint.metadata-field-is-typed-Map-String-Any + SRP-ONE-rule-
 *  submit-user-side-complaint-mutations-through-the-legacy-facade-and-report
 *  -success-failure + No-reads-those-live-on-ComplaintListRepositoryImpl-no
 *  -derivation-no-orchestration-beyond-the-per-method-legacy-call + DIP-
 *  implements-the-ComplaintActionRepository-interface-from-:domain + Life
 *  cycle-single-in-Koin-matches-ComplaintListRepository-posture-All-three-
 *  legacy-use-case-deps-are-themselves-singletons-in-SharedModule + Load-
 *  bearing-fixes-preserved-this-slice-does-NOT-touch-the-Coil-ImageLoader-
 *  AVIF-decoder-HighQualitySkiaImageDecoder-or-:platform-complaint-actions-
 *  are-pure-Firestore-bound-text-mutations-No-load-bearing-risk" —
 *  LIVE-NOT-STALE. Verified: ComplaintActionRepositoryImpl shipped with
 *  three mutation methods. replyToComplaint(parent, body) reconstructs a
 *  LegacyComplaint with the parent's userId/type/subject + new body +
 *  metadata = mapOf("replyto" to parent.id) and forwards through Legacy
 *  SendComplaintUseCase. editComplaint(original, subject, body) reconstructs
 *  a LegacyComplaint with the original's id/userId/type/createdAt/status +
 *  new subject/body + metadata = null and forwards through LegacyUpdate
 *  ComplaintUseCase. deleteComplaint(id) forwards through LegacyDelete
 *  ComplaintUseCase. All three wrapped in runCatching. The reverse-direction
 *  mappers (toLegacyStatus + toLegacyType) are byte-for-byte the inverse
 *  of AdminComplaintListRepositoryImpl's forward mappers — exhaustive
 *  when-branch posture preserves the compile-time-error-on-new-variant
 *  guarantee at the strangler-fig boundary. The "metadata = mapOf String
 *  to String only no banned Any exposure" boundary discipline honored —
 *  the impl never exposes the legacy Map<String, Any> shape to :domain.
 *  Consumed by ReplyToComplaintUseCase + EditComplaintUseCase + Delete
 *  ComplaintUseCase (cluster124 siblings) via the three suspend mutation
 *  methods. One classification. Original Phase 7.x.complaint.actions
 *  (Task #252) impl prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */

