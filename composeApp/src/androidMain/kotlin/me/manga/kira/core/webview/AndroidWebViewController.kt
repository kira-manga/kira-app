package me.manga.kira.core.webview

import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal class AndroidWebViewController : WebViewController {
    private val mutableState = MutableStateFlow(WebViewNavState(initialization = WebViewInitialization.Initializing))
    override val state: StateFlow<WebViewNavState> = mutableState
    private var attempt: AndroidWebViewAttempt? = null
    private var owned: OwnedAndroidWebView? = null
    private val liveView: WebView? get() = owned?.takeUnless { it.isReleased }?.view

    fun isCurrent(candidate: AndroidWebViewAttempt): Boolean = attempt === candidate

    fun begin(candidate: AndroidWebViewAttempt) {
        attempt = candidate
        owned = null
        mutableState.value = WebViewNavState(initialization = WebViewInitialization.Initializing)
    }

    fun complete(
        candidate: AndroidWebViewAttempt,
        result: OwnedAndroidWebView?,
    ) {
        if (!isCurrent(candidate)) return
        owned = result
        mutableState.value =
            WebViewNavState(
                initialization = if (result == null) WebViewInitialization.Unavailable else WebViewInitialization.Ready,
            )
    }

    fun detach(candidate: AndroidWebViewAttempt) {
        if (!isCurrent(candidate)) return
        attempt = null
        owned = null
        mutableState.value = WebViewNavState(initialization = WebViewInitialization.Initializing)
    }

    fun onLoadingChanged(
        isLoading: Boolean,
        view: WebView,
    ) {
        if (liveView !== view) return
        mutableState.update {
            it.copy(
                isLoading = isLoading,
                canGoBack = view.canGoBack(),
                canGoForward = view.canGoForward(),
                progress = if (isLoading) it.progress else null,
            )
        }
    }

    fun onProgressChanged(
        progress: Int,
        view: WebView,
    ) {
        if (liveView !== view) return
        mutableState.update {
            it.copy(
                progress = progress / COMPLETE_PROGRESS.toFloat(),
                isLoading = progress < COMPLETE_PROGRESS,
                canGoBack = view.canGoBack(),
                canGoForward = view.canGoForward(),
            )
        }
    }

    override fun goBack() {
        liveView?.let { if (it.canGoBack()) it.goBack() }
    }

    override fun goForward() {
        liveView?.let { if (it.canGoForward()) it.goForward() }
    }

    override fun reload() {
        liveView?.reload()
    }

    private companion object {
        const val COMPLETE_PROGRESS = 100
    }
}
