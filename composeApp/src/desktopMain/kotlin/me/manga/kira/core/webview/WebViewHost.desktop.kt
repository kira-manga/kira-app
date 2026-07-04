package me.manga.kira.core.webview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFBrowser
import dev.datlag.kcef.KCEFClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.browser.CefRendering
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager

/**
 * Desktop actual for [WebViewHost]. Uses KCEF (Compose-MP wrapper around JetBrains JCEF /
 * Chromium Embedded Framework) to embed a real Chromium browser inside the Compose UI tree via
 * [SwingPanel] interop.
 *
 * **Initialization contract.** `KCEF.init { … }` is driven from the desktop `Main.kt`. On ALL OSes
 * it runs ASYNCHRONOUSLY on a background coroutine while `application { … }` opens the window
 * immediately (the first run downloads ~150-200 MB of CEF binaries, which must never block startup;
 * on macOS a blocking init under `-XstartOnFirstThread` would also deadlock CEF). This host can
 * therefore mount BEFORE init finishes. To recover from that late-init case we collect
 * [KcefState.initialized] and key the [KCEF.newClientOrNullBlocking] acquisition on it: when init
 * flips `true`, the client is re-acquired and the browser mounts. While `initialized` is still
 * `false` we show a [CircularProgressIndicator] (init in flight). If init has completed but the
 * client is still null we treat it as a definitive failure and show the "unavailable" placeholder.
 * Either way a null client degrades gracefully rather than crashing.
 *
 * **User-agent handling.** JCEF exposes user-agent overrides at app-init time
 * ([org.cef.CefSettings.user_agent]), not per-browser. Because callers expect a per-instance UA
 * override here, we apply it as a Chrome DevTools Protocol command on the browser after creation
 * using `Network.setUserAgentOverride` via `executeJavaScript` is **not** appropriate (DevTools
 * Protocol requires a different API). Instead we set it on the client by appending a request
 * handler — but to keep this implementation small, we ignore [userAgent] on Desktop today and
 * document the limitation. Auth/CAPTCHA flows on Desktop typically don't need a custom UA because
 * Chromium's default already matches what manga sources expect.
 *
 * **Cookie capture.** After `onLoadEnd` fires we call [CefCookieManager.getGlobalManager] and
 * `visitUrlCookies(url, includeHttpOnly = true, …)`. The visitor is invoked once per cookie on the
 * IO thread. We accumulate `name=value` pairs in a thread-safe list, then synthesize a standard
 * `Cookie:`-header-formatted string and hand it back to [onCookiesAvailable]. The `total`
 * parameter on the visitor signals the last call (count == total - 1), at which point we emit.
 * If no cookies match the URL the visitor isn't called at all — we emit nothing in that case,
 * matching the Android actual's behaviour of only invoking the callback when cookies exist.
 *
 * **Lifecycle.** The [CefBrowser] is closed on dispose (`close(true)` performs a forced/sync
 * shutdown — the standard for embedded panels per JCEF docs). A fresh [KCEFClient] is allocated
 * per host mount (`KCEF.newClientOrNullBlocking` runs `cefApp.createClient()` and registers a
 * native `CefMessageRouter` each call), so it is disposed per mount too — the final
 * [DisposableEffect] calls `client.dispose()` after closing the browser and disposing the
 * UA router, otherwise each visit to the Cloudflare-solver screen would leak a native CEF client.
 */
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
    val desktopController = controller as? DesktopWebViewController

    // Late-init recovery: KCEF may still be initializing (macOS async path) when this host first
    // mounts. Collect the process-wide init flag so we recompose when it flips true.
    val kcefInitialized by KcefState.initialized.collectAsState()
    // Set when the launcher skips init entirely (e.g. macOS) — lets us show the "unavailable"
    // placeholder instead of an eternal spinner for an init that will never run.
    val kcefUnavailable by KcefState.unavailable.collectAsState()

    // newClientOrNullBlocking does not throw; it returns null if KCEF state isn't `Initialized`.
    // Key the acquisition on `kcefInitialized` so a client captured as null during init is RE-
    // ACQUIRED once init completes (otherwise `remember` would cache the null forever and the
    // WebView would never recover on the async macOS path). Clients are cheap to reuse and disposing
    // is tied to KCEF teardown anyway.
    val client: KCEFClient? = remember(kcefInitialized) {
        if (kcefInitialized) KCEF.newClientOrNullBlocking { it?.printStackTrace() } else null
    }

    if (client == null) {
        // We don't forward `onPageFinished` or `onCookiesAvailable` here, so the caller's
        // loading/saved state stays in its initial form (same contract as Android when WebView is
        // disabled by device policy). Distinguish two cases:
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!kcefInitialized && !kcefUnavailable) {
                // Init still in flight (e.g. macOS async init, or first-run binary download).
                // Show progress; we'll recompose and re-acquire the client when init completes.
                CircularProgressIndicator()
            } else {
                // Either init was skipped entirely (kcefUnavailable, e.g. the macOS branch in
                // Main.kt) or it completed yet no client could be created (failed runtime, e.g. the
                // documented macOS icudtl.dat / NSBundle upstream bug). Show the unavailable
                // placeholder; no recovery is possible without a relaunch/fix.
                Text("Embedded WebView unavailable on this Desktop runtime.")
            }
        }
        return
    }

    // Bug 4 layer 2: install a JS→Java bridge via CefMessageRouter so we can ask the page for its
    // outbound `navigator.userAgent`. JCEF does not expose a per-browser UA getter, and reading
    // the value from CefSettings is unreliable (Chromium computes its default UA at runtime). The
    // bridge is registered once per client; the handler fires every time the page calls
    // `window.kiraUaQuery({request: navigator.userAgent, …})`.
    val uaRouter: CefMessageRouter = remember(client, onUserAgentResolved) {
        val config = CefMessageRouter.CefMessageRouterConfig("kiraUaQuery", "kiraUaCancel")
        val router = CefMessageRouter.create(config)
        router.addHandler(
            object : CefMessageRouterHandlerAdapter() {
                override fun onQuery(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    queryId: Long,
                    request: String?,
                    persistent: Boolean,
                    callback: CefQueryCallback?,
                ): Boolean {
                    if (!request.isNullOrBlank()) {
                        onUserAgentResolved(request)
                        callback?.success("ok")
                        return true
                    }
                    callback?.failure(0, "empty UA")
                    return false
                }
            },
            true,
        )
        client.addMessageRouter(router)
        router
    }

    val browser: KCEFBrowser = remember(client, url, allowNavigation) {
        // GAP-WV-01 same-host sandbox: gate main-frame navigations through the supplied predicate
        // via CefRequestHandler.onBeforeBrowse (returning true cancels the navigation). Registered
        // once per browser; a null predicate skips registration entirely.
        if (allowNavigation != null) {
            client.addRequestHandler(object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    request: CefRequest?,
                    userGesture: Boolean,
                    isRedirect: Boolean,
                ): Boolean {
                    val target = request?.url ?: return false
                    val isMainFrame = frame?.isMain ?: true
                    // onBeforeBrowse returns true to CANCEL → block when the predicate rejects.
                    return !allowNavigation(target, isMainFrame)
                }
            })
        }
        client.createBrowser(
            url = url,
            rendering = CefRendering.DEFAULT,
            isTransparent = false,
        ).apply {
            client.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadingStateChange(
                    cefBrowser: CefBrowser?,
                    isLoading: Boolean,
                    canGoBack: Boolean,
                    canGoForward: Boolean,
                ) {
                    // JCEF has no determinate progress channel — progress stays null and the
                    // screen falls back to an indeterminate bar while isLoading is true.
                    desktopController?.onLoadingStateChange(isLoading, canGoBack, canGoForward)
                }

                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    // We only care about the main-frame finish — sub-frame loads (iframes, ads)
                    // would otherwise re-fire callbacks per iframe.
                    if (frame?.isMain != true) return
                    val finalUrl = cefBrowser?.url ?: url
                    onPageFinished(finalUrl)
                    captureCookies(finalUrl, onCookiesAvailable)
                    // Push the UA query into the page. The router handler above turns the JS-side
                    // request into an `onUserAgentResolved(...)` call on the JVM side.
                    cefBrowser?.executeJavaScript(
                        "window.kiraUaQuery({request: navigator.userAgent, onSuccess:function(){}, onFailure:function(){}});",
                        finalUrl,
                        0,
                    )
                }
            })
        }
    }

    // Trigger the cookie/load capture even when the browser was already cached with the same URL
    // (e.g. recomposition without key change). Without this, hot recomposition can swallow the
    // first onPageFinished if the load happened before the handler was attached. KCEFBrowser is
    // resilient to repeat loadURL calls.
    LaunchedEffect(browser, url) {
        withContext(Dispatchers.IO) { browser.loadURL(url) }
    }

    DisposableEffect(browser, desktopController) {
        desktopController?.attach(browser)
        onDispose {
            desktopController?.detach(browser)
        }
    }

    // Browser lifetime is keyed ONLY on the browser, so an unrelated key change (e.g. a churning
    // uaRouter) can never force-close the still-remembered browser and leave a dead web view mounted.
    DisposableEffect(browser, client) {
        onDispose {
            // close(true) = force close + dispose native resources synchronously. Required to
            // avoid leaking the CEF helper subprocess when the screen is popped.
            browser.close(true)
            // This host allocates a fresh client per mount (cefApp.createClient() + a native
            // message router). Dispose it here, after the browser is closed, so repeated visits
            // to the CF-solver screen don't leak native CEF clients. This onDispose is declared
            // before the uaRouter effect below, so it runs last — the router has already been
            // removed/disposed by then.
            client.dispose()
        }
    }

    DisposableEffect(client, uaRouter) {
        onDispose {
            client.removeMessageRouter(uaRouter)
            uaRouter.dispose()
        }
    }

    // Suppress unused-parameter lint: `userAgent` input is for callers that want to *override* the
    // browser's UA — Bug 4 cares about *reading* the UA back via [onUserAgentResolved]. JCEF's
    // per-browser UA override path is not wired here; if a future caller needs it we can route
    // through CefSettings at KCEF.init time.
    @Suppress("UNUSED_VALUE")
    val ignoredUserAgent = userAgent

    SwingPanel(
        factory = { browser.uiComponent },
        modifier = modifier.fillMaxSize(),
    )
}

private class DesktopWebViewController : WebViewController {
    private val _state = MutableStateFlow(WebViewNavState())
    override val state: StateFlow<WebViewNavState> = _state

    private var browser: CefBrowser? = null

    fun attach(cefBrowser: CefBrowser) {
        browser = cefBrowser
    }

    fun detach(cefBrowser: CefBrowser) {
        if (browser === cefBrowser) browser = null
    }

    /** JCEF surfaces nav-button availability + loading via `onLoadingStateChange`; progress is `null`. */
    fun onLoadingStateChange(isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
        _state.update {
            it.copy(
                isLoading = isLoading,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                progress = null,
            )
        }
    }

    override fun goBack() {
        browser?.let { if (it.canGoBack()) it.goBack() }
    }

    override fun goForward() {
        browser?.let { if (it.canGoForward()) it.goForward() }
    }

    override fun reload() {
        browser?.reload()
    }
}

@Composable
actual fun rememberWebViewController(): WebViewController =
    remember { DesktopWebViewController() }

/**
 * Walk the global cookie manager for every cookie whose `domain` matches the target host (with or
 * without a leading dot), accumulate them into a `name=value; name=value` header string, and hand
 * the joined string to [onCookiesAvailable] once the visitor finishes.
 *
 * Bug 4 layer 2, Desktop follow-up: the previous implementation called
 * `visitUrlCookies(url, includeHttpOnly = true)`. In the JCEF version KCEF ships, that path
 * silently drops HttpOnly cookies (most importantly Cloudflare's `cf_clearance` and `__cf_bm`)
 * even with the flag set, AND it can miss cookies whose Set-Cookie used `Domain=.example.com`
 * (subdomain wildcard) when queried against a bare-host URL. Symptom: Lavascans / Cloudflare
 * returned 403 because only client-side cookies (`_ga`, `_ga_*`) made it into the saved header.
 *
 * `visitAllCookies` walks the entire cookie database; we filter to the target host ourselves
 * (apex match OR leading-dot domain match OR any subdomain). HttpOnly cookies are included
 * unconditionally here — KCEF exposes them via this path regardless of any flag.
 *
 * The visitor runs on the JCEF IO thread per upstream contract — we mutate a local
 * `MutableList` that's only accessed from that thread, then snapshot to a `String`. The callback
 * caller is responsible for thread safety on its side (StateFlow / setState are safe).
 */
private fun captureCookies(
    url: String,
    onCookiesAvailable: (cookieHeader: String) -> Unit,
) {
    val targetHost = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()
    if (targetHost.isNullOrBlank()) return
    val collected = mutableListOf<String>()
    val visited = CefCookieManager.getGlobalManager().visitAllCookies { cookie: CefCookie, count: Int, total: Int, _: BoolRef ->
        val cookieDomain = cookie.domain?.lowercase()
        if (cookieDomain != null && cookie.name != null && cookie.value != null) {
            val normalized = cookieDomain.trimStart('.')
            val matches =
                targetHost == normalized ||
                    targetHost.endsWith(".$normalized")
            if (matches) {
                collected += "${cookie.name}=${cookie.value}"
            }
        }
        if (count == total - 1) {
            val header = collected.joinToString("; ")
            // Cookie-name visibility for Bug 4 layer 2 debugging — values stay out of logs.
            val names = collected.map { it.substringBefore('=') }
            println("[Headers] Desktop captureCookies host=$targetHost total=$total kept=${collected.size} names=$names")
            if (header.isNotEmpty()) {
                onCookiesAvailable(header)
            }
        }
        true
    }
    if (!visited) {
        println("[Headers] Desktop captureCookies host=$targetHost visitAllCookies returned false")
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster164.staleKdocSweep.cascade,
 * Task #620, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-twenty-eighth sibling of the cluster57-163
 * sweep — CLOSING file of the wave-36 WebViewHost 3-actual fan batch;
 * CLOSES WebViewHost actuals tier 3/3):
 *  (a) "Desktop-actual-for-WebViewHost + Uses-KCEF-Compose-MP-wrapper-
 *  around-JetBrains-JCEF-Chromium-Embedded-Framework-to-embed-a-real-
 *  Chromium-browser-inside-the-Compose-UI-tree-via-SwingPanel-interop +
 *  Initialization-contract-KCEF.init-is-called-from-the-desktop-Main.kt-
 *  BEFORE-application-blocking-on-the-JCEF-JBR-binary-download-on-first-run-
 *  ~150-200-MB-cached-to-yami-kcef-bundle-afterwards + To-stay-robust-
 *  against-the-error-case-we-use-KCEF.newClientOrNullBlocking-a-null-
 *  client-triggers-a-graceful-loading-placeholder-rather-than-a-crash +
 *  User-agent-handling-JCEF-exposes-user-agent-overrides-at-app-init-time-
 *  not-per-browser + we-ignore-userAgent-on-Desktop-today-and-document-the-
 *  limitation + Auth-CAPTCHA-flows-on-Desktop-typically-don-t-need-a-custom-
 *  UA-because-Chromium-s-default-already-matches-what-manga-sources-expect +
 *  Cookie-capture-After-onLoadEnd-we-call-CefCookieManager.getGlobalManager-
 *  and-visitUrlCookies-includeHttpOnly-true + The-visitor-is-invoked-once-
 *  per-cookie-on-the-IO-thread + We-accumulate-name-value-pairs-in-a-thread-
 *  safe-list-then-synthesize-a-standard-Cookie-header-formatted-string-and-
 *  hand-it-back-to-onCookiesAvailable + If-no-cookies-match-the-URL-the-
 *  visitor-isn-t-called-at-all-we-emit-nothing-in-that-case-matching-the-
 *  Android-actual-s-behaviour + Lifecycle-The-CefBrowser-is-closed-on-
 *  dispose-close-true-performs-a-forced-sync-shutdown + The-shared-
 *  KCEFClient-is-disposed-only-on-full-KCEF-teardown-handled-in-Main.kt-
 *  since-reusing-a-single-client-across-multiple-browser-instances-is-
 *  cheaper-than-allocating-per-host" — LIVE-NOT-STALE + FACTUALLY-DRIFTED-IN-
 *  PROSE-ONLY (the class KDoc says "After onLoadEnd fires we call ...
 *  visitUrlCookies(url, includeHttpOnly = true)" — but the bottom-half
 *  captureCookies helper has been UPGRADED to visitAllCookies with manual
 *  host filtering per the Bug 4 layer 2 follow-up. The class-level prose
 *  describes the ORIGINAL strategy; the BOTTOM-HALF KDoc on captureCookies
 *  documents the upgrade explicitly: "visitUrlCookies silently drops
 *  HttpOnly cookies AND can miss subdomain-wildcard cookies → switched to
 *  visitAllCookies with manual targetHost filtering". Per §253 the original
 *  prose stays verbatim; the per-helper KDoc serves as the authoritative
 *  current-behaviour annotation. Postscript records the drift). Verified:
 *  @Composable actual fun WebViewHost(url, userAgent, onPageFinished,
 *  onCookiesAvailable, onUserAgentResolved, modifier) shipped — remember-
 *  keyed KCEF.newClientOrNullBlocking with null-client placeholder fallback
 *  ("Embedded WebView unavailable on this Desktop runtime."), Bug-4-layer-2
 *  CefMessageRouter "kiraUaQuery"/"kiraUaCancel" config with
 *  CefMessageRouterHandlerAdapter.onQuery forwarding non-blank request to
 *  onUserAgentResolved, remember-keyed KCEFBrowser via
 *  client.createBrowser(url, rendering = CefRendering.DEFAULT,
 *  isTransparent = false) with CefLoadHandlerAdapter.onLoadEnd guarded on
 *  frame.isMain (skips iframe completion fires) — emits onPageFinished
 *  (cefBrowser.url ?: url), invokes captureCookies, pushes
 *  window.kiraUaQuery JS into the page for UA readback. LaunchedEffect
 *  (browser, url) re-loads URL on hot recomposition with
 *  withContext(Dispatchers.IO). DisposableEffect closes browser via
 *  close(true) + removes/disposes uaRouter. SwingPanel hosts
 *  browser.uiComponent. Bottom captureCookies helper: visitAllCookies walks
 *  global cookie DB, normalizes cookie.domain (trimStart '.'), matches
 *  targetHost via equality OR endsWith(".$normalized") subdomain test,
 *  builds joined "name=value; name=value" header, emits via
 *  onCookiesAvailable when count == total - 1. The "userAgent input
 *  ignored" deliberate gap is annotated with @Suppress("UNUSED_VALUE") +
 *  unused-parameter lint comment. The "Bug 4 layer 2 captureCookies switch
 *  from visitUrlCookies → visitAllCookies for Cloudflare cf_clearance /
 *  __cf_bm" rationale honored — bottom-half helper does exactly that. The
 *  "[Headers] Desktop captureCookies host=$targetHost total=$total kept=
 *  ${collected.size} names=$names" println debug-trace (cookie-name
 *  visibility WITHOUT values) honored. Consumed by Handle403Error inline
 *  interstitial + WebViewScreenRoute (cluster88 sibling) via the
 *  WebViewHost expect-decl in commonMain. Sibling actuals: Android
 *  (opening-sibling per WebViewHost.android.kt — real Android WebView +
 *  WebViewClient.onPageFinished + CookieManager.getCookie host-string) +
 *  iOS (interior-sibling per WebViewHost.ios.kt — real WKWebView +
 *  WKNavigationDelegateProtocol + NSHTTPCookieStorage cookie capture +
 *  evaluateJavaScript navigator.userAgent UA readback). CLOSING FILE of
 *  the cluster164 WebViewHost 3-actual fan batch (3 of 3 — CLOSES
 *  WebViewHost actuals tier). One classification. Original Phase 7.x.
 *  reader.openwebview Desktop KCEF-port prose preserved verbatim per the
 *  audit-trail-preservation convention.
 *
 *  UPDATE (2026-06, KCEF-macOS-enablement): the client acquisition is now
 *  late-init-recovery-aware. It collects KcefState.initialized (a process-
 *  wide StateFlow set by the desktop Main.kt on KCEF init success) via
 *  collectAsState and keys remember(kcefInitialized) on it, so a null client
 *  captured during async init (the macOS path) is RE-ACQUIRED once init flips
 *  true and the browser then mounts. The null-client branch now distinguishes
 *  two states: init-still-in-flight shows a CircularProgressIndicator;
 *  init-completed-but-still-null (definitive runtime failure, e.g. the macOS
 *  icudtl.dat / NSBundle upstream bug) shows the original "Embedded WebView
 *  unavailable on this Desktop runtime." text. Windows/Linux are unaffected:
 *  their init completes (blocking) before the window opens, so the flag is
 *  already true on first mount. KcefState lives in this same package
 *  (KcefState.kt, desktopMain). macOS rendering requires on-device
 *  verification; if the upstream bug persists it degrades to the placeholder
 *  with no regression to the working Windows/Linux path.
 */
