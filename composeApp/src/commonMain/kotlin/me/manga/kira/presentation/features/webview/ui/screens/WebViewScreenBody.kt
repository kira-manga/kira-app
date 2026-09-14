package me.manga.kira.presentation.features.webview.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import me.manga.kira.core.webview.WebViewController
import me.manga.kira.core.webview.WebViewHost
import me.manga.kira.core.webview.WebViewInitialization
import me.manga.kira.core.webview.WebViewNavState
import me.manga.kira.core.webview.WebViewUrlSandbox
import me.manga.kira.ui.theme.LocalSpacing

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
@Composable
internal fun WebViewScreenBody(
    initialUrl: String,
    controller: WebViewController,
    nav: WebViewNavState,
    capture: WebViewScreenCapture,
    modifier: Modifier,
) {
    // GAP-WV-01: retain the initial-host sandbox, not a new source-ownership policy (App41).
    val sandbox = remember(initialUrl) { WebViewUrlSandbox(initialUrl) }
    val allowNavigation = remember(sandbox) { { url: String, mainFrame: Boolean -> sandbox.isAllowed(url, mainFrame) } }
    Column(modifier) {
        if (nav.initialization == WebViewInitialization.Ready && nav.isLoading) WebViewProgress(nav.progress)
        WebViewHost(
            url = initialUrl,
            onPageFinished = capture.onPageFinished,
            onCookiesAvailable = capture.onCookiesAvailable,
            onUserAgentResolved = capture.onUserAgentResolved,
            controller = controller,
            allowNavigation = allowNavigation,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
@Composable
private fun WebViewProgress(progress: Float?) {
    val modifier = Modifier.fillMaxWidth().height(LocalSpacing.current.xs)
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier = modifier)
    }
}
