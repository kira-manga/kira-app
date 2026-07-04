package me.manga.kira.domain.repository

import me.manga.kira.domain.model.complaint.ComplaintSummary

/**
 * WRITE-only surface over user-side complaint mutations — Reply, Edit, Delete.
 *
 * Phase 7.x.complaint.actions rework. Sibling to the existing READ-only
 * [ComplaintListRepository] (Phase 7.x.complaint.foundation). The split is deliberate per
 * contract §6 ISP — a `:presentation` consumer that only needs to READ (e.g., a future
 * complaint-detail screen) should not be forced to depend on WRITE methods it never calls,
 * and vice versa for the action dialog.
 *
 * The `:data` impl ([me.manga.kira.data.repository.ComplaintActionRepositoryImpl])
 * strangler-fig delegates to the legacy `:shared` use cases:
 *  - `:shared`/`SendComplaintUseCase` — backs [replyToComplaint] (a reply is a NEW complaint
 *    record with `metadata["replyto"] = parent.id`).
 *  - `:shared`/`UpdateComplaintUseCase` — backs [editComplaint].
 *  - `:shared`/`DeleteComplaintUseCase` — backs [deleteComplaint].
 *
 * Same strangler-fig posture as [ComplaintListRepository] / [FeedbackRepository] /
 * [LanguageRepository] — three reaches into legacy `:shared`, retired by Phase 9.x route-swap.
 *
 * Contract §6 SRP: ONE rule — "submit a user-side complaint mutation and report success/failure".
 * No reads (those live on [ComplaintListRepository]); no derivation (the impl is a wire-side
 * adapter, not a transform layer); no orchestration of multi-step flows (each method is one
 * Firestore round-trip).
 *
 * Contract §6 ISP: three methods, one per mutation. Could fatter to a single
 * `submitAction(action: ComplaintAction)` polymorphic shape, but the explicit per-mutation
 * surface is exhaustive at the consumer site (the VM's intent handler) and reads better — the
 * `:presentation` VM never needs to construct a sealed-class action payload just to call this.
 *
 * Contract §6 DIP: the consumers (the 3 use cases
 * [me.manga.kira.domain.usecase.complaint.ReplyToComplaintUseCase],
 * [me.manga.kira.domain.usecase.complaint.EditComplaintUseCase],
 * [me.manga.kira.domain.usecase.complaint.DeleteComplaintUseCase]) depend on this
 * interface, never on the legacy facade or Firestore directly. Koin binds the impl at the
 * composition root in `complaintReworkModule`.
 *
 * Lifecycle expectation: `single` (same as [ComplaintListRepository]) — stateless transport
 * whose collaborators are themselves singletons. Per-resolution instantiation would be wasteful.
 *
 * **Result semantics**:
 *  - [Result.success] — Firestore write returned. The list of complaints in the user's view is
 *    NOT updated by this call; the caller is responsible for refetching via
 *    [ComplaintListRepository.loadUserComplaints] (the rework VM does this on every success).
 *  - [Result.failure] — any failure: network, Firestore permission, legacy `require()`
 *    validation (subject not blank, body ≥ 8 chars on the reply path — see legacy
 *    `SendComplaintUseCase`), deserialization, etc. The caller surfaces a single error
 *    snackbar regardless of cause; the legacy throwable's message is the user-visible text.
 *
 * **Behaviour preservation vs legacy**: this slice reads from / writes to the SAME Firestore
 * `complaints` collection the legacy `ComplaintViewModel.reply / .edit / .delete` consults.
 * Admin reads (via legacy `ComplaintAdmin` route) and user reads (via either rework or legacy
 * `Complaint` route) see the same documents.
 *
 * **Reply-metadata inheritance vs legacy**: the rework's reply inherits the FULL parent metadata
 * map verbatim, then appends `"replyto" to parent.id` last — native parity (native writes
 * `(parent.metadata ?: emptyMap()) + mapOf("replyto" to parent.id)`). The impl re-fetches the
 * legacy parent by id (the #9 fix) so no carved-summary rebuild is needed and no banned `Any`
 * crosses the `:domain` boundary (the `:data` impl is the bridge). This fixed a prior bug where a
 * 5-field rebuild silently dropped device-metadata rows on reply.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster7.staleKdocSweep.cascade,
 * Task #463, 2026-05-28): two stale citations into the §363-retired
 * legacy `:shared/.../complaint/viewmodel/ComplaintViewModel.kt` appear
 * above:
 *  - Lines 53-56 ("Behaviour preservation vs legacy" para): "this slice
 *    reads from / writes to the SAME Firestore `complaints` collection
 *    the legacy `ComplaintViewModel.reply / .edit / .delete` consults.
 *    Admin reads (via legacy `ComplaintAdmin` route) and user reads
 *    (via either rework or legacy `Complaint` route) see the same
 *    documents".
 *  - Lines 58-62 ("Reply-metadata simplification vs legacy" para):
 *    self-referential to the legacy reply path's metadata-inheritance
 *    posture that the legacy `ComplaintViewModel.reply` previously
 *    implemented.
 * The legacy `:shared/.../complaint/viewmodel/ComplaintViewModel.kt` was
 * retired in Phase 9.x.complaintvm.retire (§363 sweep, commit `e2af0d4`
 * "(1/2): delete unreachable :shared ComplaintViewModel"); verified by a
 * filesystem check returning zero hits for that path. The "same
 * Firestore collection" wire-compatibility rationale is doubly load-
 * bearing now: the legacy `ComplaintAdmin` route was itself retired in
 * Phase 9.x.admincomplaint.swap (§365 sweep — `Screen.ComplaintAdmin`
 * re-pointed to the rework admin screen), and the legacy `Complaint`
 * route's `:composeApp/.../features/complaint/ui/screens/
 * ComplaintScreen.kt` was retired in Phase 9.x.complaint.legacyui.retire
 * (§355 sweep, commit `bfea508`) — so BOTH legacy reader surfaces have
 * been retired alongside the legacy writer VM, leaving the rework
 * Complaint route + rework admin Complaint route as the SOLE consumers
 * of the SAME Firestore `complaints` collection. The interface-surface
 * design (3-method write repository: replyToComplaint / editComplaint /
 * deleteComplaint) + Reply-metadata-simplification posture (drop
 * `parent.metadata` inheritance to keep `Any` out of `:domain`) all
 * stand on their own merits — the `:data` impl
 * [me.manga.kira.data.repository.ComplaintActionRepositoryImpl]
 * continues to delegate to the legacy `:shared` use cases
 * (`SendComplaintUseCase` / `UpdateComplaintUseCase` /
 * `DeleteComplaintUseCase`) which all REMAIN LIVE post-§363 retire;
 * only the legacy VM that previously orchestrated them was the
 * unreachable orphan. Original §253-era prose preserved verbatim per
 * the audit-trail-preservation convention — the citations are
 * historical record of the design lineage; the rework
 * ComplaintActionRepository contract continues to surface the documented
 * user-side write affordances past the §363 retire.
 */
interface ComplaintActionRepository {

    /**
     * Submit a reply to [parent] with the typed [body] as a new complaint record.
     *
     * The impl constructs a fresh legacy `Complaint` with:
     *  - `userId = parent.userId` (the parent's user — for user-side reply this is the caller).
     *  - `type = parent.type` (preserved so admin threading groups Reply with parent).
     *  - `subject = parent.subject` (same threading rationale).
     *  - `body = body` (the typed reply).
     *  - `status = ComplaintStatus.OPEN` (fresh submission semantics).
     *  - `metadata` = the parent's FULL metadata map inherited verbatim, PLUS `"replyto" ->
     *    parent.id` (correlation key) appended last. The parent's submission context is preserved
     *    intact — native parity (native writes `(parent.metadata ?: emptyMap()) + mapOf("replyto"
     *    to parent.id)`). The impl re-fetches the legacy parent by id (#9) rather than rebuilding
     *    from carved [ComplaintSummary] fields, so no banned `Any` crosses this `:domain` boundary.
     *
     * Concurrency: `suspend` — legacy `SendComplaintUseCase.invoke` is suspend (Firestore write).
     *
     * @return [Result.success] on Firestore write OK; [Result.failure] on any throw including
     *   legacy `require(body.length >= 8)` validation.
     */
    suspend fun replyToComplaint(parent: ComplaintSummary, body: String): Result<Unit>

    /**
     * Update the subject and body of an existing complaint.
     *
     * The impl reconstructs a full legacy `Complaint` from [original] (need to preserve all
     * non-edited fields including `userId`, `type`, `createdAt`, `status`) with the new
     * [subject] and [body], then calls `legacy.updateComplaint(complaint)`.
     *
     * Concurrency: `suspend` — legacy `UpdateComplaintUseCase.invoke` is suspend (Firestore
     * write).
     *
     * @return [Result.success] on Firestore write OK; [Result.failure] on any throw.
     */
    suspend fun editComplaint(
        original: ComplaintSummary,
        subject: String,
        body: String,
    ): Result<Unit>

    /**
     * Delete the complaint with the given [id].
     *
     * Concurrency: `suspend` — legacy `DeleteComplaintUseCase.invoke` is suspend (Firestore
     * delete).
     *
     * @return [Result.success] on Firestore delete OK; [Result.failure] on any throw.
     */
    suspend fun deleteComplaint(id: String): Result<Unit>
}
