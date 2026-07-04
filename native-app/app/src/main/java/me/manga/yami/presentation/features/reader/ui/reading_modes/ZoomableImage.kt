package me.manga.yamiapk.presentation.features.reader.ui.reading_modes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import me.manga.yamiapk.di.coli.getImageLoader
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

@Composable
fun ZoomableImage(
    request: ImageRequest,
    painter: AsyncImagePainter,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {}
) {
    // 1) Keep track of the pinch-to-zoom scale (no pan offset)

    Box(
        modifier = modifier

    ) {
        val state by painter.state.collectAsState()

        when (state) {
            is AsyncImagePainter.State.Loading -> {
                // While loading: placeholder with known minimum height = 600.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 600.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is AsyncImagePainter.State.Error -> {
                // On error: keep same min height so scroll works
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 600.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // (You could show a retry icon here)
                }
            }

            is AsyncImagePainter.State.Success -> {
                // Once loaded: apply scale so the layout itself grows
                ZoomableAsyncImage(
                    model = request,

                    imageLoader = getImageLoader(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    onClick = { _: Offset ->
                        onTap()
                    }
                )
            }

            else -> {
                // Fallback placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 600.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

