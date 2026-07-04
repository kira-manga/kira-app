package me.manga.kira.core.platform

import androidx.compose.runtime.Composable

/**
 * Compose side-effect that hides the system navigation bar for the lifetime of the call site's
 * composition, restoring it on dispose. Reader-specific helper (Phase 7.x.reader.systembars).
 *
 * **Behaviour parity with native pre-KMP `HideSystemBars()`** (see
 * `yami-manga-apk-main/.../ReaderScreen.kt:768-786`):
 *  - Hides **only the navigation bar** — the status bar is *not* touched. Native code
 *    deliberately scoped `hide(WindowInsetsCompat.Type.navigationBars())` only, leaving the
 *    status bar visible so the user retains time / battery / signal indicators while reading.
 *  - `systemBarsBehavior` is set to `BEHAVIOR_DEFAULT` (verbatim native posture). The
 *    transient-swipe behaviour (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`) is intentionally NOT
 *    used — the legacy app preferred the bar to stay hidden until the screen disposes.
 *  - On dispose the navigation bar is `show(...)`-restored — matches the native cleanup branch.
 *
 * **Why this is NOT bound to `ReaderState.isUiVisible`**: native pre-KMP behaviour was
 * unconditional hide-on-enter / restore-on-exit, independent of in-app chrome tap-toggle. Tying
 * navigation-bar visibility to `isUiVisible` would change observable behaviour beyond the
 * Phase 7.x.reader.chrome slice's scope; the rework respects native parity here. If a future
 * design decision wants the navigation bar to track chrome, that's a deliberate UX slice with
 * its own design review.
 *
 * **Why this lives in `:composeApp/commonMain/core/platform/` and not `:platform`**: the legacy
 * `:platform` module hosts non-Compose service facades injected via Koin. This is a
 * Compose-specific side-effect that needs `LocalContext` / `LocalView` on Android and the
 * `androidx.core.view.WindowInsetsControllerCompat` API — same shape as the existing
 * [me.manga.kira.core.platform.fastScrollerGestureExclusion] / `RememberNotificationPermissionRequester`
 * Compose helpers that already live here. No DI involvement, no `ActivityHolder` dependency —
 * the Android actual reads the Activity from `LocalContext` directly. (`:platform`'s
 * Koin-backed `ForegroundActivityProvider` is unavailable to the rework graph until Phase 11
 * lands `ActivityHolder`; using `LocalContext.findActivity()` instead sidesteps that block
 * without expanding scope.)
 *
 * **Why this is NOT placed in `:ui`**: `:ui` is contractually multiplatform-pure (Contract §4):
 * it must not depend on Android-only APIs. Hosting the side-effect at the `:composeApp` route
 * adapter (`ChapterImagesReworkScreenRoute`) keeps `:ui/.../reader/ReaderScreen` free of
 * platform branches and preserves its parameter contract.
 *
 * **Platform behaviour**:
 *  - **Android**: hides nav bar via `WindowInsetsControllerCompat.hide(navigationBars())`,
 *    restores on dispose. No-op if no Activity is reachable from `LocalContext` (defensive
 *    fallback — shouldn't happen in normal navigation flow but harmless if it does).
 *  - **iOS**: no-op. iOS has no concept of a OS-controlled "navigation bar" comparable to
 *    Android's. The home indicator on modern iPhones is a system surface that apps may hide via
 *    `prefersHomeIndicatorAutoHidden`, but doing so changes a different user-visible thing than
 *    the legacy Android behaviour and is out of scope for parity.
 *  - **Desktop**: no-op. Desktop windowing has no analog of the Android navigation bar.
 *
 * Invoke from the Reader route adapter only — composing it elsewhere will hide the nav bar
 * outside the reader and create unintended global behaviour.
 */
@Composable
expect fun HideNavigationBarSideEffect()

/**
 * **Audit-trail postscript** (Phase 9.x.cluster155.staleKdocSweep.cascade,
 * Task #611, 2026-05-28): classified as follows after recursive symbol
 * verification (two-hundred-and-first sibling of the cluster57-154 sweep —
 * OPENING file of the wave-27 :composeApp platform-shim expect-decl 4-leaf
 * batch alongside FastScrollerGestureExclusion plus RememberNotification
 * PermissionRequester plus WebViewHost; OPENS :composeApp platform-shim
 * tier 1/4):
 *  (a) "Compose-side-effect-that-hides-the-system-navigation-bar-for-the-
 *  lifetime-of-the-call-site-s-composition-restoring-it-on-dispose +
 *  Reader-specific-helper-Phase-7.x.reader.systembars + Behaviour-parity-
 *  with-native-pre-KMP-HideSystemBars + Hides-only-the-navigation-bar-the
 *  -status-bar-is-not-touched + Native-code-deliberately-scoped-hide-
 *  WindowInsetsCompat.Type.navigationBars-only-leaving-the-status-bar-
 *  visible-so-the-user-retains-time-battery-signal-indicators-while-
 *  reading + systemBarsBehavior-is-set-to-BEHAVIOR_DEFAULT-verbatim-native
 *  -posture + The-transient-swipe-behaviour-BEHAVIOR_SHOW_TRANSIENT_BARS_BY
 *  _SWIPE-is-intentionally-NOT-used-the-legacy-app-preferred-the-bar-to-
 *  stay-hidden-until-the-screen-disposes + On-dispose-the-navigation-bar-
 *  is-show-restored-matches-the-native-cleanup-branch + Why-this-is-NOT-
 *  bound-to-ReaderState.isUiVisible-native-pre-KMP-behaviour-was-
 *  unconditional-hide-on-enter-restore-on-exit-independent-of-in-app-
 *  chrome-tap-toggle + Tying-navigation-bar-visibility-to-isUiVisible-
 *  would-change-observable-behaviour-beyond-the-Phase-7.x.reader.chrome-
 *  slice-s-scope + Why-this-lives-in-:composeApp-commonMain-core-platform
 *  -and-not-:platform-the-legacy-:platform-module-hosts-non-Compose-
 *  service-facades-injected-via-Koin + This-is-a-Compose-specific-side-
 *  effect-that-needs-LocalContext-LocalView-on-Android-and-the-androidx.
 *  core.view.WindowInsetsControllerCompat-API + Same-shape-as-the-existing
 *  -fastScrollerGestureExclusion-RememberNotificationPermissionRequester-
 *  Compose-helpers-that-already-live-here + No-DI-involvement-no-Activity
 *  Holder-dependency-the-Android-actual-reads-the-Activity-from-Local
 *  Context-directly + :platform-s-Koin-backed-ForegroundActivityProvider-
 *  is-unavailable-to-the-rework-graph-until-Phase-11-lands-ActivityHolder
 *  -using-LocalContext.findActivity-instead-sidesteps-that-block-without-
 *  expanding-scope + Why-this-is-NOT-placed-in-:ui-:ui-is-contractually-
 *  multiplatform-pure-Contract-section-4-it-must-not-depend-on-Android-
 *  only-APIs + Hosting-the-side-effect-at-the-:composeApp-route-adapter-
 *  ChapterImagesReworkScreenRoute-keeps-:ui-reader-ReaderScreen-free-of-
 *  platform-branches + Platform-behaviour-Android-hides-nav-bar-via-Window
 *  InsetsControllerCompat.hide-navigationBars-restores-on-dispose + iOS-
 *  no-op-iOS-has-no-concept-of-a-OS-controlled-navigation-bar-comparable-
 *  to-Android-s + The-home-indicator-on-modern-iPhones-is-a-system-surface
 *  -that-apps-may-hide-via-prefersHomeIndicatorAutoHidden-but-doing-so-
 *  changes-a-different-user-visible-thing + Desktop-no-op-Desktop-
 *  windowing-has-no-analog-of-the-Android-navigation-bar + Invoke-from-
 *  the-Reader-route-adapter-only-composing-it-elsewhere-will-hide-the-nav
 *  -bar-outside-the-reader-and-create-unintended-global-behaviour" —
 *  LIVE-NOT-STALE plus FORECAST-NOT-YET-FULFILLED on the deferred Phase
 *  11.x.activityholder enablement. Verified: @Composable expect fun
 *  HideNavigationBarSideEffect() shipped as a zero-parameter declaration.
 *  Consumed by ChapterImagesReworkScreenRoute (cluster1 sibling X) before
 *  ReaderScreen composition. The "Phase 7.x.reader.systembars native-
 *  HideSystemBars parity" stance honored — Android actual hides only the
 *  navigation bar via WindowInsetsControllerCompat.hide(navigationBars()),
 *  restores on dispose, leaves the status bar visible (verbatim native
 *  posture). The "not bound to ReaderState.isUiVisible" rationale honored
 *  — unconditional hide-on-enter / restore-on-exit, independent of in-
 *  app chrome tap-toggle. The "no ActivityHolder DI dependency" stance
 *  honored — Android actual reads the Activity from LocalContext directly,
 *  sidestepping the Phase 11.x.activityholder block. The ":platform vs
 *  :composeApp/core/platform" boundary honored — Compose-specific side-
 *  effect needs LocalContext/LocalView so :composeApp hosts it, not
 *  :platform. The "iOS / Desktop no-op" actuals contract honored. The
 *  "invoke from reader route adapter only" usage discipline honored —
 *  ChapterImagesReworkScreenRoute is the sole call site. OPENING FILE of
 *  the cluster155 :composeApp platform-shim expect-decl 4-leaf batch (1
 *  of 4: HideNavigationBarSideEffect + FastScrollerGestureExclusion +
 *  RememberNotificationPermissionRequester + WebViewHost). One
 *  classification. Original Phase 7.x.reader.systembars expect-decl prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
