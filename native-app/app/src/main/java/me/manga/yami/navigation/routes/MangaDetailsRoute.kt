package me.manga.yamiapk.navigation.routes

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.util.Handle403Error
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.navigation.safePopBackStack
import me.manga.yamiapk.presentation.features.details.ui.screens.MangaDetailsScreen
import me.manga.yamiapk.presentation.features.details.ui.viewmodel.MangaDerailsViewModel
import me.manga.yamiapk.presentation.features.home.ui.components.HelpVideoDialog
import me.manga.yamiapk.presentation.features.home.ui.viewmodel.HomeViewModel
import me.manga.yamiapk.work.WebViewDialog

/**
 * Wrapper composable for MangaDetailsScreenClean.
 * Reads `mangaUrl` arg, fetches chapters, and wires up ViewModels.
 */
@Composable
fun MangaDetailsRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    onChapterClick:  (ChapterItem, MangaInfo, List<ChapterItem>) -> Unit,
    onDownloadClick :()-> Unit,
) {
    // 2) ViewModels scoped to this destination
    val homeViewModel: HomeViewModel = hiltViewModel()
    val mangaDerailsViewModel: MangaDerailsViewModel = hiltViewModel()

    val args = backStackEntry.toRoute<Screen.MangaDetails>()

    val coroutineScope = rememberCoroutineScope()

    var showHelpDialog by remember { mutableStateOf(false) }


    // 4) Observe state
    val detailsState by mangaDerailsViewModel.mangaDetails.collectAsStateWithLifecycle()
    val savedTitles by homeViewModel.savedMangaTitles.collectAsStateWithLifecycle()
    val hasShownRemoveBookMarkFlow by homeViewModel.hasShownRemoveBookMarkFlow.collectAsStateWithLifecycle()

    Handle403Error(
        state = detailsState,
        api = args.api ,
        url = args.mangaUrl,
        onDismiss = {
            coroutineScope.launch {
            delay(1000)
            mangaDerailsViewModel.onRetry(args.mangaUrl,args.api)
            }
        }
    )
    // 5) Render screen
    MangaDetailsScreen(
        state = detailsState,
        savedTitles = savedTitles,
        onBackClick = { navController.safePopBackStack() },
        onMangaBookmark = {

            homeViewModel.toggleManga(it)
        },
        onChapterClick = onChapterClick,
        onDownloadClick =onDownloadClick,
        hasShownRemoveBookMark =hasShownRemoveBookMarkFlow,
        onShownRemoveBookMark = {
            homeViewModel.setShownRemoveBookMarkFlow(true)
        },
        onRetry = {

            mangaDerailsViewModel.onRetry(args.mangaUrl,args.api)
        },
        onOpenInWebViewError = {

            if (mangaDerailsViewModel.currentUrl == "") {
                return@MangaDetailsScreen
            }else{
                navController.navigate(Screen.WebView(mangaDerailsViewModel.currentUrl, api = args.api))

            }

        },
        onOpenInWebView = {url , api ->

            navController.navigate(Screen.WebView(url,api))

        },
        onHelp = {
            showHelpDialog = true
        },

        isPlus18 = mangaDerailsViewModel::isPlus18,
        buildImageRequest = { context , url , api ->

            mangaDerailsViewModel.buildImageRequest(context,url,api)
        }

    )
    if (showHelpDialog) {
        HelpVideoDialog(
            onDismiss = { showHelpDialog = false }
        )
    }



}
