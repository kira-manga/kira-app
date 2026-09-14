package me.manga.kira.core.webview

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

internal data class AndroidWebViewCallbacks(
    val url: String,
    val onPageFinished: (String) -> Unit,
    val onCookiesAvailable: (String) -> Unit,
    val onUserAgentResolved: (String) -> Unit,
    val allowNavigation: ((String, Boolean) -> Boolean)?,
)

internal class AndroidWebViewClients(
    attempt: AndroidWebViewAttempt,
    current: () -> AndroidWebViewCallbacks,
) {
    val navigation: WebViewClient = AttemptNavigationClient(attempt, current)
    val progress: WebChromeClient =
        object : WebChromeClient() {
            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int,
            ) {
                if (view != null) attempt.progress(newProgress, view)
            }
        }
}

private class AttemptNavigationClient(
    private val attempt: AndroidWebViewAttempt,
    private val current: () -> AndroidWebViewCallbacks,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        if (!attempt.accepts(view)) return true
        // GAP-WV-01: a supplied predicate blocks off-host navigation; null preserves platform default.
        val allowed = current().allowNavigation?.invoke(request.url.toString(), request.isForMainFrame) ?: true
        return !allowed || !attempt.accepts(view)
    }

    override fun onPageStarted(
        view: WebView?,
        url: String?,
        favicon: Bitmap?,
    ) {
        if (view != null) attempt.loading(true, view)
    }

    override fun onPageFinished(
        view: WebView?,
        url: String?,
    ) {
        if (view == null || !attempt.accepts(view)) return
        val resolved = url ?: current().url
        current().onPageFinished(resolved)
        if (!attempt.accepts(view)) return
        attempt.loading(false, view)
        publishCapturedHeaders(view, resolved)
    }

    private fun publishCapturedHeaders(
        view: WebView,
        url: String,
    ) {
        if (!attempt.accepts(view)) return
        CookieManager.getInstance().getCookie(url)?.let { current().onCookiesAvailable(it) }
        if (!attempt.accepts(view)) return
        // Bug 4 layer 2: surface the actual outbound UA, paired with the captured cookie.
        view.settings.userAgentString
            ?.takeIf { it.isNotBlank() }
            ?.let { current().onUserAgentResolved(it) }
    }
}
