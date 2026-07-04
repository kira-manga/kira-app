package me.manga.kira.ui.components

import android.view.ViewConfiguration

/**
 * Android actual — returns `ViewConfiguration.getScrollBarFadeDuration()`, the platform/OEM-configured
 * scrollbar fade duration. This is the exact value the native `LazyVerticalScrollerWithScrollBar`
 * feeds into its fade-out animation spec, so the multiplatform fast scroller fades identically.
 */
actual fun scrollBarFadeDurationMs(): Int = ViewConfiguration.getScrollBarFadeDuration()
