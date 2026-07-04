
package me.manga.yamiapk.presentation.features.home.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Scaffold
import androidx.compose.material.ScaffoldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Web
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import me.manga.yamiapk.R

import me.manga.yamiapk.ad_mob.native_ads.NativeAdListItem
import me.manga.yamiapk.ad_mob.util.ListEntryWithAd
import me.manga.yamiapk.ad_mob.util.ads_lists.interleaveAds
import me.manga.yamiapk.ad_mob.util.ads_lists.interleaveAdsCustom
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.presentation.common.componants.app_bars.TopAppBarCom
import me.manga.yamiapk.presentation.common.componants.isScrolledToTheEnd
import me.manga.yamiapk.presentation.common.screens.ErrorScreen
import me.manga.yamiapk.presentation.common.screens.LoadingScreen
import me.manga.yamiapk.presentation.features.home.data.ApiTitle
import me.manga.yamiapk.presentation.features.home.ui.components.HelpVideoDialog
import me.manga.yamiapk.presentation.features.home.ui.components.MangaCarousel
import me.manga.yamiapk.presentation.features.home.ui.components.MangaHomeItem
import me.manga.yamiapk.presentation.features.home.ui.components.SearchItems
import me.manga.yamiapk.presentation.features.home.ui.components.SourcesTabs
import me.manga.yamiapk.presentation.features.home.ui.screens.maintenance.SiteAdultContentBlockedScreen
import me.manga.yamiapk.presentation.features.home.ui.screens.maintenance.SiteMaintenanceScreen
import me.manga.yamiapk.presentation.features.home.ui.screens.maintenance.SiteStoppedScreen
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourceState
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import kotlin.Boolean

@Composable
fun HomeScreen(
    siteState: SourceState,
    currentSiteName: String,
    scaffoldState: ScaffoldState,
    mangaItemsState: State<List<MangaItem>>,
    popularManga: State<List<PopularManga>>,
    savedTitles: Set<ApiTitle>,
    isLoadingNextPage: Boolean,
    tabs: androidx.compose.runtime.State<List<BaseMangaRepository>>,
    activeTabIndex: Int,
    searchQuery: String,
    isRefreshing: Boolean,
    onSearchChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenInWebView: () -> Unit,
    onHelp: () -> Unit,
    onSaveToggle: (MangaItem) -> Unit,
    onMangaClick: (String, String, String, Boolean) -> Unit,
    onChapterClick: (ChapterItem, MangaItem, List<ChapterItem>) -> Unit,
    onRefresh: () -> Unit,
    onEndReached: () -> Unit,
    onTabSelected: (Int) -> Unit,
    onEditTabs: () -> Unit,
    buildImageRequest: (Context, String, String) -> ImageRequest,
    listState: LazyListState,
    gridState : LazyGridState,  // Add this parameter

    isNewSource: Boolean,
) {
    // Grid view state
    var isGridView by remember { mutableStateOf(false) }
    var previousTabIndex by remember { mutableStateOf(activeTabIndex) }

    // Reset scroll position when tab changes
    LaunchedEffect(activeTabIndex) {
        if (previousTabIndex != activeTabIndex) {
            listState.scrollToItem(0)
            gridState.scrollToItem(0)
            previousTabIndex = activeTabIndex
        }
    }
    // next page items
    var hasRequestedNextPage by remember { mutableStateOf(false) }

    // Reset hasRequestedNextPage when tab changes or data size changes
    LaunchedEffect(activeTabIndex, (mangaItemsState as? State.Success<List<MangaItem>>)?.data?.size) {
        hasRequestedNextPage = false
    }

    LaunchedEffect(mangaItemsState, isGridView) {
        snapshotFlow {
            if (isGridView) {
                gridState.isScrolledToTheEnd()
            } else {
                listState.isScrolledToTheEnd()
            }
        }
            .filter { it }
            .distinctUntilChanged()
            .collect {
                if (mangaItemsState is State.Success && !hasRequestedNextPage) {
                    hasRequestedNextPage = true
                    onEndReached()
                }
            }
    }

    val mergedState: State<List<ListEntryWithAd<MangaItem>>> = remember(mangaItemsState) {
        when (mangaItemsState) {
            is State.Success -> State.Success(
                interleaveAdsCustom(
                    interval = 5,
                    items = mangaItemsState.data,
                )
            )
            is State.Error -> State.Error(mangaItemsState.code, mangaItemsState.message)
            is State.Loading -> State.Loading
        }
    }

    HomeComposableUi(
        siteState = siteState,
        currentSiteName = currentSiteName,
        mangaItemsState = mergedState,
        popularManga = popularManga,
        savedTitles = savedTitles,
        isLoadingNextPage = isLoadingNextPage,
        tabs = tabs,
        activeTabIndex = activeTabIndex,
        searchQuery = searchQuery,
        isRefreshing = isRefreshing,
        isGridView = isGridView,
        onToggleGridView = { isGridView = !isGridView },
        onSearchChange = onSearchChange,
        onToggleSearch = onToggleSearch,
        onOpenInWebView = onOpenInWebView,
        onHelp = onHelp,
        onSaveToggle = onSaveToggle,
        onMangaClick = onMangaClick,
        onChapterClick = onChapterClick,
        onRefresh = onRefresh,
        onEndReached = onEndReached,
        onTabSelected = onTabSelected,
        onEditTabs = onEditTabs,
        buildImageRequest = buildImageRequest,
        listState = listState,
        gridState = gridState,
        scaffoldState = scaffoldState,
        isNewSource = isNewSource,
    )
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun HomeComposableUi(
    scaffoldState: ScaffoldState,
    siteState: SourceState,
    currentSiteName: String,
    mangaItemsState: State<List<ListEntryWithAd<MangaItem>>>,
    popularManga: State<List<PopularManga>>,
    savedTitles: Set<ApiTitle>,
    isLoadingNextPage: Boolean,
    tabs: androidx.compose.runtime.State<List<BaseMangaRepository>>,
    activeTabIndex: Int,
    searchQuery: String,
    isRefreshing: Boolean,
    isGridView: Boolean,
    onToggleGridView: () -> Unit,
    onSearchChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenInWebView: () -> Unit,
    onHelp: () -> Unit,
    onSaveToggle: (MangaItem) -> Unit,
    onMangaClick: (String, String, String, Boolean) -> Unit,
    onChapterClick: (ChapterItem, MangaItem, List<ChapterItem>) -> Unit,
    onRefresh: () -> Unit,
    onEndReached: () -> Unit,
    onTabSelected: (Int) -> Unit,
    onEditTabs: () -> Unit,
    buildImageRequest: (Context, String, String) -> ImageRequest,
    listState: LazyListState,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    isNewSource: Boolean,
) {
    var showHelpDialog by remember { mutableStateOf(false) }

    Scaffold(
        scaffoldState = scaffoldState,
        contentColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBarCom(
                title = stringResource(id = R.string.title_home),
                actions = {
                    if (siteState == SourceState.WORKING) {
                        // Grid/List toggle button
                        IconButton(onClick = onToggleGridView) {
                            Icon(
                                imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                contentDescription = if (isGridView) "List View" else "Grid View"
                            )
                        }
                        IconButton(onClick = { onToggleSearch() }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { onOpenInWebView() }) {
                            Icon(Icons.Default.Web, contentDescription = "Options")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        val pullRefreshState = rememberPullRefreshState(
            refreshing = isRefreshing,
            onRefresh = onRefresh
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pullRefresh(pullRefreshState)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Tabs at top - outside grid/list
                    SourcesTabs(
                        tabs = tabs.value,
                        activeTabIndex = activeTabIndex,
                        onTabSelected = onTabSelected,
                        onEditTabs = onEditTabs,
                        isNewSource = isNewSource,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isGridView) {
                        // Grid View Layout
                        when (siteState) {
                            SourceState.WORKING -> {
                                when (mangaItemsState) {
                                    is State.Loading -> {
                                        LoadingScreen(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(16.dp)
                                        )
                                    }
                                    is State.Error -> {
                                        ErrorScreen(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(16.dp),
                                            message = stringResource(
                                                R.string.failed_to_load,
                                                mangaItemsState.message
                                            ),
                                            onRetry = { onRefresh() },
                                            onOpenInBrowser = { onOpenInWebView() },
                                            onHelp = { showHelpDialog = true }
                                        )
                                    }
                                    is State.Success -> {
                                        LazyVerticalGrid(
                                            state = gridState,
                                            columns = GridCells.Adaptive(minSize = 160.dp),
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(8.dp)
                                        ) {
                                            itemsIndexed(mangaItemsState.data) { index , manga ->
                                                when (manga) {
                                                    is ListEntryWithAd.Item -> {
                                                        SearchItems(
                                                            manga = manga.data,
                                                            savedTitles = savedTitles,
                                                            onMangaClick = onMangaClick,
                                                            buildImageRequest = buildImageRequest
                                                        )
                                                    }
                                                    is ListEntryWithAd.Ad -> {
                                                        NativeAdListItem(
                                                            position = index,
                                                            Modifier
                                                                .padding(8.dp)
                                                                .fillMaxSize()
                                                        ) {}
                                                    }
                                                }
                                            }

                                            if (isLoadingNextPage) {
                                                item {
                                                    LoadingScreen()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            SourceState.UNDER_MAINTENANCE -> {
                                SiteMaintenanceScreen(siteName = currentSiteName)
                            }
                            SourceState.STOPPED -> {
                                SiteStoppedScreen(siteName = currentSiteName)
                            }
                            SourceState.ADULT_18_PLUS -> {
                                SiteAdultContentBlockedScreen(siteName = currentSiteName)
                            }
                        }
                    } else {
                        // List View Layout (Original)
                        when (siteState) {
                            SourceState.WORKING -> {
                                when (mangaItemsState) {
                                    is State.Loading -> {
                                        LoadingScreen(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(16.dp)
                                        )
                                    }
                                    is State.Error -> {
                                        ErrorScreen(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(16.dp),
                                            message = stringResource(
                                                R.string.failed_to_load,
                                                mangaItemsState.message
                                            ),
                                            onRetry = { onRefresh() },
                                            onOpenInBrowser = { onOpenInWebView() },
                                            onHelp = { showHelpDialog = true }
                                        )
                                    }
                                    is State.Success -> {
                                        LazyColumn(
                                            state = listState,
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(8.dp)
                                        ) {
                                            when (popularManga) {
                                                is State.Success -> {
                                                    item {
                                                        popularManga.data?.let { popularItems ->
                                                            MangaCarousel(
                                                                items = popularItems,
                                                                savedTitles = savedTitles,
                                                                onItemClick = onMangaClick,
                                                                buildImageRequest = buildImageRequest
                                                            )
                                                        }
                                                    }
                                                }
                                                is State.Error, is State.Loading -> {}
                                            }

                                            item {
                                                Spacer(modifier = Modifier.height(16.dp))
                                            }

                                            itemsIndexed(mangaItemsState.data) { index ,manga ->
                                                when (manga) {
                                                    is ListEntryWithAd.Item -> {
                                                        MangaHomeItem(
                                                            item = manga.data,
                                                            isSaved = savedTitles.contains(
                                                                ApiTitle(
                                                                    api = manga.data.api,
                                                                    title = manga.data.title
                                                                )
                                                            ),
                                                            onSaveClick = { onSaveToggle(manga.data) },
                                                            onMangaClick = onMangaClick,
                                                            onChapterClick = onChapterClick,
                                                            buildImageRequest = buildImageRequest
                                                        )
                                                    }
                                                    is ListEntryWithAd.Ad -> {
                                                        NativeAdListItem(
                                                            position = index,
                                                            Modifier
                                                                .padding(8.dp)
                                                                .fillMaxSize()
                                                        ) {}
                                                    }
                                                }
                                            }

                                            if (isLoadingNextPage) {
                                                item {
                                                    LoadingScreen()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            SourceState.UNDER_MAINTENANCE -> {
                                SiteMaintenanceScreen(siteName = currentSiteName)
                            }
                            SourceState.STOPPED -> {
                                SiteStoppedScreen(siteName = currentSiteName)
                            }
                            SourceState.ADULT_18_PLUS -> {
                                SiteAdultContentBlockedScreen(siteName = currentSiteName)
                            }
                        }
                    }
                }

                PullRefreshIndicator(
                    refreshing = isRefreshing,
                    state = pullRefreshState,
                    modifier = Modifier.align(Alignment.TopCenter),
                    backgroundColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.background
                )
            }
        }

        if (showHelpDialog) {
            HelpVideoDialog(
                onDismiss = { showHelpDialog = false }
            )
        }
    }
}