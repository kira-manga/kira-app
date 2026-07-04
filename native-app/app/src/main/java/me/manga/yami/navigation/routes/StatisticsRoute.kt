package me.manga.yamiapk.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.yamiapk.navigation.safePopBackStack
import me.manga.yamiapk.presentation.features.statistics.ui.screens.StatisticsScreen
import me.manga.yamiapk.presentation.features.statistics.ui.viewmodel.StatisticsViewModel

@Composable
fun StatisticsRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    viewModel: StatisticsViewModel = hiltViewModel()
){

    // Collect state from ViewModel
    val inLibrary by viewModel.inLibrary.collectAsStateWithLifecycle()
    val readDuration by viewModel.readDuration.collectAsStateWithLifecycle()
    val completedEntries by viewModel.completedEntries.collectAsStateWithLifecycle()
    val entriesStarted by viewModel.entriesStarted.collectAsStateWithLifecycle()
    val chaptersTotal by viewModel.chaptersTotal.collectAsStateWithLifecycle()
    val chaptersRead by viewModel.chaptersRead.collectAsStateWithLifecycle()
    val chaptersDownloaded by viewModel.chaptersDownloaded.collectAsStateWithLifecycle()
    val chaptersBookmarked by viewModel.chaptersBookmarked.collectAsStateWithLifecycle()



    StatisticsScreen(
        inLibrary = inLibrary,
        readDuration = readDuration,
        completedEntries = completedEntries,
        entriesStarted = entriesStarted,
        chaptersTotal = chaptersTotal,
        chaptersRead = chaptersRead,
        chaptersDownloaded = chaptersDownloaded,
        chaptersBookmarked = chaptersBookmarked,
        onBack = {
            navController.safePopBackStack()
        }
    )
}