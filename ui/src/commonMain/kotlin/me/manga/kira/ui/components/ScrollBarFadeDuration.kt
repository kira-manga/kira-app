package me.manga.kira.ui.components

/**
 * Platform-configured scrollbar-thumb fade-out duration in milliseconds, usable from `:ui`
 * commonMain.
 *
 * On Android this returns `android.view.ViewConfiguration.getScrollBarFadeDuration()` — the same
 * OEM/platform-configurable value the native `LazyVerticalScrollerWithScrollBar` uses for its
 * fade-out animation spec, so the fast scroller fades exactly like every other scrollbar on that
 * device. iOS and desktop have no equivalent platform setting, so they return the historical Android
 * default of 250ms.
 *
 * ## Why this exists
 * The fast-scroller thumb ([VerticalFastScroller] / [VerticalGridFastScroller]) fades out after
 * scroll activity stops. The native implementation derives that duration from `ViewConfiguration`;
 * the earlier `:composeApp` port hardcoded 250ms because `android.view.ViewConfiguration` is not
 * reachable from common code. This expect/actual seam restores exact platform parity, mirroring the
 * sibling [gestureExclusion] seam already in this component set.
 */
expect fun scrollBarFadeDurationMs(): Int
