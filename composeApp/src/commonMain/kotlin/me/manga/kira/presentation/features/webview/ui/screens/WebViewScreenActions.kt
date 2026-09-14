package me.manga.kira.presentation.features.webview.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import me.manga.kira.core.webview.WebViewController
import me.manga.kira.core.webview.WebViewInitialization

internal class WebViewScreenActions(
    val controller: WebViewController,
    private val saveHeaders: () -> Unit,
    val close: () -> Unit,
) {
    fun save() {
        if (controller.state.value.initialization == WebViewInitialization.Ready) saveHeaders()
    }

    fun back() {
        val current = controller.state.value
        if (current.initialization == WebViewInitialization.Ready && current.canGoBack) {
            controller.goBack()
        } else {
            close()
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
@Composable
internal fun WebViewHeaderPersistence(
    headers: Map<String, String>?,
    initialization: WebViewInitialization,
    actions: WebViewScreenActions,
) {
    // Preserve normal auto-save on each Cookie/UA change; saving still does not await persistence.
    LaunchedEffect(headers, initialization) {
        if (headers != null) actions.save()
    }
}
