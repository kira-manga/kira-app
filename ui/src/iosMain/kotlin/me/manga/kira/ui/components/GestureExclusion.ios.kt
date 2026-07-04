package me.manga.kira.ui.components

import androidx.compose.ui.Modifier

/**
 * iOS actual — no-op. iOS has no Android-style edge / predictive-back gesture that would conflict with
 * dragging the fast-scroller thumb, so there is nothing to exclude.
 */
actual fun Modifier.gestureExclusion(): Modifier = this
