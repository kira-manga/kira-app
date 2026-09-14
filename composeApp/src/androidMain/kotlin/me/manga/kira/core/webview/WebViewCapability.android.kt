package me.manga.kira.core.webview

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.RemoteException
import android.webkit.WebView
import me.manga.kira.core.android.androidAppContextOrNull

actual fun isEmbeddedWebViewAvailable(): Boolean = probeWebViewProvider(androidAppContextOrNull())

@Suppress("TooGenericExceptionCaught") // Android's package query wraps IPC failures in RuntimeException.
internal fun probeWebViewProvider(
    context: Context?,
    hasFeature: (Context) -> Boolean = { it.packageManager.hasSystemFeature(PackageManager.FEATURE_WEBVIEW) },
    currentProvider: () -> PackageInfo? = WebView::getCurrentWebViewPackage,
): Boolean {
    if (context == null) return false
    return try {
        hasFeature(context) && currentProvider() != null
    } catch (failure: RuntimeException) {
        webViewCriticalCause(failure)?.let { throw it }
        // WebView's non-loading package query can wrap an IPC RemoteException in RuntimeException.
        if (!webViewCauses(failure).any { it is RemoteException }) throw failure
        false
    }
}
