package me.manga.kira.presentation.features.webview.ui.screens

/** Route-owned capture callbacks, separate from controller and toolbar actions. */
data class WebViewScreenCallbacks(
    val onSaveHeaders: (Map<String, String>?, String) -> Unit,
    val onClose: (Map<String, String>?, String) -> Unit,
)
