package me.manga.kira.ui.components

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

/**
 * Android actual — delegates to `Modifier.systemGestureExclusion()` so the modified element's bounds
 * are excluded from the OS edge / predictive-back gesture region. This is what stops an edge swipe
 * starting on the fast-scroller thumb from being swallowed by the system back gesture instead of
 * dragging the thumb.
 */
actual fun Modifier.gestureExclusion(): Modifier = this.systemGestureExclusion()
