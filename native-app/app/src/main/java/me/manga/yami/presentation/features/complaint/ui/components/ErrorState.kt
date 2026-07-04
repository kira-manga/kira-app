package me.manga.yamiapk.presentation.features.complaint.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.theme.YamiMangaTheme

@Composable
fun ErrorState(
    error: State.Error,
    onRetry: (() -> Unit)? = null,
    onHelp: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val (icon, iconColor) = when (error.code) {
        0 -> Icons.Default.WifiOff to MaterialTheme.colorScheme.error // Network errors
        in 400..499 -> Icons.Default.Lock to MaterialTheme.colorScheme.error // Client errors
        in 500..599 -> Icons.Default.CloudOff to MaterialTheme.colorScheme.error // Server errors
        else -> Icons.Default.Error to MaterialTheme.colorScheme.error
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
            ),
            shape = RoundedCornerShape(16.dp),
            border = CardDefaults.outlinedCardBorder().copy(
                width = 1.dp,
                brush = SolidColor(Color.Transparent)
            )
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Error icon
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(R.string.error),
                    modifier = Modifier.size(64.dp),
                    tint = iconColor
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Error title
                Text(
                    text = stringResource(R.string.error_occurred),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))


                // Error code if available
                error.code?.let { code ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.error_code_format, code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Action buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {

                    // Retry button
                    if (onRetry != null) {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun ErrorStateNetworkPreview() {
    YamiMangaTheme(darkTheme = true) {
        ErrorState(
            error = State.Error(0, "Cannot reach server—please check your internet connection."),
            onRetry = {},
            onHelp = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ErrorStateForbiddenPreview() {
    YamiMangaTheme(darkTheme = false) {
        ErrorState(
            error = State.Error(403, "Forbidden Click On Help To Solve The Problem"),
            onRetry = {},
            onHelp = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ErrorStateServerPreview() {
    YamiMangaTheme(darkTheme = true) {
        ErrorState(
            error = State.Error(500, "Internal Server Error"),
            onRetry = {},
            onHelp = null
        )
    }
}
