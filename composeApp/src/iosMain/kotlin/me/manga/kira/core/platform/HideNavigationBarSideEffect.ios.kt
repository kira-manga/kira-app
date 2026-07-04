package me.manga.kira.core.platform

import androidx.compose.runtime.Composable

// iOS has no system "navigation bar" analogous to Android's bottom nav region. The home
// indicator on modern iPhones is the closest equivalent, and apps can hide it via
// `prefersHomeIndicatorAutoHidden`, but that affects a different visual surface than the
// Android navigation bar — toggling it would be a behaviour change, not a parity port. No-op
// keeps the rework Reader's iOS surface identical to the native pre-KMP iOS build (which
// never ran this code path either — `HideSystemBars()` was Android-only).
@Composable
actual fun HideNavigationBarSideEffect() {
    // Intentionally empty.
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster163.staleKdocSweep.cascade,
 * Task #619, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-twenty-fourth sibling of the cluster57-162
 * sweep — INTERIOR file of the wave-35 HideNavigationBarSideEffect 3-actual
 * fan batch; INTERIOR HideNavigationBarSideEffect actuals 2/3):
 *  (a) "iOS-has-no-system-navigation-bar-analogous-to-Android-s-bottom-nav-
 *  region + The-home-indicator-on-modern-iPhones-is-the-closest-equivalent-
 *  and-apps-can-hide-it-via-prefersHomeIndicatorAutoHidden-but-that-affects-
 *  a-different-visual-surface-than-the-Android-navigation-bar + toggling-it-
 *  would-be-a-behaviour-change-not-a-parity-port + No-op-keeps-the-rework-
 *  Reader-s-iOS-surface-identical-to-the-native-pre-KMP-iOS-build-which-
 *  never-ran-this-code-path-either-HideSystemBars-was-Android-only" — LIVE-
 *  NOT-STALE (the deliberate-no-op posture is honored — the rework Reader's
 *  iOS surface continues to render with no system-bar toggling, exactly as
 *  the rationale prose stipulates. No forecast remaining; the no-op is the
 *  intended final shape, not a placeholder pending a future implementation.
 *  The "prefersHomeIndicatorAutoHidden would be a behaviour change not a
 *  parity port" argument is a load-bearing rationale, not a forecast — it
 *  explains WHY no-op is correct, not what to do next). Verified:
 *  @Composable actual fun HideNavigationBarSideEffect() shipped — body is
 *  an empty no-op (`// Intentionally empty.` placeholder comment). The
 *  "native pre-KMP iOS build never ran this code path" historical claim
 *  honored — HideSystemBars() in upstream ReaderScreen.kt:768-786 used
 *  WindowCompat APIs which are Android-only; no iOS equivalent was invoked
 *  in the pre-KMP codebase. Consumed by Reader rework screen (cluster9-
 *  sibling §367 — Phase 7.x.reader.controls slice) via the
 *  HideNavigationBarSideEffect expect-decl in commonMain. Sibling actuals:
 *  Android (opening-sibling per HideNavigationBarSideEffect.android.kt —
 *  real WindowCompat insets hide/show navigationBars() implementation) +
 *  Desktop (closing-sibling per HideNavigationBarSideEffect.desktop.kt —
 *  also a deliberate no-op for analogous reasons in the JVM desktop window
 *  manager domain). INTERIOR FILE of the cluster163
 *  HideNavigationBarSideEffect 3-actual fan batch (2 of 3). One
 *  classification. Original Phase 7.x.reader.controls iOS no-op-rationale
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
