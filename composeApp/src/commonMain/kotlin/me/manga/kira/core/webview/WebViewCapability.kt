package me.manga.kira.core.webview

/**
 * Best-effort hint for whether an embedded [WebViewHost] is available on this platform/run.
 *
 * Android checks the optional system feature and current provider without loading WebView. A
 * positive result can immediately become stale; the Android host also guards creation and setup.
 * iOS uses the system-provided `WKWebView`.
 * Desktop uses KCEF, which is hard-skipped on macOS (see `Main.kt` + [KcefState]) — there the
 * WebView screen only ever shows a placeholder, so auto-routing the Cloudflare solver into it would
 * strand the user. Callers should fall back to the error pane (open-in-browser) when this is false.
 */
expect fun isEmbeddedWebViewAvailable(): Boolean
