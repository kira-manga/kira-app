package me.manga.kira.core.webview

import android.view.ViewGroup
import android.webkit.WebView

/** Single creation-thread owner, shared by effect disposal and AndroidView.onRelease. */
internal class OwnedAndroidWebView(
    val view: WebView,
) {
    private val creationThread = Thread.currentThread()
    var isReleased = false
        private set

    @Suppress("TooGenericExceptionCaught") // Stop failures still require destruction and critical propagation.
    fun release() {
        if (isReleased) return
        check(Thread.currentThread() === creationThread) { "WebView must be released on its creation thread" }
        isReleased = true
        // If removal fails, propagate rather than destroy a view still in the view system.
        (view.parent as? ViewGroup)?.removeView(view)
        check(view.parent == null) { "WebView must leave the view system before destruction" }
        try {
            view.stopLoading()
        } catch (failure: RuntimeException) {
            destroyAfterFailure(failure)
        } catch (failure: Error) {
            destroyAfterFailure(failure)
        }
        view.destroy()
    }

    @Suppress("TooGenericExceptionCaught") // Preserve original/cleanup critical-failure precedence.
    private fun destroyAfterFailure(original: Throwable): Nothing {
        val failure =
            try {
                view.destroy()
                null
            } catch (cleanup: RuntimeException) {
                webViewCleanupFailure(original, cleanup)
            } catch (cleanup: Error) {
                webViewCleanupFailure(original, cleanup)
            }
        throw failure ?: webViewCriticalCause(original) ?: original
    }
}
