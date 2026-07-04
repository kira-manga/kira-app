package me.manga.kira.presentation.whatsnew

import me.manga.kira.presentation.mvi.MviEffect

/**
 * One-shot effects emitted by [WhatsNewViewModel] for the route adapter to perform once.
 *
 * Phase 7.x.whatsnew. One variant — [OpenVideo] (GAP-WN-01, Phase 7.x.whatsnew.media), which
 * launches a feature's video URL externally. Otherwise the `:ui` surface has no external-launch
 * paths:
 *
 * - No nav-back effect (the route adapter owns back navigation via `NavController.popBack
 *   Stack()` from the TopAppBar's nav icon — same posture as the rework Statistics screen
 *   from Phase 7.x.statistics, and consistent with [me.manga.kira.presentation.about.AboutEffect]'s
 *   no-back-effect posture).
 * - No fullscreen-viewer trigger (deferred to `Phase 7.x.whatsnew.fullscreen` — the
 *   `OpenFullscreenMedia(...)` variant slots in there once the `:platform` MediaPlayer SPI
 *   lands).
 * - No should-show navigate effect (deferred to `Phase 7.x.whatsnew.gate` — the auto-trigger
 *   `NavigateToWhatsNew` effect lifts into the About screen's MVI surface, not this one).
 *
 * **Why declare an empty sealed interface (not omit the type)** — same posture as
 * [me.manga.kira.presentation.statistics.StatisticsEffect] (also currently empty). The
 * alternative ("don't declare it") would force [WhatsNewViewModel] to extend
 * `MviViewModel<WhatsNewState, WhatsNewIntent, Nothing>` — uglier signature, less self-
 * documenting. The empty-sealed-interface pattern documents the slice's extensibility hook:
 * a future `OpenFullscreenMedia(...)` variant slots in without changing the VM's base class.
 *
 * **Contract §6 OCP**: sealed interface, currently one variant ([OpenVideo]). Future sub-slices ADD
 * variants without breaking existing call sites.
 *
 * **Strict-MVI Contract §17**: effects carry only the trigger — never rendering data. When
 * the fullscreen sub-slice lands, the effect will carry only the bare params the platform
 * call needs (e.g., the URL list + start index), NOT the rendering-ready MediaItem list.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster109.staleKdocSweep.cascade,
 * Task #565, 2026-05-28): the file-scope effect-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-ninth sibling of the cluster57-108 sweep — opens
 * the wave-9 `:presentation/whatsnew/` batch, closing the wave-9
 * `:presentation/` tier alongside WhatsNewIntent.kt plus WhatsNewView-
 * Model.kt; WhatsNewState.kt already postscripted at cluster31 Task
 * #487):
 *  (a) "Currently EMPTY — no URL launches, no nav-back effect, no
 *  fullscreen-viewer trigger, no should-show navigate effect" — NOW
 *  STALE (superseded by GAP-WN-01, Phase 7.x.whatsnew.media). The sealed
 *  interface now declares one variant, OpenVideo(url), launching a
 *  feature's video URL externally; the remaining no-nav-back / no-
 *  fullscreen / no-should-show claims still hold.
 *  (b) "Why declare an empty sealed interface (not omit the type) —
 *  same posture as StatisticsEffect" — LIVE-NOT-STALE. StatisticsEffect
 *  empty-sealed-interface posture verified at cluster103 sibling sweep
 *  (Task #559); peer cross-ref preserved verbatim. The `MviViewModel
 *  <WhatsNewState, WhatsNewIntent, WhatsNewEffect>` signature at
 *  WhatsNewViewModel.kt L77 confirms the empty-but-typed-effect channel
 *  is in use (would have to be Nothing if the type were omitted).
 *  (c) "OpenFullscreenMedia(...) variant slots in there once the
 *  `:platform` MediaPlayer SPI lands" — FORECAST-NOT-YET-FULFILLED. The
 *  `:platform` MediaPlayer SPI has not landed; recursive search for
 *  MediaPlayer interface declarations in `:platform/commonMain/`
 *  returns no matches. Forecast posture preserved verbatim for the
 *  future Phase 7.x.whatsnew.fullscreen slice's landing.
 *  (d) "Should-show NavigateToWhatsNew effect lifts into the About
 *  screen's MVI surface" — FORECAST-NOT-YET-FULFILLED. The AboutEffect.
 *  kt at cluster106 sibling sweep (Task #562) verified declares zero
 *  NavigateToWhatsNew variants; the Phase 7.x.whatsnew.gate slice
 *  forecast remains unbuilt. Forecast posture preserved verbatim.
 *  (e) "Contract §6 OCP plus Strict-MVI §17 — sealed interface, future
 *  variants ADD without breaking existing call sites; effects carry only
 *  the trigger never rendering data" — LIVE-NOT-STALE. The OpenVideo
 *  variant honours §17 (carries only the bare URL); further deferred
 *  variants ADD without breaking existing call sites.
 *  Five classifications STAND on their own merits as a faithful
 *  WhatsNewEffect surface manifest. Original Phase 7.x.whatsnew-era
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
sealed interface WhatsNewEffect : MviEffect {

    /**
     * Open a feature's video URL externally (GAP-WN-01, Phase 7.x.whatsnew.media).
     *
     * The rework `:ui` has no cross-platform inline video player — the `PlatformVideoPlayer`
     * SPI lives in `:composeApp` (the only module that may declare a `@Composable expect`),
     * which `:ui` must not depend on (Contract §4). Rather than embed a player or add a new
     * video dependency, the FeatureCard renders the video as a tappable poster/placeholder
     * with a play affordance; tapping emits this effect. The route adapter routes it through
     * the Koin-resolved [me.manga.kira.core.platform.IntentLauncher] `openUrl(url)` — the
     * system video handler / browser plays it. DEVIATION(platform) substitute for the native
     * Android `VideoView` inline player (`whatsnew/ui/components/VideoComponents.kt`'s
     * `SafeVideoPlayer`); documented as such per the GAP-WN-01 acceptance criteria.
     *
     * Same trigger-only payload posture as [me.manga.kira.presentation.about.AboutEffect.OpenUrl]
     * — carries the bare URL the platform call needs, no rendering data (Strict-MVI §17).
     */
    data class OpenVideo(val url: String) : WhatsNewEffect
}
