package me.manga.yamiapk.presentation.features.reader.ui.reading_modes

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.Image
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.rememberAsyncImagePainter
import me.manga.yamiapk.R
import me.manga.yamiapk.di.coli.getImageLoader
import me.manga.yamiapk.presentation.features.reader.data.CompressionState
import me.manga.yamiapk.presentation.features.reader.data.ReaderItem
import me.manga.yamiapk.presentation.features.reader.ui.components.ImageLoadError
import me.manga.yamiapk.presentation.features.reader.ui.components.drawFallbackOnOOM

@Composable
fun PagerImageItem(
    item: ReaderItem.ImagePage,
    absIndex: Int,
    screenHeightDb: Dp,
    screenWidthPx: Int,
    compressionStates: Map<String, CompressionState>,
    modifier: Modifier = Modifier,
    onOpenInWebView: () -> Unit,
    onStartImageCompression: (String, Int, Image, Long, Int) -> Unit,
    onRetryCompression: (String) -> Unit
) {
    // Show compressed image if available
    if (item.isCompressed && item.compressedPainter != null) {
        Image(
            painter = item.compressedPainter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .fillMaxSize()
                .drawFallbackOnOOM(
                    fallbackIconRes = R.drawable.ic_image_broken,
                ) {
                    onOpenInWebView()
                }
        )
        return
    }

    // Handle original image loading
    val painter = rememberAsyncImagePainter(
        item.request,
        imageLoader = getImageLoader()
    )
    val imageState by painter.state.collectAsState()

    Box(modifier = modifier) {
        when (imageState) {
            is AsyncImagePainter.State.Loading -> {
                // show a spinner while loading
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is AsyncImagePainter.State.Error -> {
                // show an error icon + retry text
                ImageLoadError(
                    modifier = Modifier
                        .fillMaxSize(),
                    onRetry = {
                        painter.restart()
                    },
                    onOpenInWebView = onOpenInWebView
                )
            }

            is AsyncImagePainter.State.Success -> {
                val img = (imageState as AsyncImagePainter.State.Success).result.image
                val maxBytes = 99L * 1024 * 1024

                Log.i("PagerImageSize", "Image size: ${img.size}, Max: $maxBytes")

                if (img.size <= maxBytes) {
                    // Show image directly if under size threshold
                    SubcomposeAsyncImage(
                        model = item.request,
                        imageLoader = getImageLoader(),   // REQUIRED
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawFallbackOnOOM(
                                fallbackIconRes = R.drawable.ic_image_broken,
                            ) {
                                onOpenInWebView()
                            }
                    )
                } else {
                    // Handle large image compression
                    PagerCompressedImageHandler(
                        item = item,
                        absIndex = absIndex,
                        img = img,
                        maxBytes = maxBytes,
                        screenWidthPx = screenWidthPx,
                        screenHeightDb = screenHeightDb,
                        compressionStates = compressionStates,
                        onOpenInWebView = onOpenInWebView,
                        onStartImageCompression = onStartImageCompression,
                        onRetryCompression = onRetryCompression
                    )
                }
            }

            else -> {
                // do nothing or reserve space
            }
        }
    }
}

@Composable
private fun PagerCompressedImageHandler(
    item: ReaderItem.ImagePage,
    absIndex: Int,
    img: Image,
    maxBytes: Long,
    screenWidthPx: Int,
    screenHeightDb: Dp,
    compressionStates: Map<String, CompressionState>,
    onOpenInWebView: () -> Unit,
    onStartImageCompression: (String, Int, Image, Long, Int) -> Unit,
    onRetryCompression: (String) -> Unit
) {
    val imageUrl = item.request.data.toString()
    val compressionState = compressionStates[imageUrl]
    val density = LocalDensity.current

    // Calculate expected size based on image aspect ratio
    val expectedSize = remember(img) {
        val aspectRatio = img.height.toFloat() / img.width.toFloat()
        val screenWidthDp = with(density) { screenWidthPx.toDp() }
        val expectedHeight = (screenWidthDp * aspectRatio).coerceAtLeast(screenHeightDb)
        Pair(screenWidthDp, expectedHeight)
    }

    // Start compression if not already started
    LaunchedEffect(imageUrl) {
        if (!item.isCompressed && compressionState == null) {
            onStartImageCompression(imageUrl, absIndex, img, maxBytes, screenWidthPx)
        }
    }

    when {
        compressionState?.isCompressing == true -> {
            PagerCompressionLoadingPlaceholder(
                modifier = Modifier.fillMaxSize()
            )
        }

        compressionState?.error != null -> {
            ImageLoadError(
                modifier = Modifier.fillMaxSize(),
                message = compressionState.error,
                onRetry = { onRetryCompression(imageUrl) },
                onOpenInWebView = onOpenInWebView
            )
        }

        else -> {
            // Show loading placeholder while waiting for compression to start
            PagerCompressionLoadingPlaceholder(
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun PagerCompressionLoadingPlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Compressing image...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
    }
}