package me.manga.yamiapk.navigation.routes


import android.util.Log
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.manga.yamiapk.R
import me.manga.yamiapk.core.network_connectivity.ConnectivityObserver
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toChapterItems
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toMangaInfo
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.navigation.safePopBackStack
import me.manga.yamiapk.ad_mob.AdViewModel
import me.manga.yamiapk.core.util.Handle403Error
import me.manga.yamiapk.presentation.common.screens.LoadingScreen
import me.manga.yamiapk.presentation.features.download.ui.test2.DownloadViewModelv2
import me.manga.yamiapk.presentation.features.home.ui.viewmodel.HomeViewModel
import me.manga.yamiapk.presentation.features.library_details.ui.screens.LibraryMangaScreen
import me.manga.yamiapk.presentation.features.library_details.ui.viewmodel.LibraryDetailsViewModel

@Composable
fun LibraryMangaRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    onChapterClick: (SavedChapterEntity, SavedMangaEntity, List<SavedChapterEntity>) -> Unit,
    viewModel: LibraryDetailsViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    downloadViewModel: DownloadViewModelv2,
    adViewModel: AdViewModel ,


    ) {

    val snackbarScope = rememberCoroutineScope()
    // 1) Read the encoded manga URL from nav args
    LaunchedEffect(Unit) {
        val args = backStackEntry.toRoute<Screen.LibraryMangaDetails>()
        args.mangaId.let { viewModel.loadMangaDetails(it) }

        adViewModel.preloadAds()
    }

//    val toastHostState = remember { ToastHostState() }

    // 2) Kick off loading when first composed
    val manga by viewModel.manga.collectAsStateWithLifecycle()
    val sortAscending by viewModel.sortAscending.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.filterType.collectAsStateWithLifecycle()
    val selectedSort by viewModel.sortType.collectAsStateWithLifecycle()
    val showWebViewDalog = remember { mutableStateOf(false) }

    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val savedTitles by homeViewModel.savedMangaTitles.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val requestState by viewModel.imageStatus.collectAsStateWithLifecycle()

    val internetStatus by downloadViewModel.networkAvailable.collectAsStateWithLifecycle(
        ConnectivityObserver.Status.Unavailable
    )
    val downloadingChapters by downloadViewModel.queuedChapterIds.collectAsStateWithLifecycle(emptyList())
    val isWorkRunning by downloadViewModel.isDownloading.collectAsStateWithLifecycle(false)
    val runningChapter by  downloadViewModel.runningChapter.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // Snackbar state
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(requestState) {
        if (requestState is State.Error && (requestState as State.Error).code == 403) {
//            val result = snackbarHostState.showSnackbar(
//                message = context.getString(R.string.libaray_open_the_web_view),
//                actionLabel = context.getString(R.string.libaray_open_the_web_button)
//            )
//            if (result == SnackbarResult.ActionPerformed) {
//                navController.navigate(Screen.WebView(manga.url, manga.api))
//            }

            showWebViewDalog.value = true
        }
    }


    LaunchedEffect(manga.id) {
        if (manga.id == 0L) return@LaunchedEffect
        adViewModel.preloadAds()
    }
    if (manga.id == 0L) {
        LoadingScreen()
        return
    }


    // 4) Invoke your screen composable
    LibraryMangaScreen(
        manga = manga,
        chapters = chapters,
        savedTitles = savedTitles,
        runningChapter = runningChapter,
        downloadingChapters = downloadingChapters,
        onChapterClick = { chapter, manga, chapters ->
//
//            viewModel.deleteChapter(chapter)
            viewModel.setIsNewChapter(chapter)
            viewModel.updateLastOpen(manga.id)
            onChapterClick(chapter, manga, chapters)

        },
        onChapterDownloadClick = { chapter, manga ->

            if (internetStatus != ConnectivityObserver.Status.Available) {


//                toastHostState.show(
//                    "No internet connection. Please try again when you’re online."
//                )
                snackbarScope.launch {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.no_internet_connection_please_try_again_when_you_re_online)
                    )
                }
                return@LibraryMangaScreen
            }
            downloadViewModel.downloadChapter(
                chapter,
                manga.api,
                manga.title
            )
            adViewModel.onDownloadStarted(
                context,
                onProceed = {}
            )



        },

        onChapterBookmarkClick = { chapter ->
            viewModel.toggleChapterBookmark(
                chapter.id
            )
        },


        onBackClick = {
            navController.safePopBackStack()
        },
        downloadAllManga = {
            CoroutineScope(Dispatchers.Main).launch {


                if (internetStatus != ConnectivityObserver.Status.Available) {


//                toastHostState.show(
//                    "No internet connection. Please try again when you’re online."
//                )
                    snackbarScope.launch {
                        snackbarHostState.showSnackbar(
                            message = context.getString(R.string.no_internet_connection_please_try_again_when_you_re_online)
                        )
                    }
                    return@launch
                }


                val notDownloadedChapters = chapters.filter { !it.isDownloaded }
                downloadViewModel.downloadChapters(notDownloadedChapters, manga)

                adViewModel.showRewardedAdManually(context){

                }

            }
        },
        isDownloadingAll = isWorkRunning,
        cancelAllDownloads = { downloadViewModel.cancelDownloads() },
        onChapterReadClick = { items ->
            viewModel.toggleChapterRead(
                items.id
            )
        },
        onRefreshClick = {
            viewModel.refreshChapters()
        },
        isRefreshing = isRefreshing,
        onCustomDownload = { chapters, manga ->

            if (internetStatus != ConnectivityObserver.Status.Available) {


                snackbarScope.launch {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.no_internet_connection_please_try_again_when_you_re_online)
                    )
                }
                return@LibraryMangaScreen
            }
            adViewModel.showRewardedAdManually(context){

            }
            if (chapters.isEmpty()) return@LibraryMangaScreen
            val notDownloadedChapters = chapters.filter { !it.isDownloaded }

            downloadViewModel.downloadChapters(notDownloadedChapters, manga)

        },
        sortAscending = sortAscending,
        toggleSort = { viewModel.toggleSort() },
        onBookmarkAll = { items ->
            viewModel.toggleChaptersBookmark(items.map { it.id })
        },
        onMarkAllRead = { items ->
            viewModel.toggleChaptersRead(items.map { it.id })
        },
        onOpenInWebView = { mangaUrl, api ->


            navController.navigate(Screen.WebView(mangaUrl, api))


        },
        snackbarHostState = snackbarHostState,
        onMangaBookmarkClick = { chapterItems, mangaItems ->
            homeViewModel.toggleManga(mangaItems.toMangaInfo(chapterItems.toChapterItems()))
        },
        onCancelChapter = { chapter, manga ->

            downloadViewModel.onCancelChapterTapped(chapter.id)
        },
        onCancelRunningChapter = { chapter, manga ->
            downloadViewModel.cancelRunningDownload(chapter.id, manga.id)
        },
        onMarkAllDownRead = {
            val idx = chapters.reversed().indexOf(it)
            val beforeList = if (idx > 0) chapters.reversed()
                .subList(0, idx) else emptyList<SavedChapterEntity>()
            viewModel.markChaptersRead(beforeList.map { it.id })

        },
        onDeleteAll = {

            viewModel.deleteAllChapters(it)
        },
        selectedFilter = selectedFilter,
        onFilterItemSelected = viewModel::setFilter,
        selectedSort = selectedSort,
        onSortItemSelected = viewModel::setSortType
    )

//    Box(Modifier.fillMaxSize()) {
//        // … your screen content goes here …
//
//        // 3) Place the ToastHost *last*, so it draws over everything
//        Box(Modifier.align(Alignment.BottomCenter)) {
//            ToastHost(toastHostState)
//        }
//    }
}