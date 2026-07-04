package me.manga.yamiapk.presentation.features.whatsnew.ui.components


import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.whatsnew.model.MediaType

@Composable
fun FullscreenMediaViewer(
    mediaType: MediaType?,
    imageRes: Int?,
    imageUrl: String?,
    videoUrl: String?,
    onDismiss: () -> Unit
) {
    if (mediaType == null) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        when (mediaType) {
            MediaType.IMAGE -> {
                if (imageRes != null) {
                    Image(
                        painter = painterResource(id = imageRes),
                        contentDescription = stringResource(R.string.fullscreen_image),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .clickable(enabled = false) { },
                        contentScale = ContentScale.Fit
                    )
                } else if (imageUrl != null) {
                    SubcomposeAsyncImage(
                        model = imageUrl,
                        contentDescription = stringResource(R.string.fullscreen_image),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .clickable(enabled = false) { },
                        contentScale = ContentScale.Fit,
                        loading = {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = Color.White,
                                    strokeWidth = 4.dp
                                )
                            }
                        },
                        error = {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Failed to load image",
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    )
                }
            }
            MediaType.VIDEO -> {
                videoUrl?.let { url ->
                    SafeVideoPlayer(
                        videoUrl = url,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .clickable(enabled = false) { }
                    )
                }
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    CircleShape
                )
                .size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }

        Text(
            text = stringResource(R.string.tap_outside_to_close),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}