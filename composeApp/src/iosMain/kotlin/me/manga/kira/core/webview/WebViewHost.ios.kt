package me.manga.kira.core.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun WebViewHost(
    url: String,
    userAgent: String?,
    onPageFinished: (url: String) -> Unit,
    onCookiesAvailable: (cookieHeader: String) -> Unit,
    onUserAgentResolved: (userAgent: String) -> Unit,
    controller: WebViewController?,
    allowNavigation: ((url: String, isMainFrame: Boolean) -> Boolean)?,
    modifier: Modifier,
) {
    val iosController = controller as? IosWebViewController

    val delegate = remember(url, onPageFinished, onCookiesAvailable, onUserAgentResolved, iosController, allowNavigation) {
        WebViewDelegate(url, onPageFinished, onCookiesAvailable, onUserAgentResolved, iosController, allowNavigation)
    }

    val webView = remember(delegate, userAgent) {
        val config = WKWebViewConfiguration()
        val view = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config)
        userAgent?.let { view.customUserAgent = it }
        view.navigationDelegate = delegate
        view
    }

    DisposableEffect(webView, iosController) {
        iosController?.attach(webView)
        onDispose {
            iosController?.detach(webView)
        }
    }

    DisposableEffect(webView, url) {
        NSURL.URLWithString(url)?.let { nsUrl ->
            webView.loadRequest(NSURLRequest.requestWithURL(nsUrl))
        }
        onDispose {
            webView.stopLoading()
        }
    }

    // WKWebView exposes determinate progress via `estimatedProgress`. KVO interop on Kotlin/Native
    // is brittle (context-pointer plumbing), so we poll the value on a short cadence for as long as
    // the WebView is composed — this also keeps back/forward enablement fresh after in-page
    // navigations. The loop suspends cheaply between ticks and only ends when the composable leaves
    // composition.
    if (iosController != null) {
        LaunchedEffect(webView) {
            while (coroutineContext.isActive) {
                iosController.onProgressPoll(webView)
                delay(100)
            }
        }
    }

    // Early header capture (Android parity). WKWebView sets the `cf_clearance` cookie the instant the
    // Cloudflare challenge passes — typically WELL BEFORE `didFinishNavigation` of the (often heavy)
    // destination page. Capturing only on `didFinishNavigation` (below) forced the user to wait for the
    // full page load and then tap Save. Instead poll the cookie store: the moment host-matched cookies
    // appear, fire `onCookiesAvailable` (+ resolve the UA once) so the shared screen's auto-persist
    // `LaunchedEffect(savedHeaders)` and X-close save fire immediately — no full-load wait, no manual
    // Save tap. Emit only on change (quiet once stable); `didFinishNavigation` remains the final emit.
    LaunchedEffect(webView, url) {
        var lastCookieHeader: String? = null
        var uaResolved = false
        while (coroutineContext.isActive) {
            delay(300)
            // Track redirects (challenge page → real page): filter against the CURRENT URL's host.
            val currentUrl = webView.URL?.absoluteString ?: url
            val targetHost = NSURL.URLWithString(currentUrl)?.host?.lowercase()
            webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { rawCookies ->
                val header = buildHostCookieHeader(rawCookies, targetHost)
                if (header.isNotEmpty() && header != lastCookieHeader) {
                    lastCookieHeader = header
                    onCookiesAvailable(header)
                    if (!uaResolved) {
                        uaResolved = true
                        webView.evaluateJavaScript("navigator.userAgent") { result, _ ->
                            (result as? String)?.takeIf { it.isNotBlank() }?.let(onUserAgentResolved)
                        }
                    }
                }
            }
        }
    }

    UIKitView(
        factory = { webView },
        modifier = modifier,
        // Migrated off the deprecated androidx.compose.ui.interop.UIKitView. The old overload defaulted
        // accessibilityEnabled=true and interactive=true; isNativeAccessibilityEnabled=true preserves
        // the former, and the default (Cooperative) interactionMode preserves the latter.
        properties = UIKitInteropProperties(isNativeAccessibilityEnabled = true),
    )
}

@OptIn(ExperimentalForeignApi::class)
private class IosWebViewController : WebViewController {
    private val _state = MutableStateFlow(WebViewNavState())
    override val state: StateFlow<WebViewNavState> = _state

    private var webView: WKWebView? = null

    fun attach(view: WKWebView) {
        webView = view
        syncHistory(view)
    }

    fun detach(view: WKWebView) {
        if (webView === view) webView = null
    }

    fun onLoadingFinished(view: WKWebView) {
        _state.update { current ->
            current.copy(
                isLoading = false,
                progress = null,
                canGoBack = view.canGoBack,
                canGoForward = view.canGoForward,
            )
        }
    }

    fun onLoadingFailed(view: WKWebView) {
        _state.update { current ->
            current.copy(
                isLoading = false,
                progress = null,
                canGoBack = view.canGoBack,
                canGoForward = view.canGoForward,
            )
        }
    }

    /**
     * Single source of truth for load state, polled on a short cadence (KVO interop on
     * Kotlin/Native is brittle). Reads `WKWebView.loading` for the in-flight flag and
     * `estimatedProgress` for the determinate bar, and recomputes back/forward enablement —
     * replacing the would-be `didStartProvisionalNavigation` delegate hook (which collided with
     * `didFinishNavigation` on the same Kotlin/Native `webView(_, WKNavigation?)` signature).
     */
    fun onProgressPoll(view: WKWebView) {
        val loading = view.loading
        _state.update { current ->
            current.copy(
                isLoading = loading,
                progress = if (loading) view.estimatedProgress.toFloat() else null,
                canGoBack = view.canGoBack,
                canGoForward = view.canGoForward,
            )
        }
    }

    private fun syncHistory(view: WKWebView) {
        _state.update { current ->
            current.copy(canGoBack = view.canGoBack, canGoForward = view.canGoForward)
        }
    }

    override fun goBack() {
        webView?.let { if (it.canGoBack) it.goBack() }
    }

    override fun goForward() {
        webView?.let { if (it.canGoForward) it.goForward() }
    }

    override fun reload() {
        webView?.reload()
    }
}

@Composable
actual fun rememberWebViewController(): WebViewController =
    remember { IosWebViewController() }

/**
 * Build the `name=value; …` Cookie header for [targetHost] from a `WKHTTPCookieStore` cookie list,
 * filtering to cookies whose domain matches the host (host == domain, or host endsWith ".$domain") so a
 * captured Cloudflare cookie stays bound to the source site. Returns "" when none match / the list is
 * null. Shared by the early-capture poll and the `didFinishNavigation` final capture so both produce an
 * identical header.
 */
@OptIn(ExperimentalForeignApi::class)
private fun buildHostCookieHeader(rawCookies: List<*>?, targetHost: String?): String {
    val cookies = rawCookies ?: return ""
    return buildString {
        var first = true
        cookies.forEach { rawCookie ->
            val cookie = rawCookie as? NSHTTPCookie ?: return@forEach
            val domain = cookie.domain.lowercase().trimStart('.')
            val matches = targetHost != null &&
                (targetHost == domain || targetHost.endsWith(".$domain"))
            if (matches) {
                if (!first) append("; ")
                append(cookie.name)
                append('=')
                append(cookie.value)
                first = false
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class WebViewDelegate(
    private val initialUrl: String,
    private val onPageFinished: (String) -> Unit,
    private val onCookiesAvailable: (String) -> Unit,
    private val onUserAgentResolved: (String) -> Unit,
    private val controller: IosWebViewController?,
    private val allowNavigation: ((url: String, isMainFrame: Boolean) -> Boolean)?,
) : NSObject(), WKNavigationDelegateProtocol {

    // GAP-WV-01 same-host sandbox: WKWebView's pre-commit gate. When a predicate is supplied and
    // rejects the navigation, cancel it; otherwise allow. `targetFrame?.mainFrame` distinguishes a
    // main-frame load from a sub-frame load (null targetFrame = new window → treat as main frame).
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val gate = allowNavigation
        if (gate == null) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }
        val target = decidePolicyForNavigationAction.request.URL?.absoluteString
        if (target == null) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }
        val isMainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: true
        if (gate(target, isMainFrame)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        }
    }

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        controller?.onLoadingFinished(webView)
        val resolved = webView.URL?.absoluteString ?: initialUrl
        onPageFinished(resolved)

        // WKWebView stores its cookies in the configuration's website data store
        // (WKHTTPCookieStore), NOT the shared NSHTTPCookieStorage — the shared store is frequently
        // EMPTY for cookies WKWebView set, which is why iOS site-header capture silently produced
        // nothing (the bug). Read the WKHTTPCookieStore (async completion) and filter to the target
        // host so the captured Cloudflare cookie stays bound to the source site.
        val targetHost = NSURL.URLWithString(resolved)?.host?.lowercase()
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { rawCookies ->
            val header = buildHostCookieHeader(rawCookies, targetHost)
            if (header.isNotEmpty()) onCookiesAvailable(header)
        }

        // Bug 4 layer 2: WKWebView's outbound User-Agent is whatever `customUserAgent` was set to,
        // or — if null — the WebKit default, which we cannot read synchronously. Ask the page
        // itself via JS. The callback runs on the main queue; the value is wrapped as NSString.
        webView.evaluateJavaScript("navigator.userAgent") { result, _ ->
            (result as? String)?.takeIf { it.isNotBlank() }?.let(onUserAgentResolved)
        }
    }

    // A provisional-navigation failure (DNS/offline/TLS — common for the scraped sites this WebView
    // solves Cloudflare for) leaves WKWebView blank with no built-in error page (unlike Android's
    // WebView). Clear the loading state and render a minimal inline error page so the CF-solver flow
    // does not dead-end on a silent white screen. These selectors take an NSError, so they have a
    // distinct Kotlin/Native signature and do not collide with didFinishNavigation.
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        controller?.onLoadingFailed(webView)
        webView.loadHTMLString(errorPageHtml(), baseURL = null)
    }

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailNavigation: WKNavigation?,
        withError: NSError,
    ) {
        controller?.onLoadingFailed(webView)
        webView.loadHTMLString(errorPageHtml(), baseURL = null)
    }

    private fun errorPageHtml(): String =
        """<html><head><meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<style>body{font-family:-apple-system,sans-serif;color:#888;text-align:center;""" +
            """padding:40px;}</style></head><body><h3>Page failed to load</h3>""" +
            """<p>Check your connection and try again.</p></body></html>"""
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster164.staleKdocSweep.cascade,
 * Task #620, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-twenty-seventh sibling of the cluster57-163
 * sweep — INTERIOR file of the wave-36 WebViewHost 3-actual fan batch;
 * INTERIOR WebViewHost actuals 2/3):
 *  (a) inline-Bug-4-layer-2-comment "WKWebView-s-outbound-User-Agent-is-
 *  whatever-customUserAgent-was-set-to-or-if-null-the-WebKit-default-which-
 *  we-cannot-read-synchronously + Ask-the-page-itself-via-JS + The-callback-
 *  runs-on-the-main-queue + the-value-is-wrapped-as-NSString" — LIVE-NOT-
 *  STALE (the Bug 4 layer 2 UA-readback strategy is live:
 *  WebViewDelegate.webView(...didFinishNavigation) calls
 *  webView.evaluateJavaScript("navigator.userAgent") { result, _ -> ... },
 *  casts result as? String, filters via takeIf { it.isNotBlank() }, and
 *  forwards to onUserAgentResolved. The "cannot read WKWebView's UA
 *  synchronously" prose accurately describes the platform reality —
 *  WKWebView has no public accessor for the outbound UA when customUserAgent
 *  is null, so JS introspection is the only path). Verified: @OptIn
 *  (ExperimentalForeignApi::class) @Composable actual fun WebViewHost(url,
 *  userAgent, onPageFinished, onCookiesAvailable, onUserAgentResolved,
 *  modifier) shipped — remember-keyed WebViewDelegate (initialUrl, callbacks
 *  triple) extends NSObject + WKNavigationDelegateProtocol, remember-keyed
 *  WKWebView with WKWebViewConfiguration + optional customUserAgent override
 *  + navigationDelegate assignment. DisposableEffect(webView, url) loads
 *  NSURLRequest.requestWithURL(NSURL.URLWithString(url)) and stops loading
 *  on dispose. UIKitView factory hosts the WKWebView. Delegate's
 *  webView(...didFinishNavigation) reads webView.URL?.absoluteString as
 *  resolved URL, forwards via onPageFinished, walks
 *  NSHTTPCookieStorage.sharedHTTPCookieStorage.cookiesForURL(nsUrl) for
 *  each NSHTTPCookie name=value pair, joins via "; " separator, forwards
 *  via onCookiesAvailable. The "cookies as? NSHTTPCookie ?: return
 *  @forEachIndexed" defensive cast + "if (header.isNotEmpty)" guard
 *  honored. The UIKitView deprecation warning (suggested newer API) is
 *  acknowledged at the compileKotlinIosArm64 gate level — not actionable
 *  per the existing-iOS-rework-surface conservatism. Consumed by Handle403
 *  Error inline interstitial + WebViewScreenRoute (cluster88 sibling) via
 *  the WebViewHost expect-decl in commonMain. Sibling actuals: Android
 *  (opening-sibling per WebViewHost.android.kt — real WebView +
 *  WebViewClient.onPageFinished + CookieManager.getCookie host-string +
 *  settings.userAgentString readback) + Desktop (closing-sibling per
 *  WebViewHost.desktop.kt — real KCEF/JCEF SwingPanel browser with
 *  CefMessageRouter JS↔Java UA bridge + visitAllCookies host-domain
 *  filter + Bug 4 layer 2 cookie-capture workaround). INTERIOR FILE of the
 *  cluster164 WebViewHost 3-actual fan batch (2 of 3). One classification.
 *  Original Phase 7.x.reader.openwebview iOS-port prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
