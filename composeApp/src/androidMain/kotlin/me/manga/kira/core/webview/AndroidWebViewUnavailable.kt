package me.manga.kira.core.webview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import me.manga.kira.composeapp.generated.resources.Res
import me.manga.kira.composeapp.generated.resources.webview_external_browser_failed
import me.manga.kira.composeapp.generated.resources.webview_open_external_browser
import me.manga.kira.composeapp.generated.resources.webview_unavailable_message
import me.manga.kira.composeapp.generated.resources.webview_unavailable_retry
import me.manga.kira.composeapp.generated.resources.webview_unavailable_title
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
@Composable
internal fun AndroidWebViewUnavailable(
    callbacks: State<AndroidWebViewCallbacks>,
    modifier: Modifier,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val platform = LocalAndroidWebViewPlatform.current
    val spacing = LocalSpacing.current
    var launchFailed by remember(callbacks.value.url) { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.md, Alignment.CenterVertically),
    ) {
        WebViewUnavailableExplanation(launchFailed)
        Button(onClick = onRetry) { Text(stringResource(Res.string.webview_unavailable_retry)) }
        OutlinedButton(
            onClick = { launchFailed = !openWebViewExternally(context, callbacks.value, platform.openExternal) },
            enabled = externalWebViewUrl(callbacks.value.url) != null,
        ) {
            Text(stringResource(Res.string.webview_open_external_browser))
        }
    }
}

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
@Composable
private fun WebViewUnavailableExplanation(launchFailed: Boolean) {
    Text(stringResource(Res.string.webview_unavailable_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(Res.string.webview_unavailable_message), style = MaterialTheme.typography.bodyLarge)
    if (launchFailed) {
        Text(
            stringResource(Res.string.webview_external_browser_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
