package me.manga.kira.core.webview

import android.content.Context
import android.webkit.WebView

/** Robolectric view only: no URL load reaches a provider or the network. */
internal class RecordingWebView(
    context: Context,
) : WebView(context) {
    val creationThread: Thread = Thread.currentThread()
    val loads = mutableListOf<String>()
    var stopCalls = 0
    var destroyCalls = 0
    var backCalls = 0
    var reloadCalls = 0
    var hasHistory = false
    var destroyed = false
        private set
    var detachedAtDestroy = false
        private set
    var destroyThread: Thread? = null
        private set
    var stopFailure: Throwable? = null
    var destroyFailure: Throwable? = null

    override fun loadUrl(url: String) {
        check(!destroyed)
        loads += url
    }

    override fun canGoBack(): Boolean {
        check(!destroyed)
        return hasHistory
    }

    override fun canGoForward(): Boolean {
        check(!destroyed)
        return false
    }

    override fun goBack() {
        check(!destroyed)
        backCalls++
        hasHistory = false
    }

    override fun reload() {
        check(!destroyed)
        reloadCalls++
    }

    override fun stopLoading() {
        check(!destroyed)
        stopCalls++
        stopFailure?.let { throw it }
    }

    override fun destroy() {
        check(!destroyed)
        destroyCalls++
        detachedAtDestroy = parent == null
        destroyThread = Thread.currentThread()
        destroyed = true
        destroyFailure?.let { throw it }
        super.destroy()
    }
}
