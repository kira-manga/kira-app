package me.manga.yamiapk.presentation.features.settings.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.manga.yamiapk.R
import me.manga.yamiapk.core.cbz.ConversionProgress

@Composable
fun CbzConversionDialog(
    conversionProgress: ConversionProgress,
    onDismiss: () -> Unit
) {
    if (!conversionProgress.isConverting &&
        conversionProgress.error == null &&
        conversionProgress.successMessage == null) {
        return
    }

    Dialog(
        onDismissRequest = {
            // Only allow dismissing if conversion is complete
            if (!conversionProgress.isConverting) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !conversionProgress.isConverting,
            dismissOnClickOutside = !conversionProgress.isConverting
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    // Error State
                    conversionProgress.error != null -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Error",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.conversion_failed),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = conversionProgress.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.close))
                        }
                    }

                    // Success State
                    conversionProgress.successMessage != null -> {
                        Icon(
                            imageVector = if (conversionProgress.wasStopped)
                                Icons.Default.Warning
                            else
                                Icons.Default.CheckCircle,
                            contentDescription = if (conversionProgress.wasStopped)
                                "Stopped"
                            else
                                "Success",
                            modifier = Modifier.size(48.dp),
                            tint = if (conversionProgress.wasStopped)
                                MaterialTheme.colorScheme.secondary
                            else
                                MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (conversionProgress.wasStopped)
                                stringResource(R.string.conversion_stopped)
                            else
                                stringResource(R.string.conversion_complete_),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = conversionProgress.successMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.closure_reason_done))
                        }
                    }

                    // Converting State
                    conversionProgress.isConverting -> {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Warning",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.converting_to_cbz),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.please_don_t_close_the_app_until_conversion_is_complete),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Progress Indicator
                        val progress = if (conversionProgress.totalChapters > 0) {
                            conversionProgress.convertedChapters.toFloat() / conversionProgress.totalChapters.toFloat()
                        } else {
                            0f
                        }

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Progress Text
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.completed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${conversionProgress.convertedChapters} / ${conversionProgress.totalChapters}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.remaining),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${conversionProgress.totalChapters - conversionProgress.convertedChapters}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }

                        // Current Chapter Info
                        if (conversionProgress.currentMangaTitle.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Divider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(modifier = Modifier.height(16.dp))

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = stringResource(R.string.current),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = conversionProgress.currentMangaTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Text(
                                    text = stringResource(
                                        R.string.chapter,
                                        conversionProgress.currentChapterNumber
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Show spinning progress indicator
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Stop Button
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.stop_conversion))
                        }
                    }
                }
            }
        }
    }
}