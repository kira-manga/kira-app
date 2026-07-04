package me.manga.yamiapk.work

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.webview.ui.components.WebViewScreen

@Composable
fun WebViewDialog(
    api: String,
    initialUrl: String,
    onDismiss: () -> Unit
) {
    val dialogShape = RoundedCornerShape(24.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false // Allows dialog to be wider
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f) // 95% of screen width
                .fillMaxSize(0.9f)   // 90% of screen height
                .clip(dialogShape),  // clip children to the same rounded shape
            shape = dialogShape,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp) // inner padding so content doesn't touch rounded corners
            ) {
                // Header text
                Text(
                    text = stringResource(R.string.please_solve_the_bot_check),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                )

                // WebView takes remaining space and will be clipped by the parent shape
                WebViewScreen(
                    api = api,
                    initialUrl = initialUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f) // Takes all remaining space
                ) {
                    onDismiss.invoke()
                }
            }
        }
    }
}
