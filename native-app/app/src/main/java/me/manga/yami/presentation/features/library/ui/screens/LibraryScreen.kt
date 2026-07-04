package me.manga.yamiapk.presentation.features.library.ui.screens

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.manga.yamiapk.R
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.domain.model.MangaDisplayItem
import me.manga.yamiapk.presentation.common.componants.app_bars.SearchAppBar
import me.manga.yamiapk.presentation.common.componants.app_bars.TopAppBarCom
import me.manga.yamiapk.presentation.common.componants.list_items.SwitchItem
import me.manga.yamiapk.presentation.features.download.ui.test2.DownloadViewModelv2
import me.manga.yamiapk.presentation.features.library.ui.components.AnimatedPreloader
import me.manga.yamiapk.presentation.features.library.ui.components.library_sheet.CustomFilterBottomSheet
import me.manga.yamiapk.presentation.features.library.ui.components.library_sheet.DisplayOptionsSection
import me.manga.yamiapk.presentation.features.library.ui.components.library_sheet.FilterChipsRow
import me.manga.yamiapk.presentation.features.library.ui.components.library_sheet.SortOptionsSection
import me.manga.yamiapk.presentation.features.library.ui.viewmodel.LibraryViewModel

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    downloadViewModel: DownloadViewModelv2,
    onMangaClick: (Long) -> Unit,
    isRefreshing: Boolean,
    onRefreshClick:(State<List<MangaDisplayItem>>)->Unit,
    onOpenRandomClick : (State<List<MangaDisplayItem>>)->Unit,
    uiState: LibraryViewModel.UiState,
    onRefresh : ( State<List<MangaDisplayItem>>)->Unit,
    onTabChanged: (LibraryViewModel.FilterTabs) -> Unit, // <-- ADD THIS PARAMETER

    onToggleLike: (SavedMangaEntity) -> Unit,
    onToggleWatchLater: (SavedMangaEntity) -> Unit,
    onToggleDelete: (Long) -> Unit,

    onDownloadClick: () -> Unit,

) {

    // UI state for search bar and options menu
    val (
        _,
        _,
        _,
        _,
        filter,
        sort,
        tabs,
        ascending,
        showDetails,
        showButtons,
        showTabs,
        showSource,
        showCount,
        _,
        itemsPerRow,
        searchQuery,
    ) = uiState

    val context = LocalContext.current



    var showSearchBar by remember { mutableStateOf(false) }
    var menuExpanded  by remember { mutableStateOf(false) }
    var showSheet     by remember { mutableStateOf(false) }

    val isDownloading by downloadViewModel.isDownloading.collectAsStateWithLifecycle()
    val lastUpdated   by viewModel.lastUpdated.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.onSearchChanged("")
    }


    BackHandler(
        // your condition to enable handler
        enabled = showSearchBar
    ) {
        showSearchBar = false
        viewModel.onSearchChanged("")
    }
    Scaffold(
        modifier = Modifier
            .fillMaxSize(),

        topBar = {
            if (showSearchBar) {
                SearchAppBar(
                    query = searchQuery,
                    onQueryChange = viewModel::onSearchChanged,
                    onToggleSearch = {
                        showSearchBar = false
                        viewModel.onSearchChanged("")
                    },
                    onSearch = {

                        if(it.isNotBlank()){
                            viewModel.onSearchChanged("")
                        }

                    },

                    )

            } else {
                TopAppBarCom(
                    title = stringResource(id = R.string.title_library),
                    actions = {
                        IconButton(onClick = { showSearchBar = true }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.contentDescription_search))
                        }
                        if (isDownloading){
                            IconButton(onClick = onDownloadClick) {
                                AnimatedPreloader(
                                    backgroundColor = MaterialTheme.colorScheme.background,
                                    iconColor = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(onClick = {

                            showSheet = true
                        }) {
                            Icon(Icons.Default.FilterList, contentDescription = stringResource(R.string.contentDescription_filter))
                        }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.contentDescription_options))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dropdown_button_refresh)) },
                                onClick = {
                                    onRefresh(uiState.items)
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dropdown_button_open_random_manga)) },
                                onClick = {
                                    onOpenRandomClick(uiState.items)

                                    menuExpanded = false
                                }
                            )
                        }
                    }
                )
            }
        },
        content = { innerPadding ->
            val pullRefreshState = rememberPullRefreshState(
                refreshing = isRefreshing,
                onRefresh = {
                    onRefreshClick(uiState.items) },

                )




            LibraryItems(
                items = uiState.items,
                errorMessage = uiState.errorMessage,
                lastUpdated = lastUpdated,
                onMangaClick = onMangaClick,
                modifier = Modifier.padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = 8.dp
                ),
                pullRefreshState = pullRefreshState,
                isRefreshing = isRefreshing,
                itemsPerRow = uiState.itemsPerRow,
                showDetails = uiState.showDetails,
                showSource = uiState.showSource,
                showCount = uiState.showCount,
                buildImageRequest = viewModel::buildItemsImageRequest,
                tabs = tabs,
                onTabChanged = onTabChanged,
                onToggleWatchLater =onToggleWatchLater,
                onToggleLike = onToggleLike,
                showButtons = showButtons,
                showTabs = showTabs,
                onToggleDelete = onToggleDelete
            )




        }

    )

    CustomFilterBottomSheet(
        showSheet = showSheet,
        onDismiss = { showSheet = false },

        tabs = listOf(
            stringResource(R.string.library_bottom_sheet_tab_filter),
            stringResource(R.string.library_bottom_sheet_tab_sort),
            stringResource(R.string.library_bottom_sheet_tab_display)
        ),
        initialTabIndex = 0,
        pageContents = listOf(
            {

                FilterChipsRow(
                items = LibraryViewModel.FilterType.entries,
                selectedItem = filter,
                onItemSelected = viewModel::onFilterChanged,
                label = { it.getDisplayName(context) }
            )
            },
            {
                SortOptionsSection(
                    items = LibraryViewModel.SortType.entries,
                    selectedItem = sort,
                    isAscending = ascending,
                    onItemSelected = viewModel::onSortChanged,
                    onDirectionChange =  viewModel::onSortDirectionChanged,
                    label = { it.getDisplayName(context) },
                    headerText = stringResource(R.string.sort_options_title),
                    sortByText = stringResource(R.string.sort_by_label),
                    sortDirectionText = stringResource(R.string.sort_direction_label),
                    ascendingText = stringResource(R.string.sort_direction_ascending),
                    descendingText = stringResource(R.string.sort_direction_descending)
                )
             },
            {
//             


                DisplayOptionsSection(
                    modifier = Modifier,
                    count =itemsPerRow,
                    onCountChange = viewModel::onItemsPerRowChange,
                    switchContents = listOf(
                        {
                            Divider()
                            SwitchItem(
                                title = stringResource(R.string.show_items_details),
                                checked = showDetails,
                                onCheckedChange = viewModel::onToggleDetails,
                            )
                        },
                        {
                            Divider()
                            SwitchItem(
                                title = stringResource(R.string.show_items_source),
                                checked = showSource,
                                onCheckedChange =viewModel::    onToggleSource,
                            )
                        },
                        {
                            Divider()
                            SwitchItem(
                                title = stringResource(R.string.show_items_count),
                                checked = showCount,
                                onCheckedChange = viewModel::onToggleCount
                            )
                        },
                        {
                            Divider()
                            SwitchItem(
                                title = stringResource(R.string.show_buttons),
                                checked = showButtons,
                                onCheckedChange =viewModel::onToggleButtons,
                            )
                        },
                        {
                            Divider()
                            SwitchItem(
                                title = stringResource(R.string.show_tabs_all_likes_etc),
                                checked = showTabs,
                                onCheckedChange =viewModel::onToggleTabs,
                            )
                        },
                    )
                )


            }
        ),

    )


}