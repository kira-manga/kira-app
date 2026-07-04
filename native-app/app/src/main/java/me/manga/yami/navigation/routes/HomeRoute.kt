package me.manga.yamiapk.navigation.routes

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.ScaffoldState
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.SnackbarResult
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.manga.yamiapk.R
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.util.Handle403Error
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.emptyChapterItem
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toMangaInfo
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.navigation.double_click.HomeTabReselectedHandler
import me.manga.yamiapk.navigation.double_click.NavigationHandlerHolder
import me.manga.yamiapk.presentation.common.viewmodel.ChaptersViewModel
import me.manga.yamiapk.presentation.common.viewmodel.MangaViewModel
import me.manga.yamiapk.presentation.features.home.data.ApiTitle
import me.manga.yamiapk.presentation.features.home.data.SearchType
import me.manga.yamiapk.presentation.features.home.ui.screens.HomeScreen
import me.manga.yamiapk.presentation.features.home.ui.screens.search.SearchScreen
import me.manga.yamiapk.presentation.features.home.ui.viewmodel.HomeViewModel
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourceState
import me.manga.yamiapk.presentation.features.repo_settings.ui.viewmodel.RepoSettingsViewModel
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import me.manga.yamiapk.sources_repositry.EmptyMangaRepository
import me.manga.yamiapk.work.WebViewDialog


@Composable
fun HomeRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    repoSettingsViewModel: RepoSettingsViewModel,
    onHomeTabReselectedHandler: HomeTabReselectedHandler? = null,
    mangaViewModel: MangaViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    chaptersViewModel: ChaptersViewModel = hiltViewModel(),
    coroutineScope: CoroutineScope,
    onMangaDetailsClick: (String, String, String, Boolean) -> Unit,
    onChapterClick: (ChapterItem, MangaItem, List<ChapterItem>) -> Unit,

    listState: LazyListState,
    gridState : LazyGridState,
) {

    // 1) Collect all the states
    val mangaItemsState by mangaViewModel.mangaItems.observeAsState(initial = State.Loading)
    val popularMangaItemsState by mangaViewModel.popularManga.observeAsState(initial = State.Loading)
    val searchState by mangaViewModel.mangaSearchItems.observeAsState(initial = State.Success(emptyList()))
    val searchResults = searchState.toData() ?: emptyList()
    val selected by mangaViewModel.activeTabIndex.observeAsState(initial = 0)
    val savedTitles by homeViewModel.savedMangaTitles.collectAsStateWithLifecycle()
    val isSearchVisible by mangaViewModel.isSearching.collectAsStateWithLifecycle()
    val isLoadingNextPage by mangaViewModel.LoadingNextPage.observeAsState(false)
    val searchQuery by mangaViewModel.searchQuery.observeAsState(initial = "")
    val isRefreshing by mangaViewModel.isRefreshing.collectAsStateWithLifecycle()
    val tabs = repoSettingsViewModel.enabledRepositoriesFlow.collectAsStateWithLifecycle()  // just a List<String>


    Log.i("dslghsjgsfgfsgsdfgdsfgsdf",searchResults.toString())
    val sortTypes by mangaViewModel.sortTypesFlow.collectAsStateWithLifecycle()
    val genres by mangaViewModel.genresFlow.collectAsStateWithLifecycle()
    val activeGenres by mangaViewModel.activeGenres.collectAsStateWithLifecycle()
    val multiSearchState by homeViewModel.allSearchResults.collectAsStateWithLifecycle()
    val isNewSource by repoSettingsViewModel.newSources.collectAsStateWithLifecycle()

    val composeScope = rememberCoroutineScope()


    val currentBaseUrl by mangaViewModel.currentBaseUrlFlow.collectAsStateWithLifecycle(
        initialValue = ""
    )
    val currentApi by mangaViewModel.currentApiFlow.collectAsStateWithLifecycle(
        initialValue = EmptyMangaRepository
    )
    // Get site state for current API - THIS IS WHAT YOU WANTED
    val siteState by remember(currentApi.API) { // Use API string as key, not the entire object
        repoSettingsViewModel.getSiteStateFlow(currentApi.API)
    }.collectAsStateWithLifecycle(
        initialValue = SourceState.WORKING
    )



    val siteStatusFlow = remember(currentApi) {
        homeViewModel.getSiteStatus(currentApi.API)
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scaffoldState     = rememberScaffoldState(snackbarHostState = snackbarHostState)
    val siteStatusState by siteStatusFlow
            .collectAsStateWithLifecycle(initialValue = State.Loading)
    val context = LocalContext.current

    LaunchedEffect(siteStatusState) {
        if (siteStatusState is State.Error && (siteStatusState as State.Error).code == 403) {
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.libaray_open_the_web_view),
                actionLabel = context.getString(R.string.libaray_open_the_web_button)
            )
            if (result == SnackbarResult.ActionPerformed) {
                navController.navigate(Screen.WebView("https://lekmanga.net/manga/", "Lekmanga"))
            }
        }
    }


    LaunchedEffect(Unit) {
        NavigationHandlerHolder.homeReselectHandler = onHomeTabReselectedHandler

    }
    if (siteState == SourceState.WORKING) {
        Handle403Error(
            state = mangaItemsState,
            currentApi.API,
            currentBaseUrl,
            onDismiss = {
                coroutineScope.launch {
                    delay(1000)
                    mangaViewModel.getMangaHome()
                }
            }
        )
    }
    HomeScreenContainer(
        scaffoldState = scaffoldState,

        siteState = siteState,
        currentSiteName = currentApi.API,
        // shared props for search
        isSearchVisible = isSearchVisible,
        searchResultsState = searchState,
        searchResults = searchResults,
        searchQuery = searchQuery,
        onSearchChange = { q ->
            mangaViewModel.searchQuery.value = q

            mangaViewModel.startSearch(SearchType.Normal(q))
        },
        onToggleSearch = mangaViewModel::onSearchToggle,
        onOpenInWebView = {
            composeScope.launch {

                val url = mangaViewModel.getCurrentBaseUrl()
                val api = mangaViewModel.getCurrentApi()

                Log.i("salkfjaldfsdfsdfdsafsadfsdfas","$api ========= $url")
                navController.navigate(
                    Screen.WebView(
                        url,
                        api
                    )
                )
            }
        },
        onHelp = {
            /* show help dialog, etc. */
        },
        onMangaClick = { url, api, title, isSaved ->
            onMangaDetailsClick(url, api, title, isSaved)
        },
        onSaveToggle = { mangaItem ->
            val key = ApiTitle(api = mangaItem.api, title = mangaItem.title)

            if (savedTitles.contains(key)) {
                homeViewModel.toggleManga(mangaItem.toMangaInfo(emptyChapterItem()))
            } else {
                coroutineScope.launch(Dispatchers.IO) {
                    chaptersViewModel.getChaptersDataR(mangaItem.url).collect { state ->
                        state.toData()?.let { mangaInfo ->
                            homeViewModel.toggleManga(mangaInfo)
                        }
                    }
                }
            }
        },
        onChapterClick = onChapterClick,

        // home‐only props
        mangaItemsState = mangaItemsState,
        popularManga = popularMangaItemsState,
        savedTitles = savedTitles,
        isLoadingNextPage = isLoadingNextPage,
        tabs = tabs,        // note: adjust if needed
        activeTabIndex = selected ?: 0,
        isRefreshing = isRefreshing,
        onRefresh = { mangaViewModel.getMangaHome() },
        onEndReached = { mangaViewModel.onLastItemVisible() },
        onTabSelected = { mangaViewModel.onTabSelected(it) },
        onEditTabs = {

            navController.navigate(Screen.RepoSettings(false))
            repoSettingsViewModel.setNewSources(false)
        },
        onSettingsClick = {

        },
        sortTypes = sortTypes,
        onSortClick = mangaViewModel::onSortClick,           // ← new callback
        genres = genres,
        onGenreClicked = mangaViewModel::onGenreClicked,
        selectedGenres = activeGenres,
        closeSearch = mangaViewModel::closeSearch,
        buildImageRequest = { context, url, api ->
            mangaViewModel.buildImageRequest(context, url, api)
        },
        listState = listState,
        multiSearchState = multiSearchState,
        onMultiSearch = {
            homeViewModel.fetchAllSearchResults(it)},
        onRepoChange = mangaViewModel::onRepoChange,
        isNewSource = isNewSource,
        gridState = gridState,

    )


}

@Composable
fun HomeScreenContainer(
    scaffoldState: ScaffoldState,
    siteState: SourceState,
    currentSiteName: String,
    //–– shared props ––
    isSearchVisible: Boolean,
    searchResultsState: State<List<MangaItem>>,
    searchResults: List<MangaItem>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenInWebView: () -> Unit,
    onHelp: () -> Unit,
    onMangaClick:(String, String, String, Boolean) -> Unit,
    onSaveToggle: (MangaItem) -> Unit,
    onChapterClick: (ChapterItem, MangaItem, List<ChapterItem>) -> Unit,
    mangaItemsState: State<List<MangaItem>>,
    popularManga: State<List<PopularManga>>,
    savedTitles: Set<ApiTitle>,
    isLoadingNextPage: Boolean,
    tabs:androidx. compose. runtime.State<List<BaseMangaRepository>>,
    activeTabIndex: Int,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onEndReached: () -> Unit,
    onTabSelected: (Int) -> Unit,
    onEditTabs: () -> Unit,
    onSettingsClick :() -> Unit,
    sortTypes: Set<String>,
    onSortClick: (String, String, String) -> Unit,             // ← new callback
    genres: Set<String>,
    onGenreClicked:(String)->Unit,
    selectedGenres :String,
    closeSearch:()->Unit,
    buildImageRequest:(Context, String, String) -> ImageRequest,
    listState: LazyListState,
    gridState : LazyGridState,
    multiSearchState: Map<String, State<List<MangaItem>>>,
    onMultiSearch:(query :String)-> Unit,
    onRepoChange:suspend (String) -> Unit,
    isNewSource: Boolean,


) {
    if (isSearchVisible) {
        BackHandler(
            // your condition to enable handler
            enabled = isSearchVisible
        ) {
          closeSearch()
        }
        SearchScreen(
            searchResultsState = searchResultsState,
            searchQuery = searchQuery,
            savedTitles = savedTitles,

            onSearchChange = onSearchChange,
            onToggleSearch = onToggleSearch,
            onOpenInWebView = onOpenInWebView,
            onHelp = onHelp,
            onMangaClick = onMangaClick,
//            onSettingsClick= onSettingsClick,
            sortTypes = sortTypes,
            onSortClick = onSortClick,
            genres = genres,
            onGenreClicked = onGenreClicked,
            selectedGenres =selectedGenres,
            buildImageRequest =buildImageRequest,
            multiSearchState = multiSearchState,
            onMultiSearch = onMultiSearch,
            onRepoChange = onRepoChange

        )
    } else {
        HomeScreen(
            siteState = siteState,
            currentSiteName = currentSiteName,
            mangaItemsState = mangaItemsState,
            popularManga = popularManga,
            savedTitles = savedTitles,
            isLoadingNextPage = isLoadingNextPage,
            tabs = tabs,
            activeTabIndex = activeTabIndex,
            searchQuery = searchQuery,
            isRefreshing = isRefreshing,
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
            scaffoldState = scaffoldState,
            listState = listState,
            isNewSource = isNewSource,
            gridState = gridState,  // Add this parameter

        )
    }
}