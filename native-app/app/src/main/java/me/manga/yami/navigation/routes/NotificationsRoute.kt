package me.manga.yamiapk.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.yamiapk.ad_mob.AdViewModel
import me.manga.yamiapk.data.local.entity.ChapterNotification
import me.manga.yamiapk.presentation.features.download.ui.test2.DownloadViewModelv2
import me.manga.yamiapk.presentation.features.notifications.ui.screens.NotificationScreen
import me.manga.yamiapk.presentation.features.notifications.ui.viewmodel.NotificationsViewModel

/**
 * Wrapper composable for UpdatesScreen (Notifications).
 * Reads no nav-args, initializes ViewModel, and passes callbacks.
 */
@Composable
fun NotificationsRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    onNotificationClick: (ChapterNotification) -> Unit,
    onNotificationImgClick: (ChapterNotification) -> Unit,
    downloadViewModel:  DownloadViewModelv2,
    adViewModel: AdViewModel ,
) {
    // 1) Obtain ViewModel scoped to this destination
    val viewModel: NotificationsViewModel = hiltViewModel(backStackEntry)
    val downloadingChapters by  downloadViewModel.queuedChapterIds.collectAsStateWithLifecycle()
    val runningChapter by  downloadViewModel.runningChapter.collectAsStateWithLifecycle()
    val context = LocalContext.current


    // 3) Render UpdatesScreen with navigation callbacks
    NotificationScreen(
        viewModel = viewModel,
        onNotificationClick = onNotificationClick,
        onNotificationImgClick = onNotificationImgClick,
        downloadingChapters = downloadingChapters,
        onNotificationDownloadClick = {

            downloadViewModel.downloadChapterNotification(it,it.api,it.mangaTitle)
            adViewModel.onDownloadStarted(
                context,
                onProceed = {}
            ){
            }
        },
        runningChapter =  runningChapter
    )
}