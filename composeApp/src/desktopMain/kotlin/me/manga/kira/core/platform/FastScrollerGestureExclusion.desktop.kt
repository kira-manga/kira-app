package me.manga.kira.core.platform

import androidx.compose.ui.Modifier

// Desktop windowing systems (Win32/AppKit/Wayland/X11) don't reserve edge regions for OS-level
// swipe gestures inside an app window, so there's nothing to exclude. No-op is correct.
actual fun Modifier.fastScrollerGestureExclusion(): Modifier = this

/**
 * **Audit-trail postscript** (Phase 9.x.cluster165.staleKdocSweep.cascade,
 * Task #621, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-thirty-first sibling of the cluster57-164
 * sweep — CLOSING file of the wave-37 FastScrollerGestureExclusion 3-actual
 * fan batch; CLOSES FastScrollerGestureExclusion actuals tier 3/3):
 *  (a) inline-comment "Desktop-windowing-systems-Win32-AppKit-Wayland-X11-
 *  don-t-reserve-edge-regions-for-OS-level-swipe-gestures-inside-an-app-
 *  window + so-there-s-nothing-to-exclude + No-op-is-correct" — LIVE-NOT-
 *  STALE (the deliberate-no-op posture is honored — the rework Library's
 *  fast-scroller Desktop surface continues to render with the identity-
 *  Modifier return, exactly as the rationale prose stipulates. No forecast
 *  remaining; the no-op is the intended final shape, not a placeholder
 *  pending a future implementation. The "Desktop windowing systems don't
 *  reserve edge regions for OS-level swipe gestures inside an app window"
 *  argument is a load-bearing platform-reality rationale, not a forecast
 *  — it explains WHY no-op is correct, not what to do next). Verified:
 *  actual fun Modifier.fastScrollerGestureExclusion() shipped — body is
 *  `= this` identity return. No DisposableEffect, no Modifier.composed
 *  wrapper, no AWT/Swing reach. Consumed by LazyVerticalScrollerWithScroll
 *  Bar (cluster85 sibling — Phase 7.x.library.fastscroller slice) via the
 *  fastScrollerGestureExclusion expect-decl in commonMain. Sibling actuals:
 *  Android (opening-sibling per FastScrollerGestureExclusion.android.kt —
 *  real systemGestureExclusionRects implementation gated to API 29+/Q via
 *  Build.VERSION.SDK_INT check) + iOS (interior-sibling per
 *  FastScrollerGestureExclusion.ios.kt — also a deliberate no-op for
 *  analogous reasons in the UINavigationController back-swipe domain).
 *  CLOSING FILE of the cluster165 FastScrollerGestureExclusion 3-actual
 *  fan batch (3 of 3 — CLOSES FastScrollerGestureExclusion actuals tier).
 *  One classification. Original Phase 7.x.library.fastscroller Desktop
 *  no-op-rationale prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
