package me.manga.kira.presentation.features.webview.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
internal class WebViewScreenCapture(
    initialUrl: String,
) {
    var currentUrl by mutableStateOf(initialUrl)
        private set
    private var capturedCookie by mutableStateOf<String?>(null)
    private var capturedUserAgent by mutableStateOf<String?>(null)

    // Two independent capture channels: Cookie is available at page load, but UA readback is
    // asynchronous on iOS/Desktop. Preserve cookie-first saving and enrichment when the UA arrives.
    val headers: Map<String, String>? by derivedStateOf {
        val cookie = capturedCookie?.takeIf { it.isNotBlank() } ?: return@derivedStateOf null
        buildMap {
            put(COOKIE_HEADER, cookie)
            capturedUserAgent?.takeIf { it.isNotBlank() }?.let { put(USER_AGENT_HEADER, it) }
        }
    }

    // Stable callbacks avoid churning the unchanged iOS delegate / Desktop browser remember keys.
    val onPageFinished: (String) -> Unit = { currentUrl = it }
    val onCookiesAvailable: (String) -> Unit = { if (it.isNotBlank()) capturedCookie = it }
    val onUserAgentResolved: (String) -> Unit = { if (it.isNotBlank()) capturedUserAgent = it }
}

private const val COOKIE_HEADER = "Cookie"
private const val USER_AGENT_HEADER = "User-Agent"
