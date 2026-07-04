package me.manga.kira.domain.repository

import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType

/**
 * WRITE-only surface over admin-side complaint mutations — Status change, Closure reason, Delete,
 * Edit.
 *
 * Phase 7.x.complaint.admin.actions rework. Sibling to the user-side
 * [ComplaintActionRepository] (Phase 7.x.complaint.actions), and the WRITE counterpart of the
 * admin-side READ-only [AdminComplaintListRepository] (foundation slice).
 *
 * **Sibling vs extension (ISP §6)**: the user-side write repository covers reply / edit / delete
 * (act on the caller's own complaint). The admin write repository covers status-change /
 * closure-reason / delete (act on ANY complaint with admin privileges). Different mutation
 * methods, different scope semantics — two clean sibling interfaces. Forcing all 5 methods
 * (reply / edit / delete on user-side + status-change / closure-reason on admin-side) onto a
 * single interface would force every non-admin consumer to depend on admin methods they can't
 * use without elevated privileges, and vice versa.
 *
 * Same posture as the foundation slice's [AdminComplaintListRepository] sibling — that KDoc
 * explicitly predicted this slice's sibling-repo decision (foundation KDoc lines 27-32 of
 * `complaintAdminReworkModule`).
 *
 * The `:data` impl ([me.manga.kira.data.repository.AdminComplaintActionRepositoryImpl])
 * strangler-fig delegates to the legacy `:shared` use cases:
 *  - `:shared`/`UpdateComplaintUseCase` — backs [changeStatus] (writes a new status to the
 *    document) AND [addClosureReason] (writes status + metadata in one update).
 *  - `:shared`/`DeleteComplaintUseCase` — backs [deleteComplaint].
 *  - `:shared`/`UserIdProvider` — provides the admin's userId for the closure-reason
 *    `reasonAddedBy` metadata field (mirrors legacy `AdminComplaintViewModel.addClosureReason`
 *    line 153).
 *
 * Same strangler-fig posture as [ComplaintActionRepository] (user-side write) and
 * [ComplaintListRepository] / [FeedbackRepository] / [LanguageRepository] (other rework
 * strangler-fig sites) — retired by Phase 9.x route-swap.
 *
 * Contract §6 SRP: ONE rule — "submit an admin-side complaint mutation and report
 * success/failure". No reads (those live on [AdminComplaintListRepository]); no derivation;
 * no orchestration of multi-step flows (each method is one Firestore round-trip).
 *
 * Contract §6 ISP: four methods, one per mutation (status-change / closure-reason / delete /
 * edit). Could fatter to a single `submitAction(action: AdminComplaintAction)` polymorphic shape,
 * but the explicit per-mutation surface is exhaustive at the consumer site (the VM's intent
 * handler) and reads better.
 *
 * Contract §6 DIP: the consumers (the 3 admin use cases
 * [me.manga.kira.domain.usecase.complaint.ChangeComplaintStatusUseCase],
 * [me.manga.kira.domain.usecase.complaint.AddClosureReasonUseCase],
 * [me.manga.kira.domain.usecase.complaint.AdminDeleteComplaintUseCase]) depend on this
 * interface, never on the legacy facade or Firestore directly.
 *
 * Lifecycle expectation: `single` (same as [ComplaintActionRepository] /
 * [AdminComplaintListRepository]) — stateless transport whose collaborators are themselves
 * singletons.
 *
 * **Result semantics**:
 *  - [Result.success] — Firestore write returned. The admin list is NOT updated by this call;
 *    the VM refires the load (via [me.manga.kira.domain.usecase.complaint.ObserveAllComplaintsUseCase])
 *    on every success.
 *  - [Result.failure] — any failure: network, Firestore permission, legacy `require()`
 *    validation, deserialization, etc. The caller surfaces a single error snackbar regardless
 *    of cause.
 *
 * **Behaviour preservation vs legacy**: this slice reads from / writes to the SAME Firestore
 * `complaints` collection the legacy `AdminComplaintViewModel.updateComplaintStatus /
 * .addClosureReason / .deleteComplaint` consults. User-side reads (rework or legacy `Complaint`
 * route) and admin reads (rework or legacy `ComplaintAdmin` route) see the same documents.
 *
 * **Edit method ([editComplaint])**: added in Phase 7.x.complaint.admin.edit — symmetric admin
 * counterpart of the user-side [ComplaintActionRepository.editComplaint]. Both sides now PRESERVE
 * the legacy `metadata` field across the write (closure-reason / `reasonAddedBy` / `reasonAddedAt`
 * audit fields survive subject/body edits) — user-side was fixed by #9 to re-fetch and inherit the
 * full map. The remaining admin-side distinction is that admin edit may also re-categorize the
 * complaint `type`. The impl reuses the existing `fetchLegacyById` helper to read the full legacy
 * record, then mutates type + subject + body before writing back.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster7.staleKdocSweep.cascade,
 * Task #463, 2026-05-28): three stale citations into the §366-retired
 * legacy `:shared/.../admin/complaint/AdminComplaintViewModel.kt` appear
 * above (plus three more in the per-method KDocs below):
 *  - Lines 31-32 (`UserIdProvider` collaborator bullet): "mirrors legacy
 *    `AdminComplaintViewModel.addClosureReason` line 153".
 *  - Lines 65-67 ("Behaviour preservation vs legacy" para): "this slice
 *    reads from / writes to the SAME Firestore `complaints` collection
 *    the legacy `AdminComplaintViewModel.updateComplaintStatus /
 *    .addClosureReason / .deleteComplaint` consults".
 *  - Line 103 ([addClosureReason] method KDoc opener): "Legacy parity:
 *    `AdminComplaintViewModel.addClosureReason` (lines 139-190)".
 * The legacy `:shared/.../admin/complaint/AdminComplaintViewModel.kt`
 * was retired in Phase 9.x.admincomplaint.retire (§366 sweep, commit
 * `48a5c2b` "(1/2): delete orphan legacy admin VM + screen + 2 helpers +
 * drop Koin binding"); verified by a filesystem check returning zero
 * hits for that path. The interface-surface design (4-method write
 * repository: changeStatus / addClosureReason / deleteComplaint /
 * editComplaint) + metadata-preservation-on-admin-edit semantics + same-
 * upstream-Firestore-collection wire-compatibility rationale all stand
 * on their own merits — the `:data` impl
 * [me.manga.kira.data.repository.AdminComplaintActionRepositoryImpl]
 * continues to delegate to the legacy `:shared` use cases
 * (`UpdateComplaintUseCase` / `DeleteComplaintUseCase` / `UserIdProvider`)
 * which all REMAIN LIVE post-§366 retire; only the legacy VM that
 * previously orchestrated them was the unreachable orphan. The line-
 * number anchors ("line 153", "lines 139-190") are historical record of
 * the orchestration survey captured before the §366 retire; the
 * authoritative spec post-retire is the inline closure-reason metadata
 * write described in the [addClosureReason] method KDoc (writes
 * `metadata["reason"]` / `metadata["reasonAddedBy"]` / `metadata
 * ["reasonAddedAt"]`, auto-flips OPEN/IN_PROGRESS → CLOSED). Original
 * §253-era prose preserved verbatim per the audit-trail-preservation
 * convention — the citations are historical record of the design
 * lineage; the rework AdminComplaintActionRepository contract continues
 * to surface the documented admin-side write affordances past the §366
 * retire.
 */
interface AdminComplaintActionRepository {

    /**
     * Change the status of [complaint] to [newStatus].
     *
     * The impl reconstructs a full legacy `Complaint` from [complaint] (need to preserve all
     * non-status fields including `userId`, `type`, `subject`, `body`, `createdAt`,
     * `metadata`) with the new [newStatus], then calls `legacy.updateComplaint(complaint)`.
     *
     * **Metadata preservation**: the admin status-change preserves metadata to keep the
     * closure-reason `reason` / `reasonAddedBy` / `reasonAddedAt` fields intact across status
     * flips (both sides now preserve metadata — user-side edit was fixed by #9 to re-fetch and
     * inherit the full map). The impl carries the legacy `metadata: Map<String, Any>?` across
     * without leaking `Any` into this `:domain` boundary (same posture as
     * [ComplaintActionRepository]'s reply metadata).
     *
     * Concurrency: `suspend` — legacy `UpdateComplaintUseCase.invoke` is suspend (Firestore
     * write).
     *
     * @return [Result.success] on Firestore write OK; [Result.failure] on any throw.
     */
    suspend fun changeStatus(complaint: ComplaintSummary, newStatus: ComplaintStatus): Result<Unit>

    /**
     * Add a closure [reason] to [complaint]'s metadata and auto-set status to CLOSED when the
     * current status is OPEN or IN_PROGRESS.
     *
     * Legacy parity: `AdminComplaintViewModel.addClosureReason` (lines 139-190).
     *  - Stores `reason` in `metadata["reason"]` (overwriting any existing value).
     *  - Stores admin user id in `metadata["reasonAddedBy"]`.
     *  - Stores epoch ms in `metadata["reasonAddedAt"]`.
     *  - If [complaint.status] is OPEN or IN_PROGRESS → flips to CLOSED. Otherwise preserves
     *    current status (RESOLVED / CLOSED / PINNED / PLANNED / NOT_PLANNED / UNKNOWN all
     *    untouched).
     *
     * The impl reads the admin user id from the legacy `UserIdProvider` (injected at
     * construction).
     *
     * Concurrency: `suspend` — legacy `UpdateComplaintUseCase.invoke` is suspend.
     *
     * @return [Result.success] on Firestore write OK; [Result.failure] on any throw including
     *   missing admin userId.
     */
    suspend fun addClosureReason(complaint: ComplaintSummary, reason: String): Result<Unit>

    /**
     * Delete the complaint with the given [id].
     *
     * Identical wire behaviour to the user-side [ComplaintActionRepository.deleteComplaint] —
     * both bind to the legacy `:shared` `DeleteComplaintUseCase`. The split is intentional
     * (ISP §6): the user-side delete is gated to the caller's userId by Firestore rules; the
     * admin delete is gated client-side by [me.manga.kira.admin.Admin.isAdmin] at the
     * navigation entry. Same wire, different consumer pressure.
     *
     * Concurrency: `suspend` — legacy `DeleteComplaintUseCase.invoke` is suspend.
     *
     * @return [Result.success] on Firestore delete OK; [Result.failure] on any throw.
     */
    suspend fun deleteComplaint(id: String): Result<Unit>

    /**
     * Edit the [type], [subject], and [body] of an existing complaint.
     *
     * Phase 7.x.complaint.admin.edit. Symmetric admin counterpart of the user-side
     * [ComplaintActionRepository.editComplaint] (Phase 7.x.complaint.actions).
     *
     * **Type is admin-mutable**: unlike the user-side edit (which preserves the original type),
     * the admin edit may re-categorize the complaint via [type]. Native parity — the native
     * `EditComplaintDialog` exposes a Type dropdown and submits
     * `complaint.copy(type = selectedType, subject = …, body = …)`.
     *
     * **Metadata preservation** (admin-side distinct): the impl re-fetches the full legacy
     * `Complaint` via the injected `LegacyGetAllComplaintUseCase` and mutates [type] + [subject] +
     * [body], preserving:
     *  - `userId` / `createdAt` / `status` (same as user-side edit).
     *  - `metadata` (closure-reason / `reasonAddedBy` / `reasonAddedAt`) — both sides preserve
     *    metadata now (user-side via the #9 by-id re-fetch). Preservation is load-bearing here
     *    because admin-edit on a CLOSED complaint must NOT erase the audit trail set by a prior
     *    `addClosureReason`.
     *
     * **Why pass [original]**: the legacy `UpdateComplaintUseCase` takes a full `Complaint`
     * record. Without the full identity-providing fields the write would either need an extra
     * round-trip (already paid by `fetchLegacyById`) or risk an id mismatch. The caller (rework
     * VM) already has the full [ComplaintSummary] from the list state — pass it through.
     *
     * **Validation policy**: this method does NOT pre-validate. Validation is the UI's job (the
     * rework dialog enforces non-blank subject + non-blank body + ≤ 1000 chars before enabling
     * Save). Same posture as the user-side method.
     *
     * Concurrency: `suspend` — one legacy READ via `LegacyGetAllComplaintUseCase` then one
     * legacy WRITE via `LegacyUpdateComplaintUseCase`.
     *
     * @return [Result.success] on Firestore write OK; [Result.failure] on any throw (network,
     *   permission, missing-by-id).
     */
    suspend fun editComplaint(
        original: ComplaintSummary,
        type: ComplaintType,
        subject: String,
        body: String,
    ): Result<Unit>
}
