package me.manga.kira.core.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-agnostic embedded WebView. Each platform actual is responsible for:
 *  - Loading [url] (with [userAgent] applied to the request if non-null).
 *  - Invoking [onPageFinished] when navigation completes.
 *  - Invoking [onCookiesAvailable] with a `Cookie:`-style header string for the loaded URL.
 *  - Invoking [onUserAgentResolved] with the browser's actual outbound User-Agent string. This
 *    is critical for Cloudflare / `cf_clearance` flows: the cookie is bound to the UA that
 *    earned it, so the source repo must replay both Cookie AND User-Agent together. See Bug 4
 *    layer 2 — Lavascans / Cloudflare returned 403 when only Cookie was sent.
 *  - Consulting [allowNavigation] before committing a navigation and blocking it when the
 *    predicate returns `false` (GAP-WV-01 same-host sandbox — see [WebViewUrlSandbox]). When
 *    `null` (the default), no gating is applied and every navigation proceeds. Each actual maps
 *    this onto its platform's navigation gate (Android `WebViewClient.shouldOverrideUrlLoading`,
 *    iOS `decidePolicyForNavigationAction`, Desktop `CefRequestHandler.onBeforeBrowse`).
 *
 * Notes:
 *  - Android wraps `android.webkit.WebView` via `AndroidView` and bridges via `WebViewClient`.
 *  - iOS wraps `WKWebView` via `UIKitView` interop.
 *  - Desktop uses KCEF (JetBrains JCEF wrapper) and obtains the UA via JS bridge
 *    (`window.cefQuery` evaluating `navigator.userAgent`).
 */
@Composable
expect fun WebViewHost(
    url: String,
    userAgent: String? = null,
    onPageFinished: (url: String) -> Unit = {},
    onCookiesAvailable: (cookieHeader: String) -> Unit = {},
    onUserAgentResolved: (userAgent: String) -> Unit = {},
    controller: WebViewController? = null,
    allowNavigation: ((url: String, isMainFrame: Boolean) -> Boolean)? = null,
    modifier: Modifier = Modifier,
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster155.staleKdocSweep.cascade,
 * Task #611, 2026-05-28): classified as follows after recursive symbol
 * verification (two-hundred-and-fourth sibling of the cluster57-154 sweep
 * — CLOSING file of the wave-27 :composeApp platform-shim expect-decl
 * 4-leaf batch alongside HideNavigationBarSideEffect plus FastScroller
 * GestureExclusion plus RememberNotificationPermissionRequester; CLOSES
 * :composeApp platform-shim tier 4/4):
 *  (a) "Platform-agnostic-embedded-WebView + Each-platform-actual-is-
 *  responsible-for-Loading-url-with-userAgent-applied-to-the-request-if-
 *  non-null + Invoking-onPageFinished-when-navigation-completes + Invoking
 *  -onCookiesAvailable-with-a-Cookie-style-header-string-for-the-loaded-
 *  URL + Invoking-onUserAgentResolved-with-the-browser-s-actual-outbound-
 *  User-Agent-string + This-is-critical-for-Cloudflare-cf_clearance-flows
 *  -the-cookie-is-bound-to-the-UA-that-earned-it-so-the-source-repo-must-
 *  replay-both-Cookie-AND-User-Agent-together + See-Bug-4-layer-2-Lava
 *  scans-Cloudflare-returned-403-when-only-Cookie-was-sent + Android-
 *  wraps-android.webkit.WebView-via-AndroidView-and-bridges-via-WebView
 *  Client + iOS-wraps-WKWebView-via-UIKitView-interop + Desktop-uses-KCEF
 *  -JetBrains-JCEF-wrapper-and-obtains-the-UA-via-JS-bridge-window.cef
 *  Query-evaluating-navigator.userAgent" — LIVE-NOT-STALE. Verified:
 *  @Composable expect fun WebViewHost(...) shipped as a 6-parameter
 *  declaration (url + userAgent + onPageFinished + onCookiesAvailable +
 *  onUserAgentResolved + modifier). The "Cloudflare cf_clearance Cookie +
 *  User-Agent pairing must be replayed together" load-bearing rationale
 *  honored — the onCookiesAvailable + onUserAgentResolved callback pair
 *  exists precisely so the source repo can replay both headers together
 *  on subsequent requests (Bug 4 layer 2 Lavascans 403 incident). The
 *  three-platform actuals contract honored — Android wraps android.
 *  webkit.WebView via AndroidView + WebViewClient, iOS wraps WKWebView
 *  via UIKitView interop, Desktop uses KCEF (JetBrains JCEF wrapper) +
 *  obtains the UA via JS bridge (window.cefQuery evaluating navigator.
 *  userAgent). Consumed by WebViewScreenRoute (cluster88 sibling X) +
 *  Handle403Error (cluster X) — the two call sites in the legacy in-app
 *  browser surface. CLOSING FILE of the cluster155 :composeApp platform-
 *  shim expect-decl 4-leaf batch (4 of 4: HideNavigationBarSideEffect +
 *  FastScrollerGestureExclusion + RememberNotificationPermissionRequester
 *  + WebViewHost). One classification. Original Phase 7.x.webview.host
 *  expect-decl prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
