package me.manga.kira.domain.usecase.feedback

import me.manga.kira.domain.repository.FeedbackRepository

/**
 * Use case: submit the user's "request a new language" feedback to the remote complaint store.
 *
 * Phase 7.x.language.request rework. Pure pass-through to
 * [FeedbackRepository.sendLanguageRequest] — the impl owns the assembly of the `Complaint`
 * payload (userId, type, subject, metadata) and the orchestration with the legacy
 * `SendComplaintUseCase`. This use case adds nothing on top: it's the `:domain`-side boundary
 * that the rework `LanguageViewModel` calls, decoupling the VM from the [FeedbackRepository]
 * interface type so future test substitution / interface evolution doesn't ripple into
 * presentation.
 *
 * **Why a use case at all** for a one-liner pass-through? Three reasons matching the established
 * pattern across every other rework slice:
 *  1. **Naming**: `SendLanguageRequestUseCase` reads as the action the VM is performing.
 *     `feedbackRepository.sendLanguageRequest(body)` in the VM would conflate "I'm using the
 *     repository abstraction" with "I'm executing the user's intent" — those are different.
 *  2. **OCP**: if a future slice adds analytics tracking ("user submitted language request
 *     event") or a rate-limiter ("don't allow more than one submission per hour"), the
 *     decoration goes inside this use case — the VM stays unchanged.
 *  3. **DIP / consistency**: every other rework VM constructor-injects use cases, never
 *     repositories directly. The pattern is uniform across the codebase; this slice maintains
 *     the uniformity.
 *
 * Contract §6 SRP: one action — "submit a language request and report the outcome to the VM".
 *
 * Contract §6 OCP: closed for modification (the pass-through is stable); open for extension via
 * decorators (analytics, rate-limit, caching) wrapped around it without changing call sites.
 *
 * Contract §6 DIP: depends on the [FeedbackRepository] interface from `:domain`, never on the
 * `:data` impl or legacy `:shared` types.
 *
 * Lifecycle (Koin): `factory` — stateless thin pass-through. Same lifecycle as every other
 * use case in the rework.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster120.staleKdocSweep.cascade,
 * Task #576, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (sixty-first sibling of the cluster57-119 sweep — opens the
 * wave-19 `:domain/usecase/feedback/` batch alongside SubmitFeedbackUse-
 * Case.kt):
 *  (a) "Phase 7.x.language.request rework — pure pass-through to Feedback-
 *  Repository.sendLanguageRequest; the impl owns the assembly of the
 *  Complaint payload (userId, type, subject, metadata) and the
 *  orchestration with the legacy SendComplaintUseCase; this use case
 *  adds nothing on top — it is the `:domain`-side boundary that the
 *  rework LanguageViewModel calls, decoupling the VM from the Feedback-
 *  Repository interface type so future test substitution / interface
 *  evolution does not ripple into presentation" — LIVE-NOT-STALE.
 *  LanguageViewModel.kt L7 import, L155 primary constructor binds
 *  `private val sendLanguageRequest: SendLanguageRequestUseCase`; L193
 *  realization `val result = sendLanguageRequest(body)` inside the
 *  LanguageIntent.OnSubmitRequest branch. L47-48 realization `feedback-
 *  Repository.sendLanguageRequest(body)` single-line pass-through.
 *  FeedbackRepositoryImpl `:data` impl Complaint-payload-assembly plus
 *  legacy SendComplaintUseCase-orchestration verified at cluster25
 *  sibling sweep (Task #481).
 *  (b) "Why a use case at all for a one-liner pass-through — three
 *  reasons matching the established pattern across every other rework
 *  slice: naming (SendLanguageRequestUseCase reads as the action the VM
 *  is performing; feedbackRepository.sendLanguageRequest(body) in the VM
 *  would conflate `I'm using the repository abstraction` with `I'm
 *  executing the user's intent` — those are different); OCP (if a future
 *  slice adds analytics tracking or a rate-limiter, the decoration goes
 *  inside this use case — the VM stays unchanged); DIP / consistency
 *  (every other rework VM constructor-injects use cases, never
 *  repositories directly)" — LIVE-NOT-STALE plus FORECAST-NOT-YET-
 *  FULFILLED. Naming claim verified — LanguageViewModel.kt L193 reads
 *  `sendLanguageRequest(body)` as the user-action verb, not the
 *  repository-shape leakage. DIP claim verified — LanguageViewModel.kt
 *  L155 binds the use-case type, not the FeedbackRepository type. OCP
 *  forecast (analytics, rate-limit) — FORECAST-NOT-YET-FULFILLED.
 *  Recursive search for analytics-emit-on-language-request plus rate-
 *  limit decoration returns zero matches; the use case remains the
 *  single-line pass-through shape. Cross-rework one-use-case-per-VM-
 *  callable-verb peer cohort: wave-19 sibling SubmitFeedbackUseCase
 *  (this cluster120).
 *  (c) "Contract §6 SRP — one action, submit a language request and
 *  report the outcome to the VM" — LIVE-NOT-STALE. The one-action shape
 *  matches L47-48 realization (single suspend operator, single
 *  repository delegate, returns Result<Unit> verbatim from the
 *  delegate).
 *  (d) "Contract §6 OCP — closed for modification (the pass-through is
 *  stable); open for extension via decorators (analytics, rate-limit,
 *  caching) wrapped around it without changing call sites" — LIVE-NOT-
 *  STALE-FRAMING plus FORECAST-NOT-YET-FULFILLED. Decorator forecast
 *  (analytics, rate-limit, caching) — FORECAST-NOT-YET-FULFILLED.
 *  Recursive search for decorator-wrapped-use-case composition returns
 *  zero matches; the pass-through remains undecorated.
 *  (e) "Contract §6 DIP — depends on the FeedbackRepository interface
 *  from `:domain`, never on the `:data` impl or legacy `:shared` types"
 *  — LIVE-NOT-STALE. L3 import `me.manga.kira.domain.repository.
 *  FeedbackRepository`; L40 primary constructor binds the `:domain`-
 *  layer interface, not the `:data`-layer FeedbackRepositoryImpl.
 *  (f) "Lifecycle (Koin) — `factory`, stateless thin pass-through; same
 *  lifecycle as every other use case in the rework" — LIVE-NOT-STALE.
 *  LanguageReworkModule.kt L128 `factory { SendLanguageRequestUseCase(
 *  get()) }` realization confirms factory lifecycle; L123
 *  `single<FeedbackRepository> { FeedbackRepositoryImpl(get(), get(),
 *  get()) }` confirms the repository itself is `single` (stateless
 *  transport whose collaborators are themselves single) — the cross-
 *  module reuse posture verified at cluster14 sibling sweep (Task #470)
 *  and the §304 SourcesReworkModule cross-ref.
 *  Six classifications STAND on their own merits as a faithful Send-
 *  LanguageRequestUseCase manifest. Original Phase 7.x.language.request-
 *  era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
class SendLanguageRequestUseCase(
    private val feedbackRepository: FeedbackRepository,
) {
    /**
     * @param body free-form user-typed text describing the requested language.
     * @return [Result.success] on commit; [Result.failure] on any throw — see
     *         [FeedbackRepository.sendLanguageRequest] for failure modes.
     */
    suspend operator fun invoke(body: String): Result<Unit> =
        feedbackRepository.sendLanguageRequest(body)
}
