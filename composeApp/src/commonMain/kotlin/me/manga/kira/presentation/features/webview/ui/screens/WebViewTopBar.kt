package me.manga.kira.presentation.features.webview.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import me.manga.kira.composeapp.generated.resources.Res
import me.manga.kira.composeapp.generated.resources.back
import me.manga.kira.composeapp.generated.resources.close
import me.manga.kira.composeapp.generated.resources.loading
import me.manga.kira.composeapp.generated.resources.webview_action_forward
import me.manga.kira.composeapp.generated.resources.webview_action_reload
import me.manga.kira.composeapp.generated.resources.webview_action_save_headers
import me.manga.kira.core.webview.WebViewInitialization
import me.manga.kira.core.webview.WebViewNavState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
@Composable
internal fun WebViewTopBar(url: String, nav: WebViewNavState, hasHeaders: Boolean, actions: WebViewScreenActions) {
    TopAppBar(
        navigationIcon = { WebViewToolbarButton(Icons.Default.Close, Res.string.close, true, actions.close) },
        title = { WebViewTitle(url, nav.isLoading) },
        actions = {
            WebViewNavigationActions(nav, actions)
            // Save remains gated by the live load state, not only the first page-finished event.
            WebViewToolbarButton(
                Icons.Default.Save,
                Res.string.webview_action_save_headers,
                hasHeaders && nav.initialization == WebViewInitialization.Ready && !nav.isLoading,
                actions::save,
            )
        },
    )
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
@Composable
private fun WebViewTitle(url: String, loading: Boolean) {
    Column {
        Text(
            text = if (loading) stringResource(Res.string.loading) else url,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Once loading ends, the first line already shows the URL; do not duplicate it.
        if (loading) {
            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
@Composable
private fun WebViewNavigationActions(nav: WebViewNavState, actions: WebViewScreenActions) {
    val enabled = nav.initialization == WebViewInitialization.Ready && !nav.isLoading
    WebViewToolbarButton(
        Icons.AutoMirrored.Filled.ArrowBack,
        Res.string.back,
        enabled && nav.canGoBack,
        actions.controller::goBack,
    )
    WebViewToolbarButton(
        Icons.AutoMirrored.Filled.ArrowForward,
        Res.string.webview_action_forward,
        enabled && nav.canGoForward,
        actions.controller::goForward,
    )
    WebViewToolbarButton(Icons.Default.Refresh, Res.string.webview_action_reload, enabled, actions.controller::reload)
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
@Composable
private fun WebViewToolbarButton(icon: ImageVector, label: StringResource, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = stringResource(label)) }
}
