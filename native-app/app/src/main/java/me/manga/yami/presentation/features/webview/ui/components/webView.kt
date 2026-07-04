package me.manga.yamiapk.presentation.features.webview.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import me.manga.yamiapk.presentation.features.webview.ui.screens.WebViewComposeScreen
import me.manga.yamiapk.presentation.features.webview.ui.viewmodel.WebViewViewModel
import kotlin.String

@Composable
fun WebViewScreen(
    webViewViewModel: WebViewViewModel = hiltViewModel(),
    api: String,
    initialUrl: String,
    modifier: Modifier = Modifier, // Add this parameter
    onBackPressed: () -> Unit
) {
    WebViewComposeScreen(
        api = api,
        initialUrl = initialUrl,
        modifier = modifier, // Pass it through
        onSaveHeaders = webViewViewModel::saveHeaders
    ) { header, api ->
        webViewViewModel.saveHeaders(header, api)
        onBackPressed.invoke()
    }
}