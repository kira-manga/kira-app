package me.manga.yamiapk.presentation.features.library_details.ui.screens

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R

import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.firebase_cores.common.rememberFirebaseAnalytics
import me.manga.yamiapk.presentation.common.componants.floating_button.AnimatedCircleExtendedFab
import me.manga.yamiapk.presentation.common.componants.images.ImageWithGradientOverlay
import me.manga.yamiapk.presentation.common.componants.scroll.VerticalFastScroller
import me.manga.yamiapk.presentation.features.details.ui.components.dialogs.ConfirmDialogClean
import me.manga.yamiapk.presentation.features.home.data.ApiTitle
import me.manga.yamiapk.presentation.features.library.ui.components.library_sheet.CustomFilterBottomSheet
import me.manga.yamiapk.presentation.features.library.ui.components.library_sheet.FilterChipsRow
import me.manga.yamiapk.presentation.features.library.ui.components.library_sheet.SortOptionsSection
import me.manga.yamiapk.presentation.features.library_details.ui.components.MangaTopAppBar
import me.manga.yamiapk.presentation.features.library_details.ui.viewmodel.LibraryDetailsViewModel

@OptIn(ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryMangaScreen(
    manga: SavedMangaEntity,
    chapters: List<SavedChapterEntity>,
    savedTitles: Set<ApiTitle>,
    runningChapter : ChapterDownloadEntity?,
    onBackClick: () -> Unit,
    onMangaBookmarkClick: (List<SavedChapterEntity>, SavedMangaEntity) -> Unit,
    onChapterClick: (SavedChapterEntity, SavedMangaEntity, List<SavedChapterEntity>) -> Unit,
    onChapterDownloadClick: (SavedChapterEntity, SavedMangaEntity) -> Unit,
    onChapterBookmarkClick: (SavedChapterEntity) -> Unit,
    onChapterReadClick: (SavedChapterEntity) -> Unit,
    sortAscending: Boolean,
    toggleSort: () -> Unit,
    downloadAllManga: (List<SavedChapterEntity>) -> Unit,
    cancelAllDownloads: () -> Unit,
    isDownloadingAll: Boolean,
    downloadingChapters: List<Long>,
    onOpenInWebView: (String, String) -> Unit,
    onRefreshClick: () -> Unit,
    isRefreshing: Boolean,
    onCustomDownload: (Set<SavedChapterEntity>, SavedMangaEntity) -> Unit,
    onBookmarkAll: (Set<SavedChapterEntity>) -> Unit,
    onMarkAllRead: (Set<SavedChapterEntity>) -> Unit,
    snackbarHostState: SnackbarHostState,
    onCancelRunningChapter: (SavedChapterEntity, SavedMangaEntity) -> Unit,
    onCancelChapter: (SavedChapterEntity, SavedMangaEntity) -> Unit,
    onMarkAllDownRead: (SavedChapterEntity) -> Unit,
    onDeleteAll: (Set<SavedChapterEntity>) -> Unit,
    selectedFilter: LibraryDetailsViewModel.FilterType,
    onFilterItemSelected: (LibraryDetailsViewModel.FilterType) -> Unit,
    selectedSort: LibraryDetailsViewModel.SortType,
    onSortItemSelected: (LibraryDetailsViewModel.SortType) -> Unit
    ) {
    val context = LocalContext.current

    val firebaseAnalytics = rememberFirebaseAnalytics()

    LaunchedEffect(manga.id) {

        if (manga.id == 0L) return@LaunchedEffect
        val params = Bundle().apply {
            putString("manga_api", manga.api)
            putString("manga_title", manga.title)
            putString("source_screen", "library")
        }
        firebaseAnalytics.logEvent("manga_open", params)
    }






    var showSheet     by remember { mutableStateOf(false) }

    var showBookmarkAlert = remember { mutableStateOf(false) }
    val showAddBookmarkAlert = remember { mutableStateOf(false) }
    var selectedChapters by remember { mutableStateOf(setOf<SavedChapterEntity>()) }
    var showChaptersCheckBox = remember { mutableStateOf(false) }
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

    val isFloatExpanded = remember { mutableStateOf(true) }
    val firstUnread: SavedChapterEntity? = remember(chapters) {
        if (sortAscending){
        chapters.firstOrNull { !it.isRead }
        }else{

            chapters.reversed().firstOrNull { !it.isRead }

        }
    }
    LaunchedEffect(listState) {
        // Keep previous values in local vars
        var prevIndex = listState.firstVisibleItemIndex
        var prevOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (currentIndex, currentOffset) ->
                // Determine direction:
                if (currentIndex > prevIndex ||
                    (currentIndex == prevIndex && currentOffset > prevOffset)
                ) {
                    isFloatExpanded.value = false

                } else if (currentIndex < prevIndex ||
                    (currentIndex == prevIndex && currentOffset < prevOffset)
                ) {
                    isFloatExpanded.value = true

                }
                // Update previous:
                prevIndex = currentIndex
                prevOffset = currentOffset
            }
    }


    BackHandler {
        if (showChaptersCheckBox.value) {
            showChaptersCheckBox.value = false
        } else {
            onBackClick()
        }
    }
    if (showBookmarkAlert.value) {
        ConfirmDialogClean(
            title =stringResource(R.string.remove_bookmark_title),
            text = stringResource(R.string.remove_bookmark_message),
            onConfirm = {
                showBookmarkAlert.value = false
                onMangaBookmarkClick(chapters, manga)
            },
            onDismiss = { showBookmarkAlert.value = false }
        )
    }

    if (showAddBookmarkAlert.value) {
        ConfirmDialogClean(
            title =stringResource(R.string.add_library_title),
            text = stringResource(R.string.add_library_message),
            confirmText = stringResource(R.string.add_library_title),
            onConfirm = {
                showAddBookmarkAlert.value = false
                onMangaBookmarkClick(chapters, manga)
            },
            onDismiss = { showAddBookmarkAlert.value = false }
        )
    }

    Scaffold(
        topBar = {
            MangaTopAppBar(
                title = manga.title,
                onBackClick = onBackClick,
                backgroundColor = Color.Transparent,
                cancelAllDownloads = cancelAllDownloads,
                isDownloadingAll = isDownloadingAll,
                onRefreshClick = onRefreshClick,
                onFilterClick = {showSheet = true},
                onDeleteDownloads = {
                    onDeleteAll(chapters.filter { it.isDownloaded }.toSet())
                }
            )
        },
        floatingActionButton = {AnimatedCircleExtendedFab(

            onClick = {

                if (firstUnread != null) onChapterClick(firstUnread, manga, chapters)
            },
            icon = Icons.Default.PlayArrow,
            contentDescription = "Resume",
            expanded = isFloatExpanded.value,
            text = if (firstUnread != null) "Resume ${firstUnread.number}" else "You finished this manga",
        )},
        snackbarHost = {  SnackbarHost(hostState = snackbarHostState) },

        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) { padding ->
        // 1) Create pull-refresh state
        val pullRefreshState = rememberPullRefreshState(
            refreshing = isRefreshing,
            onRefresh = onRefreshClick,

            )


        // 2) Wrap content in Box with pullRefresh modifier
        Box{

            ImageWithGradientOverlay(
                imageUrl = manga.imageUrl,
                headerHeightDp = 250.dp,

                blur = 14.dp,
                parallaxOffset = parallaxOffset
            )

            VerticalFastScroller(
                listState = listState,
                modifier =  Modifier
                    .padding(top = padding.calculateTopPadding())
                    .pullRefresh(pullRefreshState)
                // You can customize these if you want:
                // thumbColor = MaterialTheme.colorScheme.primary,
                // topContentPadding = ...,
                // bottomContentPadding = ...,
                // endContentPadding = ...,
            ) {



                LazyColumn(state = listState,

                ) {




                    item {
                        LibraryHeaderSection(
                            manga = manga,
                            chapters = chapters,
                            isSaved = isSaved,
                            onMangaBookmarkClick = onMangaBookmarkClick,
                            onRequestBookmark = { showBookmarkAlert.value = it },
                            isDownloadingAll = isDownloadingAll,
                            downloadAll = downloadAllManga,
                            selectedChapters = selectedChapters,
                            showChaptersCheckBox = showChaptersCheckBox,
                            onCustomDownload = onCustomDownload,
                            onSelectedChaptersChange = { selectedChapters = it },
                            onOpenInWebView = onOpenInWebView

                        )
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, bottom = 8.dp, end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.chapters_count_format, chapters.size),
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = {
                                toggleSort()
                            }) {
                                Icon(
                                    imageVector = if (sortAscending) Icons.Outlined.KeyboardDoubleArrowDown else Icons.Outlined.KeyboardDoubleArrowUp,
                                    contentDescription = stringResource(if (sortAscending) R.string.desc_newest_first else R.string.desc_oldest_first)
                                )
                            }
                        }
                    }

                    items(
                        chapters,
                        key = { it.id }
                    ) { chapter ->
                        LibraryChapterItem(
                            chapter = chapter,
                            manga = manga,
                            chapters = chapters,
                            isSelected = selectedChapters.contains(chapter),
                            onSelectChanged = { checked ->

                                selectedChapters =
                                    if (checked) selectedChapters + chapter else selectedChapters - chapter

                            },
                            onChapterClick = onChapterClick,
                            runningChapter = runningChapter,
                            onChapterDownloadClick = onChapterDownloadClick,
                            onChapterBookmarkClick = onChapterBookmarkClick,
                            onChapterReadClick = onChapterReadClick,
                            downloadingChapters = downloadingChapters,
                            showChaptersCheckBox = showChaptersCheckBox,
                            onLongClick = {
                                showChaptersCheckBox.value = true
                            },
                            onCancelRunningChapter =onCancelRunningChapter ,
                            onCancelChapter =onCancelChapter
                        )
                    }

                }
                if (showChaptersCheckBox.value) {
                    ChapterSelectionActionsRow(
                        chapters = selectedChapters,
                        onDownloadAll = {
                            onCustomDownload(it,manga)
                            selectedChapters = emptySet()

                            showChaptersCheckBox.value = false
                        },
                        onBookmarkAll = {

                            onBookmarkAll(it)
                            selectedChapters = emptySet()

                            showChaptersCheckBox.value = false
                        },
                        onMarkAllRead = {

                            onMarkAllRead(it)

                            selectedChapters = emptySet()
                            showChaptersCheckBox.value = false
                        },
                        onCancelAll = { showChaptersCheckBox.value = false },
                        onMarkAllDownRead = onMarkAllDownRead,
                        onDeleteAll = onDeleteAll

                    )


                }
            }

            CustomFilterBottomSheet(
                showSheet = showSheet,
                onDismiss = { showSheet = false },

                tabs = listOf(
                    stringResource(R.string.library_bottom_sheet_tab_filter),
                    stringResource(R.string.library_bottom_sheet_tab_sort),
//                    stringResource(R.string.library_bottom_sheet_tab_display)
                ),
                initialTabIndex = 0,
                pageContents = listOf(
                    {

                        FilterChipsRow(
                            items = LibraryDetailsViewModel.FilterType.entries,
                            selectedItem = selectedFilter,
                            onItemSelected = onFilterItemSelected,
                            label = { it.getDisplayName(context) }
                        )
                    },
                    {
                        SortOptionsSection(
                            items = LibraryDetailsViewModel.SortType.entries,
                            selectedItem = selectedSort,
                            isAscending = sortAscending,
                            onItemSelected = onSortItemSelected,
                            onDirectionChange = { toggleSort() },
                            label = { it.getDisplayName(context) },
                            headerText = stringResource(R.string.sort_options_title),
                            sortByText = stringResource(R.string.sort_by_label),
                            sortDirectionText = stringResource(R.string.sort_direction_label),
                            ascendingText = stringResource(R.string.sort_direction_ascending),
                            descendingText = stringResource(R.string.sort_direction_descending)
                        )
                    },
//                    {
////
//
//                        DisplayOptionsSection(
//                            modifier = Modifier,
//                            count =itemsPerRow,
//                            onCountChange     = viewModel::onItemsPerRowChange,
//                            switchContents = listOf(
//                                {
//                                    Divider()
//                                    SwitchItem(
//                                        title = stringResource(R.string.show_items_details),
//                                        checked = showDetails,
//                                        onCheckedChange = viewModel::onToggleDetails,
//                                    )
//                                },
//                                {
//                                    Divider()
//                                    SwitchItem(
//                                        title = stringResource(R.string.show_items_source),
//                                        checked = showSource,
//                                        onCheckedChange =viewModel::    onToggleSource,
//                                    )
//                                },
//                                {
//                                    Divider()
//                                    SwitchItem(
//                                        title = stringResource(R.string.show_items_count),
//                                        checked = showCount,
//                                        onCheckedChange = viewModel::onToggleCount
//                                    )
//                                }
//                            )
//                        )
//
//
//                    }
                ),

                )            // 3) Pull-to-refresh indicator
            PullRefreshIndicator(
                refreshing = isRefreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                backgroundColor = MaterialTheme.colorScheme.inverseSurface, // Set your desired background color
                contentColor = MaterialTheme.colorScheme.background    // Set your desired content color
            )
        }
    }

}
