package me.manga.kira.core.webview

/**
 * Whether an embedded [WebViewHost] can actually render and solve a challenge on this platform/run.
 *
 * Android (`android.webkit.WebView`) and iOS (`WKWebView`) always have a working embedded WebView.
 * Desktop uses KCEF, which is hard-skipped on macOS (see `Main.kt` + [KcefState]) — there the
 * WebView screen only ever shows a placeholder, so auto-routing the Cloudflare solver into it would
 * strand the user. Callers should fall back to the error pane (open-in-browser) when this is false.
 */
expect fun isEmbeddedWebViewAvailable(): Boolean
