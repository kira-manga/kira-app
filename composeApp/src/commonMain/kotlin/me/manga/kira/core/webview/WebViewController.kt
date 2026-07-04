package me.manga.kira.core.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.StateFlow

/**
 * Navigation state for an embedded [WebViewHost]. Surfaced by a [WebViewController] so the host
 * screen can drive Back / Forward / Reload affordances and a progress bar without owning any
 * platform WebView APIs itself.
 *
 * @property canGoBack whether the underlying WebView has a previous entry in its history stack.
 * @property canGoForward whether the underlying WebView has a forward entry in its history stack.
 * @property isLoading whether a navigation is currently in flight (page started but not finished).
 * @property progress determinate load progress in `0f..1f`, or `null` when the platform cannot
 *   surface a percentage (e.g. JCEF/Desktop has no progress channel) — callers should fall back to
 *   an indeterminate indicator in that case.
 */
@Immutable
data class WebViewNavState(
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Float? = null,
)

/**
 * Platform-agnostic handle for driving an embedded [WebViewHost]'s in-page navigation. Obtain one
 * via [rememberWebViewController] and pass it to [WebViewHost]; the host actual attaches the live
 * WebView to the controller and pushes [WebViewNavState] updates as navigation progresses.
 *
 * Commands ([goBack] / [goForward] / [reload]) are no-ops until a WebView has been attached by the
 * host, and are also safe to call when the corresponding [WebViewNavState] flag is false.
 */
@Stable
interface WebViewController {
    /** Latest navigation state. Collect with `collectAsState()` to recompose the nav controls. */
    val state: StateFlow<WebViewNavState>

    /** Navigate one entry back in the WebView history, if [WebViewNavState.canGoBack]. */
    fun goBack()

    /** Navigate one entry forward in the WebView history, if [WebViewNavState.canGoForward]. */
    fun goForward()

    /** Reload the current page. */
    fun reload()
}

/**
 * Remember a platform [WebViewController] for the lifetime of the composition. Pass the returned
 * instance to [WebViewHost] via its `controller` parameter to receive navigation-state updates and
 * issue Back / Forward / Reload commands.
 */
@Composable
expect fun rememberWebViewController(): WebViewController
