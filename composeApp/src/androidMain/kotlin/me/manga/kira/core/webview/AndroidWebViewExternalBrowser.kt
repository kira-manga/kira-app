package me.manga.kira.core.webview

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI
import java.net.URISyntaxException

internal fun externalWebViewUrl(url: String): String? {
    if (url.isBlank() || url.any { it.isWhitespace() || it.isISOControl() }) return null
    val parsed =
        try {
            URI(url)
        } catch (_: URISyntaxException) {
            null
        }
    return parsed?.takeIf(::isExternalBrowserUri)?.let { url }
}

private fun isExternalBrowserUri(parsed: URI): Boolean =
    (parsed.scheme.equals("https", true) || parsed.scheme.equals("http", true)) &&
        !parsed.host.isNullOrBlank() &&
        parsed.rawUserInfo == null &&
        (parsed.port == -1 || parsed.port in 1..MAX_NETWORK_PORT)

internal fun openWebViewExternally(
    context: Context,
    callbacks: AndroidWebViewCallbacks,
    launch: (Context, String) -> Boolean,
): Boolean {
    val url = externalWebViewUrl(callbacks.url) ?: return false
    return callbacks.allowNavigation?.invoke(url, true) != false && launch(context, url)
}

internal fun launchWebViewExternalBrowser(
    context: Context,
    url: String,
): Boolean =
    try {
        // No headers, authentication extras, result callback or cookie import from the browser.
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (failure: ActivityNotFoundException) {
        webViewCriticalCause(failure)?.let { throw it }
        false
    } catch (failure: SecurityException) {
        webViewCriticalCause(failure)?.let { throw it }
        false
    }

private const val MAX_NETWORK_PORT = 65535
