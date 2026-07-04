package me.manga.kira.core.platform

import androidx.compose.ui.Modifier

// iOS has no system-level edge gesture that conflicts with vertical drag inside an app surface
// (the back-swipe is owned by UINavigationController and uses a left-edge pan, which is already
// handled by the navigation chain — the fast scroller lives on the right edge). No-op is correct.
actual fun Modifier.fastScrollerGestureExclusion(): Modifier = this

/**
 * **Audit-trail postscript** (Phase 9.x.cluster165.staleKdocSweep.cascade,
 * Task #621, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-thirtieth sibling of the cluster57-164
 * sweep — INTERIOR file of the wave-37 FastScrollerGestureExclusion 3-actual
 * fan batch; INTERIOR FastScrollerGestureExclusion actuals 2/3):
 *  (a) inline-comment "iOS-has-no-system-level-edge-gesture-that-conflicts-
 *  with-vertical-drag-inside-an-app-surface + the-back-swipe-is-owned-by-
 *  UINavigationController-and-uses-a-left-edge-pan-which-is-already-handled-
 *  by-the-navigation-chain + the-fast-scroller-lives-on-the-right-edge +
 *  No-op-is-correct" — LIVE-NOT-STALE (the deliberate-no-op posture is
 *  honored — the rework Library's fast-scroller iOS surface continues to
 *  render with the identity-Modifier return, exactly as the rationale prose
 *  stipulates. No forecast remaining; the no-op is the intended final
 *  shape, not a placeholder pending a future implementation. The
 *  "UINavigationController owns the left-edge back-swipe + scrollbar lives
 *  on the right edge → no spatial overlap" argument is a load-bearing
 *  spatial-geometry rationale, not a forecast — it explains WHY no-op is
 *  correct, not what to do next). Verified: actual fun Modifier.
 *  fastScrollerGestureExclusion() shipped — body is `= this` identity
 *  return. No DisposableEffect, no Modifier.composed wrapper, no UIKit
 *  reach. Consumed by LazyVerticalScrollerWithScrollBar (cluster85 sibling
 *  — Phase 7.x.library.fastscroller slice) via the
 *  fastScrollerGestureExclusion expect-decl in commonMain. Sibling
 *  actuals: Android (opening-sibling per FastScrollerGestureExclusion.
 *  android.kt — real systemGestureExclusionRects implementation gated to
 *  API 29+/Q via Build.VERSION.SDK_INT check) + Desktop (closing-sibling
 *  per FastScrollerGestureExclusion.desktop.kt — also a deliberate no-op
 *  for analogous reasons in the JVM desktop window-manager domain).
 *  INTERIOR FILE of the cluster165 FastScrollerGestureExclusion 3-actual
 *  fan batch (2 of 3). One classification. Original Phase 7.x.library.
 *  fastscroller iOS no-op-rationale prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
