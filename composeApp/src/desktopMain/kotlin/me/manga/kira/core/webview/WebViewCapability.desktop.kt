package me.manga.kira.core.webview

/**
 * Desktop uses KCEF. The launcher publishes [KcefState.markUnavailable] when init is hard-skipped
 * (macOS) — in that case the WebView screen only ever shows a placeholder, so the embedded WebView
 * is treated as unavailable. On Windows/Linux KCEF initializes, so it stays available.
 */
actual fun isEmbeddedWebViewAvailable(): Boolean = !KcefState.unavailable.value
