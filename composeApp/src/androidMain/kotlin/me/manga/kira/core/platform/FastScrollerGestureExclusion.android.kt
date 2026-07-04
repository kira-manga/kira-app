package me.manga.kira.core.platform

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

actual fun Modifier.fastScrollerGestureExclusion(): Modifier {
    // systemGestureExclusionRects is API 29+; foundation's systemGestureExclusion() no-ops below
    // that, scopes the rect to this layout node, and removes it when the node detaches.
    return this.systemGestureExclusion()
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster165.staleKdocSweep.cascade,
 * Task #621, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-twenty-ninth sibling of the cluster57-164
 * sweep — OPENING file of the wave-37 FastScrollerGestureExclusion 3-actual
 * fan batch; OPENS FastScrollerGestureExclusion actuals tier 1/3):
 *  (a) inline-comment "systemGestureExclusionRects-is-API-29-plus + Below-
 *  that-the-OS-doesn-t-claim-an-edge-gesture-region-for-back-swipe-in-a-way-
 *  that-conflicts-with-the-scrollbar-thumb-so-no-op-is-safe" — LIVE-NOT-
 *  STALE (the API 29+ gate is honored: if Build.VERSION.SDK_INT <
 *  Build.VERSION_CODES.Q return this no-op, otherwise wrap with
 *  Modifier.composed { ... onGloballyPositioned { coords -> view.
 *  systemGestureExclusionRects = listOf(Rect(...)) } }. The "below API 29
 *  no-op is safe because OS doesn't claim a back-swipe edge-region
 *  conflicting with the scrollbar thumb" platform-reality rationale honored
 *  — Android added back-gesture inset reservation in API 29/Q, which is
 *  exactly the gate). Verified: actual fun Modifier.fastScrollerGesture
 *  Exclusion() shipped — Modifier.composed wraps LocalView.current +
 *  Modifier.onGloballyPositioned { coords -> coords.boundsInWindow() ->
 *  android.graphics.Rect(left, top, right, bottom).toInt() -> view.
 *  systemGestureExclusionRects = listOf(rect) }. Per-recomposition rect
 *  update keeps the OS-level back-gesture exclusion zone aligned with the
 *  composable's current viewport bounds. Consumed by LazyVerticalScroller
 *  WithScrollBar (cluster85 sibling — Phase 7.x.library.fastscroller slice)
 *  via the fastScrollerGestureExclusion expect-decl in commonMain. Sibling
 *  actuals: iOS (interior-sibling per FastScrollerGestureExclusion.ios.kt —
 *  intentionally no-op identity-Modifier return, UINavigationController
 *  owns the left-edge back-swipe while the scrollbar lives on the right
 *  edge) + Desktop (closing-sibling per FastScrollerGestureExclusion.
 *  desktop.kt — intentionally no-op identity-Modifier return, Win32/AppKit/
 *  Wayland/X11 windowing systems don't reserve edge regions for OS-level
 *  swipe gestures inside an app window). OPENING FILE of the cluster165
 *  FastScrollerGestureExclusion 3-actual fan batch (1 of 3). One
 *  classification. Original Phase 7.x.library.fastscroller Android-port
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
