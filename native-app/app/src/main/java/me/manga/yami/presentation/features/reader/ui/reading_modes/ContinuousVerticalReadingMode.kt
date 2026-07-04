package me.manga.yamiapk.presentation.features.reader.ui.reading_modes

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.Image
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.rememberAsyncImagePainter
import me.manga.yamiapk.R
import me.manga.yamiapk.di.coli.getImageLoader
import me.manga.yamiapk.presentation.common.componants.isScrolledToTheEnd
import me.manga.yamiapk.presentation.features.reader.data.CompressionState
import me.manga.yamiapk.presentation.features.reader.data.ReaderItem
import me.manga.yamiapk.presentation.features.reader.ui.components.ImageLoadError
import me.manga.yamiapk.presentation.features.reader.ui.components.NextChapterCard
import me.manga.yamiapk.presentation.features.reader.ui.components.drawFallbackOnOOM
import me.manga.yamiapk.presentation.features.reader.ui.components.errorCard
import net.engawapg.lib.zoomable.ExperimentalZoomableApi
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomableWithScroll

@Composable
fun ContinuousVerticalReadingMode(
    readerItems: List<ReaderItem>,
    listState: LazyListState,
    scrollTarget: Int?,
    screenHeightDb: Dp,
    screenWidthPx: Int,
    isCurrentChapLoading: Boolean,
    compressionStates: Map<String, CompressionState>,
    onOpenInWebView: () -> Unit,
    loadingNextChapter: () -> Unit,
    onTap: () -> Unit,
    onStartImageCompression: (String, Int, Image, Long, Int) -> Unit,
    onRetryCompression: (String) -> Unit
) {
    ContinuousVerticalReader(
        readerItems = readerItems,
        listState = listState,
        targetIndex = scrollTarget,
        screenHeightDb = screenHeightDb,
        screenWidthPx = screenWidthPx,
        isCurrentChapLoading = isCurrentChapLoading,
        compressionStates = compressionStates,
        onOpenInWebView = onOpenInWebView,
        onTap = onTap,
        onStartImageCompression = onStartImageCompression,
        onRetryCompression = onRetryCompression
    )

    var alreadyLoading by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrolledToTheEnd() }
            .collect { reachedEnd ->
                if (reachedEnd && !alreadyLoading) {
                    alreadyLoading = true
                    loadingNextChapter()
                } else if (!reachedEnd && alreadyLoading) {
                    alreadyLoading = false
                }
            }
    }
}

@OptIn(ExperimentalZoomableApi::class)
@Composable
fun ContinuousVerticalReader(
    readerItems: List<ReaderItem>,
    listState: LazyListState,
    targetIndex: Int?,
    screenHeightDb: Dp,
    screenWidthPx: Int,
    isCurrentChapLoading: Boolean,
    compressionStates: Map<String, CompressionState>,
    onOpenInWebView: () -> Unit,
    onTap: () -> Unit = {},
    onStartImageCompression: (String, Int, Image, Long, Int) -> Unit,
    onRetryCompression: (String) -> Unit
) {
    // 1) Whenever targetIndex changes, immediately jump there:
    LaunchedEffect(targetIndex) {
        targetIndex?.let { idx ->
            listState.scrollToItem(idx)
        }
    }

    val context = LocalContext.current
    // 2) The LazyColumn: each row now supplies the fillMaxWidth()/heightIn(min=600.dp)
    //    directly into ZoomableImage so the List can measure correct height.
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .zoomableWithScroll(
                rememberZoomState(),
                onTap = {
                    onTap()
                })
            .background(MaterialTheme.colorScheme.background)
    ) {
        itemsIndexed(
            items = readerItems,
            key = { index, item ->
                when (item) {
                    is ReaderItem.ImagePage -> "${item.chapterIndex}-${item.request.data}-${index}"
                    else -> "${index}-${item.javaClass.simpleName}"
                }
            }
        ) { absIndex, item ->
            when (item) {
                is ReaderItem.ImagePage -> {
                    ContinuousVerticalImageItem(
                        item = item,
                        absIndex = absIndex,
                        screenHeightDb = screenHeightDb,
                        screenWidthPx = screenWidthPx,
                        compressionStates = compressionStates,
                        onOpenInWebView = onOpenInWebView,
                        onStartImageCompression = onStartImageCompression,
                        onRetryCompression = onRetryCompression
                    )
                }

                is ReaderItem.NextChapterOverlay -> {
                    NextChapterCard(
                        currentChapter = item.currentChapter,
                        nextChapter = item.nextChapter,
                        screenHig = screenHeightDb,
                        isCurrentChapLoading = isCurrentChapLoading,
                        onGoToNext = {
                            // Parent's effect watching scroll-end will load next chapter
                        }
                    )
                }

                is ReaderItem.ErrorOverlay -> {
                    errorCard(
                        currentChapter = item.currentChapter,
                        errorMassege = item.errorMassage,
                        screenHig = screenHeightDb,
                    )
                }
            }
        }

        if (isCurrentChapLoading) {
            item {
                // This will cover the entire reader area with a semi‐transparent overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(color = MaterialTheme.colorScheme.background)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinuousVerticalImageItem(
    item: ReaderItem.ImagePage,
    absIndex: Int,
    screenHeightDb: Dp,
    screenWidthPx: Int,
    compressionStates: Map<String, CompressionState>,
    onOpenInWebView: () -> Unit,
    onStartImageCompression: (String, Int, Image, Long, Int) -> Unit,
    onRetryCompression: (String) -> Unit
) {
    // Show compressed image if available
    if (item.isCompressed && item.compressedPainter != null) {
        Image(
            painter = item.compressedPainter,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
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
    val state by painter.state.collectAsState()

    when (state) {
        is AsyncImagePainter.State.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = screenHeightDb),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        is AsyncImagePainter.State.Error -> {
            ImageLoadError(
                modifier = Modifier
                    .fillMaxSize()
                    .defaultMinSize(minHeight = screenHeightDb),
                onRetry = {
                    painter.restart()
                },
                onOpenInWebView = onOpenInWebView
            )
        }

        is AsyncImagePainter.State.Success -> {
            val img = (state as AsyncImagePainter.State.Success).result.image
            val maxBytes = 99L * 1024 * 1024

            Log.i("ContinuousVerticalImageSize", "Image size: ${img.size}, Max: $maxBytes")

            if (img.size <= maxBytes) {
                // Show image directly if under size threshold
                Image(
                    painter = painter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .drawFallbackOnOOM(
                            fallbackIconRes = R.drawable.ic_image_broken,
                        ) {
                            onOpenInWebView()
                        },
                    contentDescription = null
                )
            } else {
                // Handle large image compression
                ContinuousVerticalCompressedImageHandler(
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 600.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun ContinuousVerticalCompressedImageHandler(
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

    // Calculate expected height based on image aspect ratio
    val expectedHeight = remember(img) {
        val aspectRatio = img.height.toFloat() / img.width.toFloat()
        val screenWidthDp = with(density) { screenWidthPx.toDp() }
        (screenWidthDp * aspectRatio).coerceAtLeast(screenHeightDb)
    }

    // Start compression if not already started
    LaunchedEffect(imageUrl) {
        if (!item.isCompressed && compressionState == null) {
            onStartImageCompression(imageUrl, absIndex, img, maxBytes, screenWidthPx)
        }
    }

    when {
        compressionState?.isCompressing == true -> {
            CompressionLoadingPlaceholder(
                modifier = Modifier
                    .fillMaxSize()

            )
        }

        compressionState?.error != null -> {
            ImageLoadError(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(expectedHeight),
                message = compressionState.error,
                onRetry = { onRetryCompression(imageUrl) },
                onOpenInWebView = onOpenInWebView
            )
        }

        else -> {
            // Show loading placeholder while waiting for compression to start
            CompressionLoadingPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(expectedHeight)
            )
        }
    }
}

@Composable
private fun CompressionLoadingPlaceholder(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
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