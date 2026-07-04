package me.manga.kira.ui.components

/**
 * iOS actual — no platform scrollbar-fade setting exists, so this returns the historical Android
 * default of 250ms (the same literal the earlier port used).
 */
actual fun scrollBarFadeDurationMs(): Int = DEFAULT_SCROLLBAR_FADE_DURATION_MS

private const val DEFAULT_SCROLLBAR_FADE_DURATION_MS = 250
