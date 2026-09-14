package me.manga.kira.core.webview

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class AndroidWebViewAttempt(
    private val controller: AndroidWebViewController?,
) {
    var initialization by mutableStateOf(WebViewInitialization.Initializing)
        private set
    var owned by mutableStateOf<OwnedAndroidWebView?>(null)
        private set
    private var disposed = false

    fun begin() {
        if (disposed) return
        controller?.begin(this)
    }

    fun publish(result: OwnedAndroidWebView?) {
        if (disposed || controller?.isCurrent(this) == false) {
            result?.release()
            return
        }
        owned = result
        initialization = if (result == null) WebViewInitialization.Unavailable else WebViewInitialization.Ready
        controller?.complete(this, result)
    }

    fun accepts(view: WebView?): Boolean =
        !disposed &&
            initialization == WebViewInitialization.Ready &&
            controller?.isCurrent(this) != false &&
            owned?.isReleased == false &&
            owned?.view === view

    fun loading(
        isLoading: Boolean,
        view: WebView,
    ) {
        if (accepts(view)) controller?.onLoadingChanged(isLoading, view)
    }

    fun progress(
        progress: Int,
        view: WebView,
    ) {
        if (accepts(view)) controller?.onProgressChanged(progress, view)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        val released = owned
        owned = null
        controller?.detach(this)
        released?.release()
    }
}
