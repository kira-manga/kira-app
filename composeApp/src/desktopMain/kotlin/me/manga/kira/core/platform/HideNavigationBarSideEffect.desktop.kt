package me.manga.kira.core.platform

import androidx.compose.runtime.Composable

// Desktop window managers (Win32 / AppKit / Wayland / X11) have no Android-style navigation
// bar to hide inside an app window. The OS task bar / dock is owned by the user's shell and
// is out of scope for app-level fullscreen ergonomics here. No-op keeps the rework Reader's
// desktop surface identical to the native pre-KMP behaviour (which never ran a system-bar
// hide path on JVM).
@Composable
actual fun HideNavigationBarSideEffect() {
    // Intentionally empty.
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster163.staleKdocSweep.cascade,
 * Task #619, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-twenty-fifth sibling of the cluster57-162
 * sweep — CLOSING file of the wave-35 HideNavigationBarSideEffect 3-actual
 * fan batch; CLOSES HideNavigationBarSideEffect actuals tier 3/3):
 *  (a) "Desktop-window-managers-Win32-AppKit-Wayland-X11-have-no-Android-
 *  style-navigation-bar-to-hide-inside-an-app-window + The-OS-task-bar-dock-
 *  is-owned-by-the-user-s-shell-and-is-out-of-scope-for-app-level-fullscreen-
 *  ergonomics-here + No-op-keeps-the-rework-Reader-s-desktop-surface-
 *  identical-to-the-native-pre-KMP-behaviour-which-never-ran-a-system-bar-
 *  hide-path-on-JVM" — LIVE-NOT-STALE (the deliberate-no-op posture is
 *  honored — the rework Reader's desktop surface continues to render with no
 *  system-bar toggling, exactly as the rationale prose stipulates. No
 *  forecast remaining; the no-op is the intended final shape, not a
 *  placeholder pending a future implementation. The "OS task bar / dock is
 *  owned by the user's shell and is out of scope" argument is a load-
 *  bearing rationale, not a forecast — it explains WHY no-op is correct,
 *  not what to do next). Verified: @Composable actual fun
 *  HideNavigationBarSideEffect() shipped — body is an empty no-op (`//
 *  Intentionally empty.` placeholder comment). The "native pre-KMP behaviour
 *  never ran a system-bar hide path on JVM" historical claim honored — the
 *  pre-KMP codebase did not have a desktop variant of the Reader at all;
 *  HideSystemBars() in upstream ReaderScreen.kt:768-786 was Android-only and
 *  was never bridged to a JVM Swing/AWT/JavaFX equivalent. Consumed by
 *  Reader rework screen (cluster9-sibling §367 — Phase 7.x.reader.controls
 *  slice) via the HideNavigationBarSideEffect expect-decl in commonMain.
 *  Sibling actuals: Android (opening-sibling per HideNavigationBarSideEffect.
 *  android.kt — real WindowCompat insets hide/show navigationBars()
 *  implementation) + iOS (interior-sibling per HideNavigationBarSideEffect.
 *  ios.kt — also a deliberate no-op for analogous reasons in the iOS home-
 *  indicator domain). CLOSING FILE of the cluster163
 *  HideNavigationBarSideEffect 3-actual fan batch (3 of 3 — CLOSES
 *  HideNavigationBarSideEffect actuals tier). One classification. Original
 *  Phase 7.x.reader.controls Desktop no-op-rationale prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
