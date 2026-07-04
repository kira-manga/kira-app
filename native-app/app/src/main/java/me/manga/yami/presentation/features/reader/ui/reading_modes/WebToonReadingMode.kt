package me.manga.yamiapk.presentation.features.reader.ui.reading_modes

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.Image
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.manga.yamiapk.R
import me.manga.yamiapk.core.progress.ProgressManager
import me.manga.yamiapk.core.progress.ProgressState
import me.manga.yamiapk.core.progress.formattedPercent
import me.manga.yamiapk.core.progress.formattedSize
import me.manga.yamiapk.di.coli.getImageLoader
import me.manga.yamiapk.presentation.common.componants.isScrolledToTheEnd
import me.manga.yamiapk.presentation.features.reader.data.CompressionState
import me.manga.yamiapk.presentation.features.reader.data.ReaderItem
import me.manga.yamiapk.presentation.features.reader.data.ReaderItem.ImagePage
import me.manga.yamiapk.presentation.features.reader.ui.components.ImageLoadError
import me.manga.yamiapk.presentation.features.reader.ui.components.NextChapterCard
import me.manga.yamiapk.presentation.features.reader.ui.components.drawFallbackOnOOM
import me.manga.yamiapk.presentation.features.reader.ui.components.errorCard
import net.engawapg.lib.zoomable.ExperimentalZoomableApi
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomableWithScroll

@Composable
fun WebToonReadingMode(
    readerItems: List<ReaderItem>,
    listState: LazyListState,
    scrollTarget: Int?,
    screenHeightDb: Dp,
    screenWidthPx: Int,
    isCurrentChapLoading: Boolean,
    compressionStates: Map<String, CompressionState>,
    onLoadNextChapter: () -> Unit,
    onOpenChapterInWebView: () -> Unit,
    onTap: () -> Unit,
    onStartImageCompression: (String, Int, Image, Long, Int) -> Unit,
    onRetryCompression: (String) -> Unit
) {
    // Core WebToon scrollable reader
    WebToonReader(
        readerItems = readerItems,
        listState = listState,
        targetIndex = scrollTarget,
        onTap = onTap,
        screenHeightDb = screenHeightDb,
        screenWidthPx = screenWidthPx,
        compressionStates = compressionStates,
        onOpenChapterInWebView = onOpenChapterInWebView,
        isCurrentChapLoading = isCurrentChapLoading,
        onStartImageCompression = onStartImageCompression,
        onRetryCompression = onRetryCompression
    )

    // Load next chapter once per scroll-to-end
    var alreadyLoading by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrolledToTheEnd() }
            .collect { reachedEnd ->
                if (reachedEnd && !alreadyLoading) {
                    alreadyLoading = true
                    onLoadNextChapter()
                } else if (!reachedEnd && alreadyLoading) {
                    alreadyLoading = false
                }
            }
    }
}

@OptIn(ExperimentalZoomableApi::class)
@Composable
fun WebToonReader(
    readerItems: List<ReaderItem>,
    listState: LazyListState,
    targetIndex: Int?,
    screenHeightDb: Dp,
    screenWidthPx: Int,
    compressionStates: Map<String, CompressionState>,
    onTap: () -> Unit = {},
    onOpenChapterInWebView: () -> Unit,
    isCurrentChapLoading: Boolean,
    onStartImageCompression: (String, Int, Image, Long, Int) -> Unit,
    onRetryCompression: (String) -> Unit
) {
    LaunchedEffect(targetIndex) {
        targetIndex?.let { index ->
            listState.scrollToItem(index)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .zoomableWithScroll(
                rememberZoomState(),
                onTap = { onTap() }
            )
            .background(MaterialTheme.colorScheme.background)
    ) {
        itemsIndexed(
            items = readerItems,
            key = { index, item ->
                when (item) {
                    is ImagePage -> "${item.chapterIndex}-${item.request.data}-${index}"
                    else -> "${index}-${item.javaClass.simpleName}"
                }
            }
        ) { absIndex, item ->
            when (item) {
                is ImagePage -> {

                    WebToonImageItem(
                        item = item,
                        absIndex = absIndex,
                        screenHeightDb = screenHeightDb,
                        screenWidthPx = screenWidthPx,
                        compressionStates = compressionStates,
                        onOpenChapterInWebView = onOpenChapterInWebView,
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
                        onGoToNext = { onTap() }
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
    }
}

@Composable
private fun WebToonImageItem(
    item: ImagePage,
    absIndex: Int,
    screenHeightDb: Dp,
    screenWidthPx: Int,
    compressionStates: Map<String, CompressionState>,
    onOpenChapterInWebView: () -> Unit,
    onStartImageCompression: (String, Int, Image, Long, Int) -> Unit,
    onRetryCompression: (String) -> Unit
) {


    // Show compressed image if available
    if (item.isCompressed && item.compressedPainter != null) {
        Image(
            painter = item.compressedPainter,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()

                .drawFallbackOnOOM(
                    fallbackIconRes = R.drawable.ic_image_broken,
                ) {
                    onOpenChapterInWebView()
                }
        )
        return
    }

    // Handle original image loading
    val painter = rememberAsyncImagePainter(
        item.request,
        imageLoader = getImageLoader()
    )
    val progressFlow = remember { ProgressManager.getProgressFlow(item.request.data.toString()) }
    val progress by progressFlow.collectAsState()
    val state by painter.state.collectAsState()

    when (state) {
        is AsyncImagePainter.State.Loading -> {
            LoadingImagePlaceholder(
                progressState = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = screenHeightDb)
            )
        }

        is AsyncImagePainter.State.Error -> {
            ImageLoadError(
                modifier = Modifier
                    .fillMaxSize()
                    .defaultMinSize(minHeight = screenHeightDb),
                onRetry = { painter.restart() },
                onOpenInWebView = onOpenChapterInWebView
            )
        }

        is AsyncImagePainter.State.Success -> {
            val img = (state as AsyncImagePainter.State.Success).result.image
            val maxBytes = 99L * 1024 * 1024
            if (img.size <= maxBytes) {
                // Show image directly if under size threshold
                Image(
                    painter = painter,
                    contentDescription = null,

                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .drawFallbackOnOOM(
                            fallbackIconRes = R.drawable.ic_image_broken,
                        ) {
                            onOpenChapterInWebView()
                        }
                )
            } else {
                // Handle large image compression

                CompressedImageHandler(
                    item = item,
                    absIndex = absIndex,
                    img = img,
                    maxBytes = maxBytes,
                    screenWidthPx = screenWidthPx,
                    compressionStates = compressionStates,
                    onOpenChapterInWebView = onOpenChapterInWebView,
                    onStartImageCompression = onStartImageCompression,
                    onRetryCompression = onRetryCompression
                )


            }
        }

        else -> {
            LoadingImagePlaceholder(
                progressState = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 600.dp)
            )
        }
    }
}

@Composable
private fun CompressedImageHandler(
    item: ImagePage,
    absIndex: Int,
    img: Image,
    maxBytes: Long,
    screenWidthPx: Int,
    compressionStates: Map<String, CompressionState>,
    onOpenChapterInWebView: () -> Unit,
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
        (screenWidthDp * aspectRatio).coerceAtLeast(200.dp)
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
                    .fillMaxWidth()
                    .height(expectedHeight)
            )
        }

        compressionState?.error != null -> {
            ImageLoadError(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(expectedHeight),
                message = compressionState.error,
                onRetry = { onRetryCompression(imageUrl) },
                onOpenInWebView = onOpenChapterInWebView
            )
        }

        else -> {
            // Show original image while waiting for compression to start
            CompressionLoadingPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(expectedHeight)
            )
        }
    }
}

@Composable
fun LoadingImagePlaceholder(
    progressState: ProgressState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        when (progressState) {
            is ProgressState.Idle -> {
                CircularProgressIndicator()
            }

            is ProgressState.Loading -> {

                val percentText = progressState.formattedPercent()
                val sizeText = progressState.formattedSize()
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { progressState.percent / 100f },
                            modifier = Modifier.size(48.dp),
                        )

                        Text(
                            text = percentText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            is ProgressState.Completed -> {
                CircularProgressIndicator()
            }

            is ProgressState.Failed -> {
                Text(
                    text = stringResource(R.string.failed_to_load),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
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
                text =stringResource(R.string.notification_compressing_images),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
        }
    }
}