package me.manga.yamiapk.presentation.features.details.ui.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.request.ImageRequest
import me.manga.yamiapk.R
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.presentation.common.componants.app_bars.TopAppBarCom
import me.manga.yamiapk.presentation.common.componants.images.ImageWithGradientOverlay
import me.manga.yamiapk.presentation.common.componants.scroll.VerticalFastScroller
import me.manga.yamiapk.presentation.features.details.ui.components.HeaderSection
import me.manga.yamiapk.presentation.features.details.ui.components.dialogs.ConfirmDialogClean
import me.manga.yamiapk.presentation.features.home.data.ApiTitle

@Composable
fun DetailsContent(
    manga: MangaInfo,
    savedTitles: Set<ApiTitle>,
    hasShownRemoveBookMark:Boolean,
    onShownRemoveBookMark:()->Unit,
    onBackClick: () -> Unit,
    onMangaBookmark: (MangaInfo) -> Unit,
    onChapterClick: (ChapterItem, MangaInfo, List<ChapterItem>) -> Unit,
    onDownloadClick: () -> Unit,
    onOpenInWebView: (String, String) -> Unit,

    buildImageRequest:(Context, String, String) -> ImageRequest

) {
    var showBookmarkAlert = remember { mutableStateOf(false) }
    var showAddBookmarkAlert = remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val isSaved = ApiTitle(api = manga.api, title = manga.title) in savedTitles
    val headerHeightDp = 250.dp
    val headerHeightPx = with(LocalDensity.current) { headerHeightDp.toPx() }
    val scrollOffset = remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat().coerceAtMost(headerHeightPx)
            } else {
                headerHeightPx
            }
        }
    }
    val parallaxOffset by remember { derivedStateOf { scrollOffset.value / 2f } }
    if (showBookmarkAlert.value) {
        ConfirmDialogClean(
            title = stringResource(R.string.remove_bookmark_title),
            text = stringResource(R.string.remove_bookmark_message),
            onConfirm = {
                showBookmarkAlert.value = false
                onMangaBookmark(manga)
            },
            onDismiss = { showBookmarkAlert.value = false }
        )
    }

    if (showAddBookmarkAlert.value) {
        ConfirmDialogClean(
            title = stringResource(R.string.add_library_title),
            text = stringResource(R.string.add_library_message),
            confirmText = stringResource(R.string.confirm_add_to_library),
            onConfirm = {
                showAddBookmarkAlert.value = false
                onMangaBookmark(manga)
            },
            onDismiss = {
                showAddBookmarkAlert.value = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBarCom(manga.title, titleSize = 20.sp,
                backgroundColor = Color.Transparent,

                fontWeight = FontWeight.Normal,
                navigationIcon = {
                    IconButton(onBackClick)
                    {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }) },
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) { padding ->
        Box{

            ImageWithGradientOverlay(
                imageUrl = manga.imageUrl,
                headerHeightDp = 250.dp,
                blur = 14.dp,
                parallaxOffset = parallaxOffset
            )

        VerticalFastScroller(
            listState = listState,
            Modifier.padding(top = padding.calculateTopPadding())
            // You can customize these if you want:
            // thumbColor = MaterialTheme.colorScheme.primary,
            // topContentPadding = ...,
            // bottomContentPadding = ...,
            // endContentPadding = ...,
        ) {
            LazyColumn(


                state = listState

            ) {
                item {
                    HeaderSection(
                        manga = manga,
                        isSaved = isSaved,

                        onMangaBookmark = onMangaBookmark,
                        onRequestAddBookmark = {

                            if (hasShownRemoveBookMark) {
                                onMangaBookmark(manga)
                            } else {
                                showAddBookmarkAlert.value = it
                                onShownRemoveBookMark()

                            }
                        },
                        onDownloadClick = onDownloadClick,
                        onOpenInWebView = onOpenInWebView,
                        buildImageRequest = buildImageRequest

                    )
                }


                items(manga.chapters) { chapter ->
                    ChapterItem(
                        manga = manga,
                        chapter = chapter,
                        chapters = manga.chapters ?: mutableListOf(),
                        isSaved = isSaved,
                        onRequestAddBookmark = { showAddBookmarkAlert.value = true },
                        onDownloadClick = onDownloadClick,
                        onChapterClick = onChapterClick
                    )
                }
            }
        }
    }

        }
}