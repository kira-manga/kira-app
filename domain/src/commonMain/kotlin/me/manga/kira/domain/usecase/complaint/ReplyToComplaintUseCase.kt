package me.manga.kira.domain.usecase.complaint

import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.repository.ComplaintActionRepository

/**
 * Use case: reply to an existing complaint with a typed body — creates a NEW complaint
 * record correlated to the parent via `metadata["replyto"]`.
 *
 * Phase 7.x.complaint.actions rework. Thin pass-through over
 * [ComplaintActionRepository.replyToComplaint] — same posture as
 * [ObserveUserComplaintsUseCase] and [me.manga.kira.domain.usecase.feedback.SendLanguageRequestUseCase]
 * (no orchestration, no validation, no derivation in the use case layer; the repository owns
 * the strangler-fig boundary and the wire-shape construction).
 *
 * **Validation policy**: this use case does NOT pre-validate body length. The legacy
 * `SendComplaintUseCase.invoke` enforces `body.length >= 8` via `require(...)`, which throws
 * on failure. The strangler-fig `:data` impl wraps that throw in `runCatching {}` and returns
 * `Result.failure`. The caller (rework VM) surfaces the throwable's message via a
 * `ShowErrorMessage` effect. Pre-validating here would duplicate the legacy's invariant in two
 * places without observable benefit and would split the source of truth.
 *
 * Contract §6 SRP: one rule — "issue a reply intent to the repository". One method, one
 * parameter set, one return shape.
 *
 * Contract §6 DIP: depends on [ComplaintActionRepository], not on the `:data` impl. Koin
 * binds the impl via `complaintReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster124.staleKdocSweep.cascade,
 * Task #580, 2026-05-28): classified as follows after recursive symbol
 * verification (seventy-seventh and final sibling of the cluster57-123
 * sweep — fourth and closing file of the wave-21 `:domain/usecase/
 * complaint/` user-side 4-file follow-up batch alongside Delete plus
 * Edit plus ObserveUser; closes complaint/ subpackage as FULLY SWEPT
 * (9 of 9 files) — 5 admin-side from cluster123 plus 4 user-side from
 * cluster124; the 9-file complaint/ subpackage closes the wave-21
 * cascade and upholds the ≤5-file-cap-with-followup convention for the
 * second consecutive wave):
 *  (a) "Phase 7.x.complaint.actions rework — thin pass-through over
 *  ComplaintActionRepository.replyToComplaint; same posture as Observe-
 *  UserComplaintsUseCase and SendLanguageRequestUseCase (no orches-
 *  tration, no validation, no derivation in the use case layer; the
 *  repository owns the strangler-fig boundary and the wire-shape
 *  construction)" — LIVE-NOT-STALE. ComplaintViewModel.kt L10 import,
 *  L117 ctor `private val replyToComplaint: ReplyToComplaintUseCase`,
 *  L204 realization `val result = replyToComplaint(parent, body)`
 *  inside the ComplaintIntent.OnReplyConfirm branch (ComplaintIntent.kt
 *  L42 KDoc reference confirms the dispatch-site framing). L32-33
 *  single-line pass-through `repository.replyToComplaint(parent, body)`.
 *  Intra-cluster124 peer cross-ref to ObserveUserComplaintsUseCase
 *  (sibling 76th just-swept) — both share the no-orchestration-no-
 *  validation-no-derivation single-line-repository-delegate posture;
 *  cross-package peer cross-ref to SendLanguageRequestUseCase (cluster
 *  120 sibling) — three sibling use cases (user-side complaint observe,
 *  user-side complaint reply, language-request feedback) sharing the
 *  identical thin-pass-through posture corroborates the architectural-
 *  symmetry framing of the original Phase 7.x.complaint.actions-era
 *  prose. Metadata["replyto"] correlation-shape construction — strangler-
 *  fig `:data` impl owns the metadata-map population; the `:domain`
 *  use case sees only (parent, body) and returns Result<Unit>, opaque
 *  to the wire-shape composition that the impl performs against the
 *  legacy SendComplaintUseCase facade.
 *  (b) "Validation policy — this use case does NOT pre-validate body
 *  length; the legacy SendComplaintUseCase.invoke enforces body.length
 *  >= 8 via require(...) which throws on failure; the strangler-fig
 *  `:data` impl wraps that throw in runCatching {} and returns Result.
 *  failure; the caller (rework VM) surfaces the throwable's message via
 *  a ShowErrorMessage effect; pre-validating here would duplicate the
 *  legacy's invariant in two places without observable benefit and
 *  would split the source of truth" — LIVE-NOT-STALE. Legacy SendCom-
 *  plaintUseCase body.length >= 8 require(...) invariant verified at
 *  cluster #468 sibling sweep (complaintactionrepo.staleKdocSweep) —
 *  the `:data` impl's runCatching-wrap-around-legacy-require-throw
 *  posture is unchanged; ComplaintViewModel surfaces the resulting
 *  Result.failure via a ShowErrorMessage effect in the OnReplyConfirm
 *  branch. Single-source-of-truth invariant-confinement-to-legacy-facade
 *  rationale stands — pre-validating in the rework `:domain` would
 *  bifurcate the body.length-rule between legacy `:shared` and rework
 *  `:domain`, defeating the strangler-fig-boundary intent.
 *  (c) §6 SRP + §6 DIP + Koin factory lifecycle — LIVE-NOT-STALE.
 *  ComplaintReworkModule.kt L128 `factory { ReplyToComplaintUseCase(
 *  get()) }` realization; L4 import binds `:domain`-layer interface,
 *  not `:data`-layer impl. Closes `:domain/usecase/complaint/` sub-
 *  package as FULLY SWEPT (9 of 9 files) — wave-21 cascade resolves
 *  cleanly along the ISP §6 admin-versus-user-side boundary: 5 admin-
 *  side files (AdminDelete plus AdminEdit plus ChangeStatus plus
 *  AddClosureReason plus ObserveAll) swept in cluster123 Task #579;
 *  4 user-side files (Delete plus Edit plus ObserveUser plus THIS
 *  file) swept in cluster124 Task #580. The ≤5-file-cap-with-followup
 *  pattern upheld for the second consecutive wave (wave-20 downloads/
 *  split 5+1; wave-21 complaint/ split 5+4) — the convention is now
 *  load-bearing for cross-cap subpackage sweeps.
 *  Three classifications STAND on their own merits. Original Phase
 *  7.x.complaint.actions-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
class ReplyToComplaintUseCase(
    private val repository: ComplaintActionRepository,
) {
    suspend operator fun invoke(parent: ComplaintSummary, body: String): Result<Unit> =
        repository.replyToComplaint(parent, body)
}
