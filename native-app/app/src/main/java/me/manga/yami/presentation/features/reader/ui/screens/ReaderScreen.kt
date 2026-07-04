@file:JvmName("ReaderScreenKt")

package me.manga.yamiapk.presentation.features.reader.ui.screens


import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.manga.yamiapk.ad_mob.bannars.BannerAdView
import me.manga.yamiapk.core.util.Handle403Error
import me.manga.yamiapk.core.util.image_share.ScreenshotUtils
import me.manga.yamiapk.di.coli.CoilEntryPoint
import me.manga.yamiapk.domain.model.ReaderChapters
import me.manga.yamiapk.presentation.common.screens.LoadingScreen
import me.manga.yamiapk.presentation.common.viewmodel.SharedChaptersViewModel
import me.manga.yamiapk.presentation.features.history.ui.viewmodel.HistoryViewModel
import me.manga.yamiapk.presentation.features.reader.data.ReaderItem
import me.manga.yamiapk.presentation.features.reader.data.ReadingMode
import me.manga.yamiapk.presentation.features.reader.data.isPaged
import me.manga.yamiapk.presentation.features.reader.ui.components.ControlOverlay
import me.manga.yamiapk.presentation.features.reader.ui.components.reading_mode_dialog.ReadingModeDialog
import me.manga.yamiapk.presentation.features.reader.ui.reading_modes.ContinuousVerticalReadingMode
import me.manga.yamiapk.presentation.features.reader.ui.reading_modes.HorizontalReadingMode
import me.manga.yamiapk.presentation.features.reader.ui.reading_modes.VerticalReadingMode
import me.manga.yamiapk.presentation.features.reader.ui.reading_modes.WebToonReadingMode
import me.manga.yamiapk.presentation.features.reader.ui.viewmodel.ReaderViewModel
import me.manga.yamiapk.theme.YamiMangaTheme


@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun ReaderScreen(
    startIndex: Int,
    mangaApi: String,
    chaptersList: List<ReaderChapters>,
    readerViewModel: ReaderViewModel = hiltViewModel(),
    historyViewModel: HistoryViewModel,
    sharedChaptersVm: SharedChaptersViewModel,
    openChapterInWebView : (String,String)-> Unit,

    mangaUrl: String,
    onBackPressed: () -> Unit
) {

    if (chaptersList.isNullOrEmpty()){


        onBackPressed()
        return@ReaderScreen

    }

    val context = LocalContext.current
    val activity = context as Activity
    val rootView = LocalView.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightDb =  remember{ configuration.screenHeightDp.dp }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        onDispose {

            sharedChaptersVm.clearSavedStateHandle()
        }
    }
    HideSystemBars()
//    DisposableEffect(Unit) {
//        hideNavigationBar(activity)
//        onDispose {
//            showNavigationBar(activity)
//        }
//    }

    var scrollTarget by remember { mutableStateOf<Int?>(null) }
    var showReadingModeDialog by remember { mutableStateOf(false) }
    var showWebViewDialog by remember { mutableStateOf(false) }
    var error403ChapterUrl by remember { mutableStateOf("") }
    var error403Api by remember { mutableStateOf("") }

    // 1) Observe ViewModel state
    val readingMode by readerViewModel.readingMode.collectAsStateWithLifecycle()
    val isBookmarked by readerViewModel.bookmarked.collectAsStateWithLifecycle()
    val allReaderItems by readerViewModel.allReaderItems.collectAsStateWithLifecycle()
    val currentChapterIndex by readerViewModel.currentChapterIndex.collectAsStateWithLifecycle()
    val compressionStates by readerViewModel.compressionStates.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()

    // 2) Record reading-session times
    ReadingTimeRecorder(lifecycleOwner = lifecycleOwner, viewModel = readerViewModel)

    // 3) Get the latest historyId on first compose
    var historyId by remember { mutableStateOf<Long>(0L) }
    LaunchedEffect(mangaUrl) {
        historyId = historyViewModel.getLatestHistoryIdByManga(mangaUrl).first() ?: 0L
    }
    var shouldScrollToChapter by remember { mutableStateOf(false) }

    // 4) Initialize ViewModel (loads the very first chapter)
    LaunchedEffect(Unit) {
        readerViewModel.initialize(
            startIndex = startIndex,
            chaptersList = chaptersList,
            mangaApi = mangaApi,
            screenWidthPx = screenWidthPx,
            context = context
        )
    }

    val listState     = rememberLazyListState()
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { allReaderItems.size }    // ← dynamic page count
    )

    LaunchedEffect(allReaderItems, currentChapterIndex) {
        val currentChapterItems = allReaderItems.filter { item ->
            when (item) {
                is ReaderItem.ImagePage -> item.chapterIndex == currentChapterIndex
                is ReaderItem.ErrorOverlay -> item.currentChapter == chaptersList.getOrNull(currentChapterIndex)
                is ReaderItem.NextChapterOverlay -> item.currentChapter == chaptersList.getOrNull(currentChapterIndex)
            }
        }

        val errorOverlay = currentChapterItems.filterIsInstance<ReaderItem.ErrorOverlay>()
            .firstOrNull { it.errorCode == 403 }

        if (errorOverlay != null && !showWebViewDialog) {
            error403ChapterUrl = errorOverlay.currentChapter.url
            error403Api = errorOverlay.currentChapter.api.ifEmpty { mangaApi }
            showWebViewDialog = true
        }
    }



    // 5) Whenever currentChapterIndex changes or allReaderItems grows,
    //    wait for that chapter’s first ImagePage to appear, then scroll the pager

    LaunchedEffect(currentChapterIndex, allReaderItems) {


        // Update history/bookmark
        val currChap = chaptersList[currentChapterIndex]
        if (historyId != 0L) {
            historyViewModel.updateHistoryItem(
                id = historyId,
                chapterUrl = currChap.url,
                chapterTitle = currChap.chapterNumber,
                isDownloaded = currChap.isDownloaded,
                localImagePaths = currChap.localImagePaths
            )
        }
        if (currChap.chapterId != 0L) {
            readerViewModel.observeBookmark(currChap.chapterId)
        }
    }
    // ─── 3) Dedicated LaunchedEffect: only scrolls when shouldScrollToChapter == true ───
    LaunchedEffect(currentChapterIndex, allReaderItems, shouldScrollToChapter) {
        if (!shouldScrollToChapter) return@LaunchedEffect
        if (allReaderItems.isEmpty()) return@LaunchedEffect

        // 3.a) Find absolute index of the first page of `currentChapterIndex`:
        var targetAbs = allReaderItems.indexOfFirst { item ->
            (item as? ReaderItem.ImagePage)?.chapterIndex == currentChapterIndex
        }
        while (targetAbs == -1) {
            delay(50)
            targetAbs = allReaderItems.indexOfFirst { item ->
                (item as? ReaderItem.ImagePage)?.chapterIndex == currentChapterIndex
            }
        }

        // 3.b) Jump in the correct way based on readingMode:
        if (readingMode.isPaged) {
            pagerState.scrollToPage(targetAbs)
        } else {
            listState.scrollToItem(targetAbs)
        }

        // 3.c) Reset the flag so we only scroll once:
        shouldScrollToChapter = false
    }
    // 6) If user swipes to the absolute last page, auto‐load next chapter
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == allReaderItems.lastIndex) {
            readerViewModel.goToNextChapter(
                chaptersList = chaptersList,
                mangaApi = mangaApi,
                screenWidthPx = screenWidthPx,
                context = context
            )
        }
    }

    LaunchedEffect(readingMode, allReaderItems, listState, pagerState) {
        // Only run while there are pages loaded:
        if (allReaderItems.isEmpty()) return@LaunchedEffect

        if (readingMode.isPaged) {
            // In paged mode (VerticalPager/HorizontalPager):
            snapshotFlow { pagerState.currentPage }
                .map { pageIndex ->
                    // “pageIndex” is the absolute index in allReaderItems
                    (allReaderItems.getOrNull(pageIndex) as? ReaderItem.ImagePage)
                        ?.chapterIndex
                        ?: currentChapterIndex
                }
                .distinctUntilChanged()
                .collect { newChapIdx ->
                    // Tell ViewModel if it changed
                    readerViewModel.setCurrentChapterIndex(newChapIdx)
                }

        } else {
            // In continuous/WebToon mode (LazyColumn):
            snapshotFlow { listState.firstVisibleItemIndex }
                .map { absIndex ->
                    (allReaderItems.getOrNull(absIndex) as? ReaderItem.ImagePage)
                        ?.chapterIndex
                        ?: currentChapterIndex
                }
                .distinctUntilChanged()
                .collect { newChapIdx ->
                    readerViewModel.setCurrentChapterIndex(newChapIdx)
                }
        }
    }


    val loadingChapters by readerViewModel.loadingChapters.collectAsState()

    // 2) Derive whether the current chapter index is loading:
    val isCurrentChapLoading by remember(loadingChapters, readerViewModel) {
        derivedStateOf { loadingChapters.contains(readerViewModel.currentChapterIndex.value) }
    }
    val isFirstChapterLoading by remember(isCurrentChapLoading, currentChapterIndex) {
        derivedStateOf { isCurrentChapLoading && currentChapterIndex == startIndex }
    }
    // 7) Build a list of absolute indices for the current chapter’s pages
    val chapterPageIndices: List<Int> = remember(allReaderItems, currentChapterIndex) {
        allReaderItems.mapIndexedNotNull { absIndex, item ->
            (item as? ReaderItem.ImagePage)
                ?.takeIf { it.chapterIndex == currentChapterIndex }
                ?.let { absIndex }
        }
    }
    val chapterPageCount = chapterPageIndices.size

    // 8) Compute “relativePageInChapter”
    // 8) Compute “relativePageInChapter” for BOTH paged and continuous modes:
    val currentAbsPage by remember(pagerState.currentPage, listState.firstVisibleItemIndex, readingMode) {
        derivedStateOf {
            if (readingMode.isPaged) {
                pagerState.currentPage
            } else {
                // In continuous mode, use the first visible item’s absolute index:
                listState.firstVisibleItemIndex
            }
        }
    }

    val relativePageInChapter by remember(currentAbsPage, chapterPageIndices) {
        derivedStateOf {
            chapterPageIndices.indexOf(currentAbsPage).coerceAtLeast(0)
        }
    }

    // 9) Show/hide controls on tap
    var showControls by remember { mutableStateOf(true) }
    // (You can auto-hide after 3s if desired.)

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }
    // 10) Build the UI
    Scaffold { paddingValues ->
        val start = paddingValues.calculateLeftPadding(LayoutDirection.Ltr)
        val end = paddingValues.calculateRightPadding(LayoutDirection.Ltr)

        Column {


            Box(
                modifier = Modifier
                    .padding(start = start, end = end)
                    .fillMaxSize()
                    .weight(1F)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { showControls = !showControls }
                    .background(MaterialTheme.colorScheme.background)
            ) {


                when (readingMode) {
                    ReadingMode.DEFAULT,
                    ReadingMode.VERTICAL -> {
                        if (allReaderItems.isNotEmpty()) {

                            VerticalReadingMode(
                                pagerState = pagerState,
                                allReaderItems = allReaderItems,
                                mangaApi = mangaApi,
                                screenHeightDb = screenHeightDb,
                                screenWidthPx = screenWidthPx,
                                isCurrentChapLoading = isCurrentChapLoading,
                                compressionStates = compressionStates,
                                loadingNextChapter = {
                                    markChapterAsRead(historyViewModel,chaptersList,currentChapterIndex)

                                    readerViewModel.goToNextChapter(
                                        chaptersList, mangaApi, screenWidthPx, context
                                    )
                                },
                                onOpenInWebView = {
                                    chaptersList.getOrNull(
                                        currentChapterIndex
                                    )?.let {
                                        openChapterInWebView(
                                            it.url,
                                            it.api
                                        )
                                    }},
                                onTap = {
                                    showControls = !showControls
                                },
                                onStartImageCompression = { imageUrl, absIndex, img, maxBytes, screenWidthPx ->
                                    readerViewModel.startImageCompression(imageUrl, absIndex, img, maxBytes, screenWidthPx)
                                },
                                onRetryCompression = { imageUrl ->
                                    readerViewModel.retryCompression(imageUrl)
                                }
                            )

                        }else{
                            LoadingScreen()
                        }
                    }

                    ReadingMode.LEFT_TO_RIGHT, ReadingMode.RIGHT_TO_LEFT -> {

                        HorizontalReadingMode(
                            pagerState = pagerState,
                            allReaderItems = allReaderItems,
                            mangaApi = mangaApi,
                            screenHeightDb = screenHeightDb,
                            screenWidthPx = screenWidthPx,
                            isCurrentChapLoading = isCurrentChapLoading,
                            compressionStates = compressionStates,
                            scope = scope,
                            loadingNextChapter = {
                                markChapterAsRead(historyViewModel,chaptersList,currentChapterIndex)

                                readerViewModel.goToNextChapter(
                                    chaptersList, mangaApi, screenWidthPx, context
                                )
                            },
                            reverseLayout = (readingMode == ReadingMode.RIGHT_TO_LEFT),
                            onOpenInWebView = {
                                chaptersList.getOrNull(
                                    currentChapterIndex
                                )?.let {
                                    openChapterInWebView(
                                        it.url,
                                        it.api
                                    )
                                }},
                            onTap = {
                                showControls = !showControls
                            },
                            onStartImageCompression = { imageUrl, absIndex, img, maxBytes, screenWidthPx ->
                                readerViewModel.startImageCompression(imageUrl, absIndex, img, maxBytes, screenWidthPx)
                            },
                            onRetryCompression = { imageUrl ->
                                readerViewModel.retryCompression(imageUrl)
                            }
                        )
                    }

                    ReadingMode.WEBTOON -> {
                        WebToonReadingMode(
                            readerItems = allReaderItems,
                            listState = listState,
                            scrollTarget = scrollTarget,
                            screenHeightDb = screenHeightDb,
                            screenWidthPx = screenWidthPx,
                            isCurrentChapLoading = isCurrentChapLoading,
                            compressionStates = compressionStates,
                            onLoadNextChapter = {
                                markChapterAsRead(historyViewModel, chaptersList, currentChapterIndex)
                                readerViewModel.goToNextChapter(
                                    chaptersList, mangaApi, screenWidthPx, context
                                )
                            },
                            onOpenChapterInWebView = {
                                chaptersList.getOrNull(currentChapterIndex)?.let {
                                    openChapterInWebView(it.url, it.api)
                                }
                            },
                            onTap = { showControls = !showControls },
                            onStartImageCompression = { imageUrl, absIndex, img, maxBytes, screenWidthPx ->
                                readerViewModel.startImageCompression(imageUrl, absIndex, img, maxBytes, screenWidthPx)
                            },
                            onRetryCompression = { imageUrl ->
                                readerViewModel.retryCompression(imageUrl)
                            }
                        )
                    }
                    ReadingMode.CONTINUOUS_VERTICAL -> {
                        ContinuousVerticalReadingMode(
                            readerItems = allReaderItems,
                            listState = listState,
                            scrollTarget = scrollTarget,
                            screenHeightDb = screenHeightDb,
                            isCurrentChapLoading = isCurrentChapLoading,
                            loadingNextChapter = {
                                markChapterAsRead(
                                    historyViewModel,
                                    chaptersList,
                                    currentChapterIndex
                                )
                                readerViewModel.goToNextChapter(
                                    chaptersList, mangaApi, screenWidthPx, context
                                )
                            },
                            onOpenInWebView = {
                                chaptersList.getOrNull(
                                    currentChapterIndex
                                )?.let {
                                    openChapterInWebView(
                                        it.url,
                                        it.api
                                    )
                                }
                            },
                            onTap = { showControls = !showControls },
                            screenWidthPx = screenWidthPx,
                            compressionStates = compressionStates,
                            onStartImageCompression = { imageUrl, absIndex, img, maxBytes, screenWidthPx ->
                                readerViewModel.startImageCompression(imageUrl, absIndex, img, maxBytes, screenWidthPx)
                            },
                            onRetryCompression = { imageUrl ->
                                readerViewModel.retryCompression(imageUrl)
                            }
                        )
                    }

                }


                // ——— Overlay Controls (on tap) ———
                ControlOverlay(
                    currentChapter = chaptersList[currentChapterIndex],
                    show = showControls,
                    currentPage = relativePageInChapter,
                    pageCount = chapterPageCount,
                    isBookmarked = isBookmarked,

                    onPageChange = { relativeTarget ->
                        val absTarget = chapterPageIndices.getOrNull(relativeTarget) ?: return@ControlOverlay
                        scope.launch {
                            if (readingMode.isPaged) {
                                pagerState.scrollToPage(absTarget)
                            } else {
                                // If you implement continuous/list in the future:
                                scrollTarget = absTarget
                            }
                        }
                    },

                    // “Next Chapter” tapped
                    onNext = {
                        shouldScrollToChapter = true
                        markChapterAsRead(historyViewModel,chaptersList,currentChapterIndex)

                        val nextIdx = (currentChapterIndex + 1).coerceAtMost(chaptersList.lastIndex)
                        scope.launch {
                            readerViewModel.goToChapter(
                                index = nextIdx,
                                chaptersList = chaptersList,
                                mangaApi = mangaApi,
                                screenWidthPx = screenWidthPx,
                                context = context,

                                )
                        }


                    },

                    // “Previous Chapter” tapped
                    onPrevious = {
                        shouldScrollToChapter = true
                        markChapterAsRead(historyViewModel,chaptersList,currentChapterIndex)

                        val prevIdx = (currentChapterIndex - 1).coerceAtLeast(0)
                        scope.launch {
                            readerViewModel.goToChapter(
                                index = prevIdx,
                                chaptersList = chaptersList,
                                mangaApi = mangaApi,
                                screenWidthPx = screenWidthPx,
                                context = context,
                            )
                        }


                    },

                    onBackPressed = onBackPressed,

                    onSettings = {
                        showReadingModeDialog = true  },

                    onBookmark = {
                        val chapId = chaptersList[currentChapterIndex].chapterId
                        if (chapId != 0L) {
                            readerViewModel.toggleChapterBookmark(chapId)
                        } else {
                            Toast.makeText(context, "You should add the manga to Library first", Toast.LENGTH_SHORT).show()
                        }
                    },

                    onShare = {
                        scope.launch(Dispatchers.Default) {
                            ScreenshotUtils.captureAndShare(
                                activity = activity,
                                rootView = rootView,
                                hideControls = { showControls = false },
                                showControls = { showControls = true }
                            )
                        }
                    },

                    hasPrevious = (currentChapterIndex > 0),
                    hasNext = (currentChapterIndex < chaptersList.lastIndex)
                )
            }

            BannerAdView()

        }



       if (isFirstChapterLoading){
           Box(
               Modifier
                   .fillMaxSize()
                   .padding(16.dp),
               contentAlignment = Alignment.Center
           ) {
               CircularProgressIndicator(  color = MaterialTheme.colorScheme.primary)
           }
       }


        if (showReadingModeDialog) {
            ReadingModeDialog(
                currentMode = readingMode,
                onModeSelected = { newMode ->
                    readerViewModel.setReadingMode(newMode)
                },
                onDismissRequest = {
                    showReadingModeDialog = false
                },
                onApply = {
                    showReadingModeDialog = false
                }
            )
        }
        if (showWebViewDialog) {
            Handle403Error(
                api = error403Api.ifEmpty { mangaApi },
                chapterUrl = error403ChapterUrl,
                onDismiss = {
                    showWebViewDialog = false
                    // Optionally reload the chapter after WebView is dismissed
                    scope.launch {
                        delay(500)
                        readerViewModel.goToChapter(
                            index = currentChapterIndex,
                            chaptersList = chaptersList,
                            mangaApi = mangaApi,
                            screenWidthPx = screenWidthPx,
                            context = context
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun ReadingTimeRecorder(lifecycleOwner: LifecycleOwner, viewModel: ReaderViewModel) {

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                {
                    viewModel.onScreenResume()
                }
                Lifecycle.Event.ON_STOP -> {
                    viewModel.onScreenPause()

                }
                else -> {


                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

fun markChapterAsRead(viewModel : HistoryViewModel, chaptersList :  List<ReaderChapters>,index : Int){
    chaptersList.getOrNull(index)
        ?.takeIf { it.chapterId != 0L }
        ?.chapterId
        ?.let { viewModel.markChapterAsRead(it) }
}
@Preview
@Composable
fun test (){
    YamiMangaTheme(false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 600.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Failed to load image.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            BorderedPrimaryButton(title = "retry"){}
        }
    }
    }
}

@Composable
fun BorderedPrimaryButton(title : String,
                          height: Dp = 38.dp,                // <-- you can tweak this
                          verticalPadding: Dp = 8.dp,        // <-- and this
                          horizontalPadding: Dp = 28.dp,
                                  onRetry: () -> Unit) {
    Button(
        onClick = { onRetry() },
        modifier = Modifier
            // 1 dp border using onPrimary (for good contrast); adjust color as needed
            // 1dp transparent border
            .border(
                BorderStroke(1.dp, Color.Transparent),
                shape = RoundedCornerShape(16.dp)
            )
            // clip to the same rounded shape
            .clip(RoundedCornerShape(16.dp))
            // force a specific height
            .height(height)
            // remove compose's default min-height so your .height() can take effect
            .defaultMinSize(minHeight = 0.dp),
        // override the default content padding
        contentPadding = PaddingValues(
            vertical = verticalPadding,
            horizontal = horizontalPadding
        ),
        // set the background to primary and content (text) to onPrimary
        colors = ButtonDefaults.buttonColors(
            backgroundColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        elevation = ButtonDefaults.elevation(defaultElevation = 4.dp)
    ) {
        Text(text = title)
    }
}

@Composable
fun HideSystemBars() {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        insetsController.apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }

        onDispose {
            insetsController.apply {
                show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
}

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}