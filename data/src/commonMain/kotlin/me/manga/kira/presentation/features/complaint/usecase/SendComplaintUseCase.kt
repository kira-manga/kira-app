package me.manga.kira.presentation.features.complaint.usecase

import me.manga.kira.presentation.features.complaint.model.Complaint
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository

class SendComplaintUseCase(
    private val repo: ComplaintRepository,
) {
    suspend operator fun invoke(complaint: Complaint): String {
        require(complaint.subject.isNotBlank()) { "Subject must not be empty" }
        // Native-parity (GAP-SET-11 / GAP-SRC-01): the OLD native FeedbackDialog gated the body at
        // >= 5 chars end-to-end, and both rework UIs (Settings Feedback + Sources request) gate at 5.
        // The prior >= 8 floor silently rejected 5–7 char bodies that passed the UI — a parity break.
        require(complaint.body.length >= MIN_BODY_LENGTH) { "Body too short" }
        return repo.sendComplaint(complaint)
    }

    companion object {
        /** Native-parity minimum body length (OLD FeedbackDialog gated at 5; both rework UIs gate at 5). */
        const val MIN_BODY_LENGTH = 5
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster200.staleKdocSweep.cascade, Task #656, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster200 leaf 4/5 — :shared/complaint/usecase/ tier midbody, sibling 355.
 *
 * File-shape note: 14-line use-case class with INPUT VALIDATION inside `invoke()` — the only
 * file in the 5-file cohort that does more than thin pass-through. Two `require(...)`
 * preconditions: subject non-blank + body ≥ 8 chars. Returns `String` (the generated
 * complaint ID from the legacy repository). Sole non-trivial-body member of the cohort.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — strangler-fig SOURCE for both user-complaint create AND feedback
 *     submission. Injected as `LegacySendComplaintUseCase` at:
 *       - data/.../ComplaintActionRepositoryImpl.kt L11 (user complaints — Reply / Edit flows)
 *       - data/.../FeedbackRepositoryImpl.kt L11 (feedback dialog — complaint-as-feedback
 *         strangler-fig pattern, where the SettingsScreen Feedback button piggybacks on the
 *         complaint pipeline via a synthetic `ComplaintType.FEEDBACK` value)
 *     Two-consumer pair use; verified at cluster153 sibling sweep (:data/repository complaint
 *     trio). Koin binding in SharedModule.kt L15.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 2 imports: legacy `Complaint` model + legacy
 *     `ComplaintRepository` interface.
 *
 *   • LIVE-NOT-STALE (validation invariants) — the two `require(...)` checks are CONTRACT
 *     INVARIANTS preserved through the rework. `ComplaintActionRepositoryImpl.sendReply`
 *     does NOT re-validate the subject/body length itself; it depends on this use-case's
 *     validation gate. Moving validation out would silently relax the contract — DO NOT
 *     refactor the validation into the repository layer during the upcoming architecture
 *     rework without first lifting these `require` calls to the rework `:domain` layer.
 *
 *   • PARALLEL-CLASS-DRIFT — the rework has NO same-named successor at :domain/.../usecase/
 *     complaint/ — there is no `SendComplaintUseCase.kt` in :domain. Instead, the rework
 *     :data impls call `LegacySendComplaintUseCase` directly through Koin-injected strangler-
 *     fig wiring. Different posture from siblings 352 / 354 (which have parallel rework
 *     same-named or renamed siblings). The send-path's mutation semantics + validation
 *     posture made it cheaper to wrap-not-rewrite during cluster124. INVERTED-PARALLEL
 *     pattern — legacy stays load-bearing while rework consumes it directly.
 *
 *   • DOC-LACUNA — no KDoc header (same shape-stripped state as siblings 352 + 353 + 356).
 *
 *   • FULFILLED-PORT (vacuously) — Phase 4 @Inject removal axis applies.
 */
