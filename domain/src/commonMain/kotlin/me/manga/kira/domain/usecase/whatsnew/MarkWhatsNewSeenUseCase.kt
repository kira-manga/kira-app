package me.manga.kira.domain.usecase.whatsnew

import me.manga.kira.domain.repository.WhatsNewRepository

/**
 * Record that the user has seen the What's New surface for the current app version.
 *
 * Phase 7.x.whatsnew. The rework `WhatsNewViewModel` invokes this use case from
 * `handle(WhatsNewIntent.OnMarkSeen)`. The write is live: the rework `:ui` `WhatsNewScreen`
 * dispatches `OnMarkSeen` (dismiss/confirm), and the `WhatsNewScreenRoute` adapter submits it on
 * mount, so the version-name + timestamp prefs are recorded for the current app version.
 *
 * Suspend pass-through. The repository contract handles the dual prefs write (version-name key
 * + timestamp key) atomically; the use case adds no orchestration.
 *
 * **Why a separate use case (not a method on `GetWhatsNewFeaturesUseCase`)**: SRP. The two
 * concerns — read features, write mark-seen — have orthogonal lifetimes (one is screen-mount,
 * one is screen-dismiss or explicit user action) and orthogonal failure modes (read may return
 * empty; write is structurally infallible — sync `SharedPrefsHelper.putString/putLong`). A
 * single use case bundling both would force the caller to pass a discriminant. Two use cases
 * keep the caller's `handle(intent)` block one-line-per-arm.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `whatsNewReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster111.staleKdocSweep.cascade,
 * Task #567, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-first sibling of the cluster57-110 sweep — closes
 * the wave-11 `:domain/usecase/whatsnew/` batch alongside GetWhatsNew-
 * FeaturesUseCase.kt):
 *  (a) "Phase 7.x.whatsnew foundation — rework WhatsNewViewModel invokes
 *  this use case from handle(WhatsNewIntent.OnMarkSeen) — fires-and-
 *  forgets on viewModelScope" — MIXED LIVE-PLUS-STALE-WORDING. The
 *  WhatsNewViewModel.kt L154 `WhatsNewIntent.OnMarkSeen rename-to mark-
 *  WhatsNewSeen()` realization calls the suspend use case DIRECTLY from
 *  within the `handle` override (which itself runs on the intent-
 *  processing coroutine via the MviViewModel base class) — NOT wrapped
 *  in `viewModelScope.launch{}`. The "fires-and-forgets on viewModel-
 *  Scope" wording is mildly inaccurate (the call suspends the intent-
 *  processing coroutine until the prefs write completes) but immaterial
 *  given the use case's structurally-infallible sync-prefs-write nature.
 *  Cross-referenced at cluster109 sibling sweep WhatsNewViewModel.kt
 *  postscript (f) — same mixed-wording classification preserved here
 *  for parity with the VM-side audit trail.
 *  (b) "Foundation `:ui` does NOT submit OnMarkSeen from any composable
 *  today (debug-only route + deferred should-show gating means mark-seen
 *  has no functional impact yet) — use case is wired for the follow-on
 *  Phase 7.x.whatsnew.gate sub-slice that lands the auto-trigger" —
 *  LIVE-NOT-STALE for the wired-but-unreached observation; FORECAST-NOT-
 *  YET-FULFILLED for the gate sub-slice. WhatsNewIntent.kt cluster109
 *  sibling sweep (b) verified no `:ui` composable dispatches OnMarkSeen
 *  today (recursive verification across `:ui/whatsnew/`); the gate
 *  forecast remains unbuilt. Note: the parenthetical "debug-only route"
 *  framing is now SUPERSEDED — Phase 7.x.whatsnew.swap re-pointed
 *  `Screen.WhatsNew` to the rework adapter, so the rework WhatsNew
 *  surface is no longer debug-only. The use case's reach-status is
 *  unchanged (still wired-but-unreached) but the surrounding wording
 *  carries a historical-routing-era qualifier; preserved verbatim per
 *  the audit-trail-preservation convention.
 *  (c) "Suspend pass-through; repository contract handles the dual prefs
 *  write (version-name key + timestamp key) atomically; the use case
 *  adds no orchestration" — LIVE-NOT-STALE. L30 realization `repository.
 *  markSeen()` single-line pass-through; WhatsNewRepository.kt L28-31
 *  KDoc confirms the no-param + version-from-AppVersionProvider plus
 *  idempotency-realization contract; WhatsNewRepositoryImpl dual-prefs-
 *  write realization verified at cluster25 sibling sweep (Task #481).
 *  (d) "Why a separate use case (not a method on GetWhatsNewFeaturesUse-
 *  Case) — SRP: the two concerns have orthogonal lifetimes (screen-
 *  mount versus screen-dismiss/explicit user action) plus orthogonal
 *  failure modes (read may return empty; write is structurally infall-
 *  ible — sync SharedPrefsHelper.putString/putLong)" — LIVE-NOT-STALE.
 *  WhatsNewReworkModule.kt L102-103 binds the two use cases as separate
 *  factories; the WhatsNewViewModel.kt L141-142 primary-constructor
 *  injects them as two distinct collaborators (getWhatsNewFeatures plus
 *  markWhatsNewSeen). The `handle(intent)` block at L151-156 realizes
 *  the one-line-per-arm posture the SRP rationale predicted.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `whatsNewReworkModule`" — LIVE-NOT-STALE. WhatsNewRework-
 *  Module.kt L103 `factory { MarkWhatsNewSeenUseCase(get()) }`
 *  realization confirms factory lifecycle (stateless, cheap to
 *  construct, never shared).
 *  Five classifications STAND on their own merits as a faithful Mark-
 *  WhatsNewSeenUseCase manifest. Original Phase 7.x.whatsnew-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class MarkWhatsNewSeenUseCase(
    private val repository: WhatsNewRepository,
) {
    suspend operator fun invoke() = repository.markSeen()
}
