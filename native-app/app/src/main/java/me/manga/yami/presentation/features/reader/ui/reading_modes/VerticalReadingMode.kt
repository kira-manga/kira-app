package me.manga.yamiapk.presentation.features.reader.ui.reading_modes

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import coil3.Image
import me.manga.yamiapk.presentation.common.screens.LoadingScreen
import me.manga.yamiapk.presentation.features.reader.data.CompressionState
import me.manga.yamiapk.presentation.features.reader.data.ReaderItem
import me.manga.yamiapk.presentation.features.reader.ui.components.NextChapterCard
import me.manga.yamiapk.presentation.features.reader.ui.components.errorCard
import net.engawapg.lib.zoomable.ExperimentalZoomableApi
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomableWithScroll

@OptIn(ExperimentalZoomableApi::class)
@Composable
fun VerticalReadingMode(
    pagerState: PagerState,
    allReaderItems: List<ReaderItem>,
    mangaApi: String,
    screenHeightDb: Dp,
    screenWidthPx: Int,
    isCurrentChapLoading: Boolean,
    compressionStates: Map<String, CompressionState>,
    loadingNextChapter: () -> Unit,
    onOpenInWebView: () -> Unit,
    onTap: () -> Unit,
    onStartImageCompression: (String, Int, Image, Long, Int) -> Unit,
    onRetryCompression: (String) -> Unit
) {
    VerticalPager(
        state = pagerState,
        modifier = Modifier
            .zoomableWithScroll(
                rememberZoomState(),
                onTap = {
                    onTap()
                })
            .fillMaxSize()
    ) { page ->
        val itemPage = allReaderItems.getOrNull(page)

        when (val item = itemPage) {
            is ReaderItem.ImagePage -> {
                PagerImageItem(
                    item = item,
                    absIndex = page,
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
                        loadingNextChapter()
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

            null -> {
                LoadingScreen()
            }
        }
    }
}