package me.manga.kira.core.webview

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.staticCompositionLocalOf

/** Scoped platform operations; the host keeps application callbacks outside this failure boundary. */
internal class AndroidWebViewPlatform(
    val probe: () -> Boolean = ::isEmbeddedWebViewAvailable,
    val create: (Context) -> WebView = ::WebView,
    val configure: (WebView, String?, AndroidWebViewClients) -> Unit = ::configureAndroidWebView,
    val openExternal: (Context, String) -> Boolean = ::launchWebViewExternalBrowser,
)

internal val LocalAndroidWebViewPlatform = staticCompositionLocalOf { AndroidWebViewPlatform() }

@Suppress("TooGenericExceptionCaught") // Preserve initialization-failure cleanup and classification.
internal fun initializeAndroidWebView(
    context: Context,
    platform: AndroidWebViewPlatform,
    userAgent: String?,
    clients: AndroidWebViewClients,
): OwnedAndroidWebView? {
    if (!platform.probe()) return null
    var owned: OwnedAndroidWebView? = null
    return try {
        owned = OwnedAndroidWebView(platform.create(context))
        platform.configure(owned.view, userAgent, clients)
        owned
    } catch (failure: RuntimeException) {
        releaseAfterWebViewFailure(owned, failure)
        val unrecoverable = webViewCriticalCause(failure) ?: failure.takeUnless(::isWebViewInitializationFailure)
        if (unrecoverable != null) throw unrecoverable
        null
    } catch (failure: Error) {
        releaseAfterWebViewFailure(owned, failure)
        throw failure
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun configureAndroidWebView(
    view: WebView,
    userAgent: String?,
    clients: AndroidWebViewClients,
) {
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        configureWebViewSecurity(this)
        // Pinch-zoom on, no on-screen zoom buttons (legacy parity).
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        userAgent?.let { userAgentString = it }
    }
    view.webViewClient = clients.navigation
    view.webChromeClient = clients.progress
}

private fun configureWebViewSecurity(settings: WebSettings) {
    // GAP-WV-06: retain the local-file/content restrictions and native mixed-content posture.
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    @Suppress("DEPRECATION")
    settings.allowFileAccessFromFileURLs = false
    @Suppress("DEPRECATION")
    settings.allowUniversalAccessFromFileURLs = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.mediaPlaybackRequiresUserGesture = true
    settings.setGeolocationEnabled(false)
    settings.setSupportMultipleWindows(false)
}
