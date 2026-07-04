    package me.manga.yamiapk.core.util

    import androidx.compose.runtime.Composable
    import androidx.compose.runtime.LaunchedEffect
    import androidx.compose.runtime.getValue
    import androidx.compose.runtime.mutableIntStateOf
    import androidx.compose.runtime.mutableStateOf
    import androidx.compose.runtime.remember
    import androidx.compose.runtime.setValue
    import me.manga.yamiapk.core.states.State
    import me.manga.yamiapk.work.WebViewDialog

    /**
     * Reusable composable that monitors a State for 403 errors and shows WebViewDialog.
     *
     * @param state The state to monitor for 403 errors
     * @param api The API/source name to pass to WebViewDialog
     * @param url The URL to pass to WebViewDialog
     * @param onDismiss Callback when dialog is dismissed (typically includes retry logic)
     */
    @Composable
    fun <T> Handle403Error(
        state: State<T>,
        api: String,
        url: String,
        onDismiss: () -> Unit,
        maxDismissals: Int = 1
    ) {
        var showWebViewDialog by remember { mutableStateOf(false) }
        var dismissCount by remember { mutableIntStateOf(0) }

        LaunchedEffect(state) {
            if (state is State.Error && state.code == 403 && dismissCount < maxDismissals) {
                showWebViewDialog = true
            }
        }

        if (showWebViewDialog) {
            WebViewDialog(
                api = api,
                initialUrl = url,
                onDismiss = {
                    showWebViewDialog = false
                    dismissCount++
                    onDismiss()
                }
            )
        }
    }


    @Composable
    fun Handle403Error(
        api: String,
        chapterUrl: String,
        onDismiss: () -> Unit
    ) {
        WebViewDialog(
            api = api,
            initialUrl = chapterUrl,
            onDismiss = onDismiss
        )
    }