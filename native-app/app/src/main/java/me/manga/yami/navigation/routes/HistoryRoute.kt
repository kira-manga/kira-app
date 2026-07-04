package me.manga.yamiapk.navigation.routes


import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.yamiapk.data.local.entity.HistoryItemD
import me.manga.yamiapk.presentation.features.history.ui.screens.HistoryScreen
import me.manga.yamiapk.presentation.features.history.ui.viewmodel.HistoryViewModel

/**
 * Wrapper composable for HistoryScreen. Reads no nav-args.
 * Initializes the HistoryViewModel and passes callbacks for navigation.
 */
@Composable
fun HistoryRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    onHistoryImgClick: (HistoryItemD) -> Unit,
    onHistoryItemClick: (HistoryItemD) -> Unit,

    ) {
    // 1) Get your ViewModel scoped to this destination
    val viewModel: HistoryViewModel = hiltViewModel(backStackEntry)

    // 2) Collect UI state

    // 3) Render the screen, wiring up navigation callbacks
    HistoryScreen(
        viewModel = viewModel,
        onMangaClick = onHistoryImgClick,
        onChapterClick = onHistoryItemClick,
        buildImageRequest = viewModel::buildImageRequest

    )
}
