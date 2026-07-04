package me.manga.kira.ui.components

import androidx.compose.ui.Modifier

/**
 * No-arg system-gesture-exclusion modifier usable from `:ui` commonMain.
 *
 * On Android this delegates to `Modifier.systemGestureExclusion()`, which marks the modified element's
 * bounds as a region the OS should NOT treat as an edge / predictive-back gesture. iOS and desktop
 * have no equivalent edge-gesture conflict, so their actuals are no-ops (return [Modifier] unchanged).
 *
 * ## Why this exists
 * The fast-scroller thumb ([VerticalFastScroller] / [VerticalGridFastScroller]) sits on the layout
 * end edge — exactly where Android's predictive-back / edge-swipe gesture lives. Without excluding the
 * thumb region, an edge swipe that begins on the thumb can be consumed by the OS back gesture instead
 * of dragging the thumb. The native `LazyVerticalScrollerWithScrollBar` applies
 * `systemGestureExclusion()` to the thumb under the same visibility condition; this seam restores that
 * parity in the multiplatform `:ui` module (the helper used to live in `:composeApp`, which `:ui`
 * cannot depend on — so it is lifted here as an expect/actual instead, mirroring the `BackHandler`
 * seam already in `ui/util`).
 *
 * Call-site (matching native):
 * ```
 * .then(if (isThumbVisible && !isThumbDragged && !isScrollInProgress) Modifier.gestureExclusion() else Modifier)
 * ```
 */
expect fun Modifier.gestureExclusion(): Modifier
