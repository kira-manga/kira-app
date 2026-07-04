package me.manga.yamiapk.navigation.routes

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.yamiapk.R
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.domain.model.MangaDisplayItem
import me.manga.yamiapk.navigation.Screen
import me.manga.yamiapk.presentation.features.download.ui.test2.DownloadViewModelv2
import me.manga.yamiapk.presentation.features.library.ui.screens.LibraryScreen
import me.manga.yamiapk.presentation.features.library.ui.viewmodel.LibraryViewModel
import me.manga.yamiapk.presentation.features.refresh.ui.viewmodel.RefreshViewModel
import me.manga.yamiapk.presentation.features.whatsnew.viewmodel.WhatsNewViewModel

/**
 * Reads no nav-args (just uses the saved data),
 * wires up the ViewModel and pull-to-refresh,
 * then hosts the LibraryScreen.
 */
@Composable
fun LibraryRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    downloadViewModel: DownloadViewModelv2,
    onOpenRandomClick: (State<List<MangaDisplayItem>>) -> Unit,
    onLibraryMangaClick: (Long) -> Unit,
    whatsNewViewModel: WhatsNewViewModel
) {
    // 1. Create / remember your scope and viewModel
    val context = LocalContext.current
    val vm: LibraryViewModel = hiltViewModel(backStackEntry)
    val refreshViewModel: RefreshViewModel = hiltViewModel()

    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val isRefreshing by refreshViewModel.isWorkRunning.collectAsStateWithLifecycle(false)

    val shouldShowWhatsNew by whatsNewViewModel.shouldShowWhatsNew.collectAsStateWithLifecycle()
    val isLoading by whatsNewViewModel.isLoading.collectAsStateWithLifecycle()

    // Track if we've already navigated to prevent loops
    var hasNavigatedToWhatsNew by remember { mutableStateOf(false) }

    // State for delete confirmation dialog
    var showDeleteDialog by remember { mutableStateOf(false) }
    var mangaToDelete by remember { mutableStateOf<Long?>(null) }

    // Only navigate to What's New if we should show it AND we haven't navigated yet
    LaunchedEffect(shouldShowWhatsNew, isLoading) {
        if (shouldShowWhatsNew && !isLoading && !hasNavigatedToWhatsNew) {
            hasNavigatedToWhatsNew = true
            navController.navigate(Screen.WhatsNewScreen(true))
        }
    }

    // Reset the navigation flag only when shouldShowWhatsNew becomes false after being true
    // This ensures we don't navigate again until the next version update
    LaunchedEffect(shouldShowWhatsNew) {
        if (!shouldShowWhatsNew && hasNavigatedToWhatsNew) {
            // User has seen the What's New screen and it's been marked as seen
            hasNavigatedToWhatsNew = false
        }
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog && mangaToDelete != null) {
        AlertDialog(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp) // dialog side padding
                .clip(RoundedCornerShape(16.dp)), // <-- rounded corners here
            onDismissRequest = {
                showDeleteDialog = false
                mangaToDelete = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.delete_manga),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.are_you_sure_you_want_to_remove_this_manga_from_your_library_all_progress_read_status_bookmarks_and_downloaded_chapters_for_this_manga_will_be_permanently_deleted_and_cannot_be_recovered),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            ,
            confirmButton = {
                TextButton(
                    onClick = {
                        mangaToDelete?.let { vm.removeManga(it) }
                        showDeleteDialog = false
                        mangaToDelete = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        mangaToDelete = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        )
    }

    LibraryScreen(
        viewModel = vm,
        downloadViewModel = downloadViewModel,
        onMangaClick = onLibraryMangaClick,
        isRefreshing = isRefreshing,
        onRefreshClick = {
            onRefreshLibrary(it, refreshViewModel, context)
        },
        uiState = uiState,
        onOpenRandomClick = onOpenRandomClick,
        onRefresh = {
            onRefreshLibrary(it, refreshViewModel, context)
        },
        onTabChanged = {
            vm.onTabChanged(it)
        },
        onToggleLike = vm::toggleLiked,
        onToggleWatchLater = vm::toggleWatchingNow,
        onToggleDelete = { manga ->
            mangaToDelete = manga
            showDeleteDialog = true
        }
    ) {
        navController.navigate(Screen.DownloadsScreen)
    }
}

fun onRefreshLibrary(
    savedEntities: State<List<MangaDisplayItem>>,
    refreshViewModel: RefreshViewModel,
    context: Context
) {
    val items = savedEntities.toData()
    // pick a random one, or null if empty
    if (!items.isNullOrEmpty()) {
        refreshViewModel.refreshLibrary()
    } else {
        Toast
            .makeText(context, "No manga in your library yet!", Toast.LENGTH_SHORT)
            .show()
    }
}