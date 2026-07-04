package me.manga.yamiapk.presentation.features.library.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R

@Composable
fun DownloadProgressDialog(
    visible: Boolean,
    allCaptersCount : Int,
    downloadedChapters:Int,
    progressFraction: Float,       // 0f..1f
    onStop: () -> Unit,            // stops/cancels the download
    onContinue: () -> Unit         // simply dismisses the dialog
) {
    if (!visible) return

    // Format the start date/time:

    AlertDialog(
        onDismissRequest = onContinue,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.downloading_title), style = MaterialTheme.typography.titleLarge) },

        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.downloaded_info, downloadedChapters, allCaptersCount),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                // A horizontal progress bar
                LinearProgressIndicator(
                    progress = progressFraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .padding(horizontal = 0.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.percent_completed, (progressFraction * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                TextButton(onClick = onContinue) { Text(stringResource(R.string.continue_string)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }

        }
    )
}