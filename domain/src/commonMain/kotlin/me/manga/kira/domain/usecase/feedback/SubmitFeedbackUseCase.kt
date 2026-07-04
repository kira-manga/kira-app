package me.manga.kira.domain.usecase.feedback

import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.FeedbackRepository

/**
 * Use case: submit a generic user feedback complaint (type + subject + body) to the remote
 * complaint store.
 *
 * Phase 7.x.settings.feedback rework. Pure pass-through to
 * [FeedbackRepository.submit] — the impl owns the `Complaint` payload assembly (userId,
 * metadata, timestamp, status) and the orchestration with the legacy `SendComplaintUseCase`.
 * This use case adds nothing on top: it's the `:domain`-side boundary that the rework
 * [me.manga.kira.presentation.settings.SettingsViewModel] calls, decoupling the VM from the
 * [FeedbackRepository] interface type so future test substitution / interface evolution doesn't
 * ripple into presentation.
 *
 * **Why a use case at all** for a one-liner pass-through? Three reasons matching the established
 * pattern across every other rework slice and sibling [SendLanguageRequestUseCase]:
 *  1. **Naming**: `SubmitFeedbackUseCase` reads as the action the VM is performing.
 *     `feedbackRepository.submit(type, subject, body)` in the VM would conflate "I'm using the
 *     repository abstraction" with "I'm executing the user's intent" — those are different.
 *  2. **OCP**: if a future slice adds analytics tracking ("user submitted feedback event") or a
 *     rate-limiter ("don't allow more than one submission per hour"), the decoration goes
 *     inside this use case — the VM stays unchanged.
 *  3. **DIP / consistency**: every other rework VM constructor-injects use cases, never
 *     repositories directly. The pattern is uniform across the codebase; this slice maintains
 *     the uniformity.
 *
 * Contract §6 SRP: one action — "submit a generic feedback complaint and report the outcome to
 * the VM".
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
 * KMP graph (sixty-second sibling of the cluster57-119 sweep — closes the
 * wave-19 `:domain/usecase/feedback/` batch alongside SendLanguageRequest-
 * UseCase.kt):
 *  (a) "Phase 7.x.settings.feedback rework — pure pass-through to
 *  FeedbackRepository.submit; the impl owns the Complaint payload
 *  assembly (userId, metadata, timestamp, status) and the orchestration
 *  with the legacy SendComplaintUseCase; this use case adds nothing on
 *  top — it is the `:domain`-side boundary that the rework SettingsView-
 *  Model calls, decoupling the VM from the FeedbackRepository interface
 *  type so future test substitution / interface evolution does not
 *  ripple into presentation" — LIVE-NOT-STALE plus MIXED LIVE-PLUS-
 *  UNDERSPECIFIED-CONSUMER-SET. SettingsViewModel.kt L9 import, L148
 *  primary constructor binds `private val submitFeedback: SubmitFeedback-
 *  UseCase`; L227 realization `val result = submitFeedback(type = type,
 *  subject = type.name, body = body)` inside the Settings feedback-
 *  dialog branch. The original framing names SettingsViewModel as the
 *  sole consumer — that is now UNDERSPECIFIED. The use case is ALSO
 *  consumed by SourcesViewModel.kt L8 import, L137 ctor binding `private
 *  val submitFeedback: SubmitFeedbackUseCase`, L177 realization `val
 *  result = submitFeedback(type = type, subject = type.name, body =
 *  body)` (Phase 7.x.sources.complaint Request-adding-source dialog with
 *  pinned ComplaintType.SITES_ADD type — verified at cluster108 sibling
 *  sweep Task #564 SourcesIntent.OnSubmitSourceRequest framing). The use
 *  case has been cross-feature-promoted into a two-consumer surface
 *  without the file-scope KDoc reflecting the second consumer.
 *  FeedbackRepositoryImpl `:data` impl Complaint-payload-assembly plus
 *  legacy SendComplaintUseCase-orchestration verified at cluster25
 *  sibling sweep (Task #481).
 *  (b) "Why a use case at all for a one-liner pass-through — three
 *  reasons matching the established pattern across every other rework
 *  slice and sibling SendLanguageRequestUseCase: naming, OCP (analytics
 *  + rate-limiter decoration); DIP / consistency (every other rework VM
 *  constructor-injects use cases, never repositories directly)" — LIVE-
 *  NOT-STALE plus FORECAST-NOT-YET-FULFILLED. Naming claim verified —
 *  SettingsViewModel.kt L227 plus SourcesViewModel.kt L177 both read
 *  `submitFeedback(...)` as the user-action verb. DIP claim verified —
 *  both VM ctor sites bind the use-case type, not the FeedbackRepository
 *  type. Sibling cross-ref to SendLanguageRequestUseCase (this cluster120,
 *  just-edited) holds as architectural-symmetry peer. OCP forecast
 *  (analytics emit, rate-limit) — FORECAST-NOT-YET-FULFILLED. Recursive
 *  search for analytics-emit-on-feedback-submit plus rate-limit
 *  decoration returns zero matches; the use case remains the single-
 *  line pass-through shape.
 *  (c) "Contract §6 SRP — one action, submit a generic feedback
 *  complaint and report the outcome to the VM" — LIVE-NOT-STALE. L53-54
 *  realization (single suspend operator, single repository delegate,
 *  returns Result<Unit> verbatim from the delegate). The "report to the
 *  VM" portion now serves TWO VMs as documented in (a).
 *  (d) "Contract §6 OCP — closed for modification (the pass-through is
 *  stable); open for extension via decorators (analytics, rate-limit,
 *  caching) wrapped around it without changing call sites" — LIVE-NOT-
 *  STALE-FRAMING plus FORECAST-NOT-YET-FULFILLED. Decorator forecast
 *  (analytics, rate-limit, caching) — FORECAST-NOT-YET-FULFILLED.
 *  Recursive search returns zero matches; the pass-through remains
 *  undecorated.
 *  (e) "Contract §6 DIP — depends on the FeedbackRepository interface
 *  from `:domain`, never on the `:data` impl or legacy `:shared` types"
 *  — LIVE-NOT-STALE. L4 import `me.manga.kira.domain.repository.
 *  FeedbackRepository`; L43 primary constructor binds the `:domain`-
 *  layer interface, not the `:data`-layer FeedbackRepositoryImpl.
 *  (f) "Lifecycle (Koin) — `factory`, stateless thin pass-through; same
 *  lifecycle as every other use case in the rework" — LIVE-NOT-STALE.
 *  SettingsReworkModule.kt L126 `factory { SubmitFeedbackUseCase(get())
 *  }` realization confirms factory lifecycle; the cross-module-reuse
 *  posture (FeedbackRepository singleton bound in languageReworkModule
 *  at L123, consumed by SettingsReworkModule.kt L126 plus SourcesRework-
 *  Module factory binding for the SOURCES_ADD consumer) verified at
 *  cluster14 sibling sweep (Task #470 — SourcesReworkModule §304 plus
 *  §282 framing) plus cluster107 sibling sweep (Task #563 — :presentation
 *  /settings/ tier).
 *  Six classifications STAND on their own merits as a faithful Submit-
 *  FeedbackUseCase manifest. Original Phase 7.x.settings.feedback-era
 *  prose preserved verbatim per the audit-trail-preservation convention;
 *  the UNDERSPECIFIED-CONSUMER-SET delta in (a) appended as a non-
 *  destructive correction without rewriting the original SettingsView-
 *  Model-singular consumer framing.
 */
class SubmitFeedbackUseCase(
    private val feedbackRepository: FeedbackRepository,
) {
    /**
     * @param type user-selected complaint category.
     * @param subject short header line (typically the type's display name; the Settings hub
     *               passes `type.name` pre-Phase 10).
     * @param body free-form user-typed description.
     * @return [Result.success] on commit; [Result.failure] on any throw — see
     *         [FeedbackRepository.submit] for failure modes.
     */
    suspend operator fun invoke(type: ComplaintType, subject: String, body: String): Result<Unit> =
        feedbackRepository.submit(type = type, subject = subject, body = body)
}
