package me.manga.yamiapk.presentation.features.library.ui.screens

import AutoSubtitleText
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.PullRefreshState
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.request.ImageRequest
import me.manga.yamiapk.R
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.util.date.Date.timeAgo
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.domain.model.MangaDisplayItem
import me.manga.yamiapk.presentation.common.componants.scroll.VerticalGridFastScroller
import me.manga.yamiapk.presentation.common.screens.LoadingScreen
import me.manga.yamiapk.presentation.features.library.ui.components.EmptyLibraryPlaceholder
import me.manga.yamiapk.presentation.features.library.ui.viewmodel.LibraryViewModel
import java.time.LocalDateTime

// LibraryScreen.kt
@OptIn(ExperimentalMaterialApi::class)
@Composable
fun LibraryItems(
    items:  State<List<MangaDisplayItem>>,
    errorMessage: String?,
    lastUpdated: LocalDateTime?,
    onMangaClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    pullRefreshState: PullRefreshState,
    isRefreshing: Boolean,
    itemsPerRow: Int,
    showDetails: Boolean,
    showSource: Boolean,
    showCount: Boolean,
    buildImageRequest: suspend (Context, String, String) -> ImageRequest,
    tabs: LibraryViewModel.FilterTabs,
    onTabChanged: (LibraryViewModel.FilterTabs) -> Unit, // <-- ADD THIS PARAMETER,
    onToggleLike: (SavedMangaEntity) -> Unit,

    onToggleWatchLater: (SavedMangaEntity) -> Unit,
    onToggleDelete: (Long) -> Unit,

    showButtons : Boolean,
    showTabs : Boolean,


    ) {

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp

    val filterTabs = LibraryViewModel.FilterTabs.entries.toList()
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    val libraryName = when (tabIndex) {
        1 -> stringResource(R.string.watching_now)
        2 -> stringResource(R.string.likes)
        else -> stringResource(id = R.string.title_library)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pullRefresh(pullRefreshState)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Always shown at top: FlowRow or Row

            if (showTabs) {
                TabRow(
                    selectedTabIndex = tabIndex,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    filterTabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = tabIndex == index,
                            onClick = {
                                tabIndex = index
                                onTabChanged(tab)
                            },
                            text = {
                                AutoSubtitleText(
                                    text = tab.getDisplayName(context),
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    maxSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                    }
                }
            }



            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.last_updated,
                        lastUpdated?.timeAgo(context) ?: stringResource(R.string.not_updated_yet)
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                if (showCount) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.items_count,
                            (items as? State.Success)?.data?.size ?: 0,
                            (items as? State.Success)?.data?.size ?: 0
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
            when (items) {
                is State.Loading -> LoadingScreen()
                is State.Error -> Snackbar(modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(items.message)
                }

                is State.Success -> {
                    val mangaList = items.data
                    if (mangaList.isEmpty()) {
                        EmptyLibraryPlaceholder(libraryName)
                    } else {
                        Column(Modifier.fillMaxSize()) {


                            val gridState = rememberLazyGridState()
                            val minSizeDp = remember(screenWidthDp, itemsPerRow) {
                                if (itemsPerRow > 0) (screenWidthDp / itemsPerRow).dp else 140.dp
                            }

                            VerticalGridFastScroller(
                                state = gridState,
                                columns = if (itemsPerRow > 0) GridCells.Fixed(itemsPerRow) else GridCells.Adaptive(
                                    minSizeDp
                                ),
                                arrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(8.dp),
                                modifier = Modifier.fillMaxSize(),
                                thumbColor = MaterialTheme.colorScheme.primary
                            ) {
                                LazyVerticalGrid(
                                    columns = if (itemsPerRow > 0) GridCells.Fixed(itemsPerRow) else GridCells.Adaptive(
                                        minSizeDp
                                    ),
                                    contentPadding = PaddingValues(8.dp),
                                    state = gridState,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    items(
                                        items = mangaList,
                                        key = { item -> "manga_${item.manga.id}" }, // More specific key
                                        contentType = { "manga_card" } // Add content type for better recycling
                                    ) { manga ->
                                        // Wrap in remember to prevent unnecessary recompositions
                                        key(manga.manga.id) {
                                            MangaCard(
                                                mangaDisplayItem = manga,
                                                onMangaClick = onMangaClick,
                                                showDetails = showDetails,
                                                showSource = showSource,
                                                buildImageRequest = buildImageRequest,
                                                onToggleLike = onToggleLike,
                                                onToggleWatchLater = onToggleWatchLater,
                                                itemsPerRow = itemsPerRow,
                                                showButtons = showButtons,
                                                onToggleDelete = onToggleDelete
                                            )
                                        }
                                    }
                                }
                            }
                        }

                    }
                }
            }
        }

        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        errorMessage?.let { msg ->
            Snackbar(Modifier.align(Alignment.BottomCenter)) { Text(msg) }
        }
    }
}