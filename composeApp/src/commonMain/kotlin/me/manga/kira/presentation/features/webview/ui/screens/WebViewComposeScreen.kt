@file:OptIn(ExperimentalMaterial3Api::class)

package me.manga.kira.presentation.features.webview.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.manga.kira.composeapp.generated.resources.Res
import me.manga.kira.composeapp.generated.resources.back
import me.manga.kira.composeapp.generated.resources.close
import me.manga.kira.composeapp.generated.resources.loading
import me.manga.kira.composeapp.generated.resources.webview_action_forward
import me.manga.kira.composeapp.generated.resources.webview_action_reload
import me.manga.kira.composeapp.generated.resources.webview_action_save_headers
import me.manga.kira.core.webview.WebViewHost
import me.manga.kira.core.webview.WebViewUrlSandbox
import me.manga.kira.core.webview.rememberWebViewController
import org.jetbrains.compose.resources.stringResource

/**
 * Ported (and significantly slimmed) from upstream
 * `presentation/features/webview/ui/screens/WebViewComposeScreen.kt`.
 *
 * Upstream embedded `android.webkit.WebView` directly with its own custom `WebViewClient` (URL
 * gating, host-restricted cookie capture, render-process-gone recovery, lifecycle observer,
 * recreation-on-crash loop, etc.). That is fundamentally per-platform — the equivalent on iOS is
 * `WKWebView` and on desktop is "either JCEF or fall back to system browser". The KMP rewrite
 * delegates the entire web stack to the [WebViewHost] expect/actual surface
 * (`core/webview/WebViewHost.kt`); this screen is now just a Scaffold around it.
 *
 * Deltas vs source:
 *   1. All `android.webkit.*`, `androidx.compose.ui.viewinterop.AndroidView`, `BackHandler`, and
 *      lifecycle plumbing removed. The actuals handle their platform's WebView lifecycle.
 *   2. The header capture surface is `{Cookie, User-Agent}` (Bug 4 layer 2). Saving both is
 *      required for Cloudflare flows — the `cf_clearance` cookie is bound to the UA that earned
 *      it, so replaying Cookie alone returns 403. Source's full `request.requestHeaders` capture
 *      is still narrower than upstream Android, but the UA covers the bulk of the regression.
 *   3. Forward/back navigation and reload ARE exposed, matching native's top-bar action cluster.
 *      They are driven through a platform [WebViewController] obtained via [rememberWebViewController]
 *      and handed to [WebViewHost]; the host actual attaches its live WebView/WKWebView/CEF browser
 *      and pushes `canGoBack`/`canGoForward`/`isLoading` back through the controller's state flow.
 *      (The native in-page history stack is not reconstructed in common code — each platform's
 *      WebView owns its own back/forward history, which the controller drives directly.)
 *   4. Load progress is determinate (0–100%) on platforms that report a percentage — Android via
 *      `onProgressChanged` surfaced as `nav.progress` — and falls back to an indeterminate bar where
 *      the platform has no progress channel (Desktop/KCEF, iOS-at-finish), where `nav.progress` is
 *      null. Accepted platform limitation, not a regression vs native (which is Android-only).
 *   5. `LocalContext.current` removed (only ever used to instantiate `WebView(context)`).
 *
 * The `api` and `initialUrl` parameters are kept verbatim so the route host's bridge to
 * [me.manga.kira.presentation.features.webview.ui.viewmodel.WebViewViewModel.saveHeaders] stays
 * source-compatible.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster72.staleKdocSweep.cascade,
 * Task #528, 2026-05-28): the 5 port-deltas + the 2-paragraph
 * cross-target-severance prose above are classified as follows after
 * recursive symbol verification across the KMP graph (sixteenth
 * sibling of the cluster57-71 reader/ui sweep — first file in the
 * `webview/ui/screens/` sub-package, structurally distinct as a
 * route-screen that delegates its entire platform-specific stack to
 * the [WebViewHost] expect/actual surface):
 *  (a) Cross-target-severance prose ("Upstream embedded
 *  android.webkit.WebView directly... is fundamentally per-platform
 *  — the equivalent on iOS is WKWebView and on desktop is JCEF / fall
 *  back to system browser. The KMP rewrite delegates the entire web
 *  stack to the WebViewHost expect/actual surface") — LIVE-NOT-STALE.
 *  Realized at L29 import (`me.manga.kira.core.webview.
 *  WebViewHost`) + L138-155 call site. The expect/actual delegation
 *  is the campaign-wide pattern for platform-specific UI surfaces.
 *  (b) Delta #1 — FULFILLED-PREDICTION across three sub-claims
 *  (Android-only severance):
 *   (b.1) `android.webkit.*` imports removed — L1-29 import block
 *   carries no `android.webkit.*` import. Recursive Grep for
 *   `android\.webkit` matches ZERO live references file-wide.
 *   (b.2) `androidx.compose.ui.viewinterop.AndroidView` removed —
 *   L1-29 import block carries no `AndroidView` import (Android-
 *   only; would break iOS/Desktop targets). Recursive Grep for
 *   `AndroidView` matches ZERO live references file-wide.
 *   (b.3) `BackHandler` and lifecycle plumbing removed — L1-29
 *   import block carries no `BackHandler` import; the WebViewHost
 *   actuals own their platform's WebView lifecycle.
 *  (c) Delta #2 — FULFILLED-PREDICTION. Header capture surface is
 *  `{Cookie, User-Agent}` (Bug 4 layer 2) — LIVE realization at
 *  L75-76 (`var capturedCookie by remember { mutableStateOf<String?>
 *  (null) }; var capturedUserAgent by remember { mutableStateOf
 *  <String?>(null) }`) + L80-88 `savedHeaders` derived-state
 *  recomposition that puts both keys into the resulting `Map<String,
 *  String>`. The Cloudflare-flow rationale ("`cf_clearance` cookie
 *  is bound to the UA that earned it") is realized as a strict
 *  Cookie+UA pair construction — Cookie alone returns 403 from
 *  Cloudflare-protected sources.
 *  (d) Delta #3 — REVISED-NOW-FULFILLED (P3-LOW parity sweep,
 *  Task #288): the original prose ("Forward/back navigation and
 *  reload NOT exposed" + "TODO expand WebViewHost with a
 *  WebViewController") is SUPERSEDED. Back/Forward/Reload ARE now
 *  live: the screen calls rememberWebViewController(), collects its
 *  state, and renders Back/Forward/Reload IconButtons in the TopAppBar
 *  actions gated on nav.canGoBack/canGoForward/isLoading. The
 *  WebViewController interface exists in core/webview and is consumed
 *  by all three actuals (Android/iOS/Desktop), so the old "matches
 *  ZERO references" claim no longer holds. The Delta #3 text above was
 *  rewritten to describe the implemented nav controls.
 *  (e) Delta #4 — REVISED. Load progress is DETERMINATE (0-100%) where
 *  the platform reports it: Android surfaces onProgressChanged as
 *  nav.progress and the body renders LinearProgressIndicator(progress =
 *  …); Desktop/KCEF + iOS-at-finish leave nav.progress null and fall
 *  back to the indeterminate bar. The original "always indeterminate
 *  until onPageFinished" prose is superseded — the screen-local
 *  one-shot isLoading flag was removed in favour of the controller's
 *  per-load nav.isLoading. Accepted platform limitation on the
 *  no-percentage targets, not a docs-sweep artifact.
 *  (f) Delta #5 — FULFILLED-PREDICTION. `LocalContext.current`
 *  removed — LIVE realization: L1-29 import block carries no
 *  `LocalContext` import (Android-only; only ever used to
 *  instantiate `WebView(context)` per the original prose).
 *  Recursive Grep for `LocalContext` matches ZERO live references
 *  file-wide.
 *  Five classifications (Delta #1 FULFILLED-PREDICTION across three
 *  sub-claims + Delta #2/#5 FULFILLED-PREDICTION + Delta #3/#4
 *  REVISED-NOW-FULFILLED after the P3-LOW nav-controls + determinate-
 *  progress parity sweep, plus LIVE-NOT-STALE cross-target-severance
 *  prose) STAND on their own merits as a faithful port-deltas
 *  manifest. This file is a
 *  structurally distinct sibling to the reader/ui/ sweep: where
 *  reader/ui/ delegated the Coil3 ImageLoader to a singleton setup,
 *  this file delegates the entire web stack to a [WebViewHost]
 *  expect/actual surface — the campaign-wide pattern for
 *  per-platform UI seams. Original Phase 10.3-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
@Composable
fun WebViewComposeScreen(
    api: String,
    initialUrl: String,
    modifier: Modifier = Modifier,
    onSaveHeaders: (Map<String, String>?, String) -> Unit,
    onClose: (Map<String, String>?, String) -> Unit,
) {
    // Two independent capture channels — Cookie arrives synchronously after page load on every
    // platform; User-Agent arrives synchronously on Android (`webView.settings.userAgentString`)
    // but asynchronously on iOS (`evaluateJavaScript`) and Desktop (CefMessageRouter). The
    // derived `savedHeaders` map below recomposes whenever either channel updates, so the Save
    // button enables as soon as the cookie is in hand and gets enriched with the UA when it
    // arrives shortly after.
    var capturedCookie by remember { mutableStateOf<String?>(null) }
    var capturedUserAgent by remember { mutableStateOf<String?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }

    // Navigation controls (Back / Forward / Reload + determinate progress) are driven by a
    // platform [WebViewController]; the [WebViewHost] actual attaches its live WebView and pushes
    // state updates as navigation progresses.
    val controller = rememberWebViewController()
    val nav by controller.state.collectAsState()

    // GAP-WV-01: pin the embedded browser to the host of [initialUrl]. Main-frame navigations that
    // would drift off-host are blocked so the captured Cloudflare cookie stays bound to the source
    // host the repo will later replay it to. Rebuilt only when [initialUrl] changes.
    val sandbox = remember(initialUrl) { WebViewUrlSandbox(initialUrl) }

    val savedHeaders: Map<String, String>? = remember(capturedCookie, capturedUserAgent) {
        val cookie = capturedCookie?.takeIf { it.isNotBlank() } ?: return@remember null
        buildMap {
            put("Cookie", cookie)
            capturedUserAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        // The save chain logs the final saved keys via DataStoreHelper.saveHeadersForApi, so
        // confirming `User-Agent` made it through is visible in the existing [Headers] log line.
    }

    // Auto-persist captured headers the moment they're available instead of waiting for the user to
    // tap Save. The user reported header-save working only on Android: on iOS/Desktop the Cookie and
    // User-Agent arrive ASYNCHRONOUSLY (WKWebView cookie store / CEF JS bridge), so gating the save
    // on a Save/Close tap raced the capture and frequently persisted nothing. Persisting on every
    // capture change (cookie first, then enriched once the UA lands) removes the race on all
    // platforms; `saveHeaders` no-ops on null/empty and idempotently overwrites with the latest set.
    LaunchedEffect(savedHeaders) {
        if (savedHeaders != null) {
            onSaveHeaders(savedHeaders, api)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onClose(savedHeaders, api) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(Res.string.close),
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            // P3-LOW parity: drive the title's "Loading…" text off the controller's
                            // live per-load state (same source as Back/Forward/Reload + Save) rather
                            // than the one-shot screen-local flag, so the title reflects each
                            // navigation in flight, not just the first page load.
                            text = if (nav.isLoading) stringResource(Res.string.loading) else currentUrl,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Only show the URL subtitle WHILE loading (when line 1 reads "Loading…").
                        // Once the page finishes, line 1 already shows the URL, so a second URL line
                        // would just duplicate it.
                        if (nav.isLoading) {
                            Text(
                                text = currentUrl,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { controller.goBack() },
                        enabled = nav.canGoBack && !nav.isLoading,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                    IconButton(
                        onClick = { controller.goForward() },
                        enabled = nav.canGoForward && !nav.isLoading,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = stringResource(Res.string.webview_action_forward),
                        )
                    }
                    IconButton(
                        onClick = { controller.reload() },
                        enabled = !nav.isLoading,
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(Res.string.webview_action_reload),
                        )
                    }
                    IconButton(
                        onClick = { onSaveHeaders(savedHeaders, api) },
                        // P3-LOW parity (native WebViewComposeScreen.kt:465-467): the Save button is
                        // gated on the LIVE per-load loading state, not a one-shot first-finish flag.
                        // Native's `isLoading` reflects ongoing WebChromeClient progress, so Save
                        // re-disables on every subsequent navigation; the controller's `nav.isLoading`
                        // is the KMP equivalent (driven by onPageStarted/onProgressChanged on Android,
                        // onLoadingStateChange on Desktop). Using it here keeps Save disabled while any
                        // later navigation is in flight instead of staying enabled after the first load.
                        enabled = savedHeaders != null && !nav.isLoading,
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = stringResource(Res.string.webview_action_save_headers),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (nav.isLoading) {
                val progress = nav.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
            }

            // Make the host callbacks explicitly STABLE across recomposition so they don't churn
            // the iOS WebViewDelegate / Desktop KCEF browser `remember` keys (which would rebuild
            // the web view mid-load — the screen recomposes ~every 100ms while a page loads).
            // NOTE: under the Compose compiler's default strong-skipping (Kotlin 2.x) these inline
            // lambdas were already auto-memoized because their captures are stable (the
            // currentUrl/capturedCookie/capturedUserAgent MutableState delegates and the remembered
            // `sandbox`), so this is primarily DEFENSIVE/explicit — it guards against a future
            // strong-skipping opt-out or a stability regression in those captures rather than fixing
            // an observed rebuild. Capturing once is correct (writes hit the live MutableState).
            val onPageFinished = remember { { finishedUrl: String -> currentUrl = finishedUrl } }
            val onCookiesAvailable = remember {
                { cookieHeader: String -> if (cookieHeader.isNotBlank()) capturedCookie = cookieHeader }
            }
            val onUserAgentResolved = remember {
                { ua: String -> if (ua.isNotBlank()) capturedUserAgent = ua }
            }
            val allowNavigation = remember(sandbox) {
                { url: String, isMainFrame: Boolean -> sandbox.isAllowed(url, isMainFrame) }
            }
            WebViewHost(
                url = initialUrl,
                onPageFinished = onPageFinished,
                onCookiesAvailable = onCookiesAvailable,
                onUserAgentResolved = onUserAgentResolved,
                controller = controller,
                allowNavigation = allowNavigation,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
