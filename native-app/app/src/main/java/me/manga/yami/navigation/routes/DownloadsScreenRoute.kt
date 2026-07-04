package me.manga.yamiapk.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toChapterEntity
import me.manga.yamiapk.navigation.safePopBackStack
import me.manga.yamiapk.presentation.features.download.data.DownloadingState
import me.manga.yamiapk.presentation.features.download.ui.screens.DownloadsScreen
import me.manga.yamiapk.presentation.features.download.ui.test2.DownloadViewModelv2

@Composable
fun DownloadsScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    downloadViewModeltestv2: DownloadViewModelv2 = hiltViewModel(),
) {
    // Get paged flows
//    val downloadsPaged = remember { downloadViewModeltestv2.downloadsPaged }

    val activeDownloadsPaged = remember {
        downloadViewModeltestv2.getDownloadsByState(
            listOf(DownloadingState.RUNNING, DownloadingState.QUEUED)
        )
    }

    val failedDownloadsPaged = remember {
        downloadViewModeltestv2.getDownloadsByState(
            listOf(DownloadingState.FAILED)
        )
    }

    val completedDownloadsPaged = remember {
        downloadViewModeltestv2.getDownloadsByState(
            listOf(DownloadingState.SUCCESS)
        )
    }

    DownloadsScreen(
//        downloadsPaged = downloadsPaged,
        activeDownloadsPaged = activeDownloadsPaged,
        failedDownloadsPaged = failedDownloadsPaged,
        completedDownloadsPaged = completedDownloadsPaged,
        onRetry = {
            downloadViewModeltestv2.downloadChapter(
                it.toChapterEntity(),
                mangaApi = it.api,
                title = it.mangaTitle ?: ""
            )
        },
        runningChapterCancel = {
            downloadViewModeltestv2.cancelRunningDownload(it.chapterId, it.mangaId)
        },
        onCancel = {
            downloadViewModeltestv2.onCancelChapterTapped(it.chapterId)
        },
        onDelete = {
            downloadViewModeltestv2.deleteDownload(it.chapterId)
        }
    ) {
        navController.safePopBackStack()
    }
}