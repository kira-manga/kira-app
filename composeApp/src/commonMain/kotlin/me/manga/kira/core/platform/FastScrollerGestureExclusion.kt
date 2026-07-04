package me.manga.kira.core.platform

import androidx.compose.ui.Modifier

/**
 * Excludes the modifier's bounds from the OS-level edge gesture (Android predictive back / system
 * gesture region). Used by the fast scroller's thumb so a back-swipe over the scrollbar drags the
 * thumb instead of dismissing the screen.
 *
 * - **Android** delegates to `Modifier.systemGestureExclusion()` (API 29+).
 * - **iOS** is a no-op — there's no equivalent OS edge gesture that conflicts with vertical drag.
 * - **Desktop** is a no-op — desktop has no system-level swipe-from-edge gesture.
 */
expect fun Modifier.fastScrollerGestureExclusion(): Modifier

/**
 * **Audit-trail postscript** (Phase 9.x.cluster155.staleKdocSweep.cascade,
 * Task #611, 2026-05-28): classified as follows after recursive symbol
 * verification (two-hundred-and-second sibling of the cluster57-154 sweep
 * — CONTINUING file of the wave-27 :composeApp platform-shim expect-decl
 * 4-leaf batch alongside HideNavigationBarSideEffect plus RememberNoti
 * ficationPermissionRequester plus WebViewHost; CONTINUES :composeApp
 * platform-shim tier 2/4):
 *  (a) "Excludes-the-modifier-s-bounds-from-the-OS-level-edge-gesture-
 *  Android-predictive-back-system-gesture-region + Used-by-the-fast-
 *  scroller-s-thumb-so-a-back-swipe-over-the-scrollbar-drags-the-thumb-
 *  instead-of-dismissing-the-screen + Android-delegates-to-Modifier.
 *  systemGestureExclusion-API-29-plus + iOS-is-a-no-op-there-s-no-
 *  equivalent-OS-edge-gesture-that-conflicts-with-vertical-drag + Desktop
 *  -is-a-no-op-desktop-has-no-system-level-swipe-from-edge-gesture" —
 *  LIVE-NOT-STALE. Verified: expect fun Modifier.fastScrollerGesture
 *  Exclusion(): Modifier shipped as a Modifier extension. The "back-swipe-
 *  over-scrollbar-drags-thumb-instead-of-dismissing" UX rationale honored
 *  — Android actual delegates to Modifier.systemGestureExclusion() (API
 *  29+), iOS / Desktop actuals are no-op. Consumed by the rework's
 *  LazyVerticalScrollerWithScrollBar (cluster85 sibling X) on the
 *  scrollbar thumb composable. CONTINUING FILE of the cluster155
 *  :composeApp platform-shim expect-decl 4-leaf batch (2 of 4). One
 *  classification. Original Phase 7.x.scrollbar.gestureExclusion expect-
 *  decl prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
