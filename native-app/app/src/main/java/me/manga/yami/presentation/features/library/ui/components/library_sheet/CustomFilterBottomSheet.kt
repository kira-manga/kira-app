package me.manga.yamiapk.presentation.features.library.ui.components.library_sheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.library.ui.viewmodel.LibraryViewModel

//// --- CustomFilterBottomSheet.kt --------------------
//@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
//@Composable
//fun CustomFilterBottomSheet(
//    showSheet: Boolean,
//    onDismiss: () -> Unit,
//
//    // now using enums and booleans…
//    selectedFilter: LibraryViewModel.FilterType,
//    onFilterSelected: (LibraryViewModel.FilterType) -> Unit,
//
//    selectedSort: LibraryViewModel.SortType,
//    onSortSelected: (LibraryViewModel.SortType) -> Unit,
//    isAscending: Boolean,
//    onSortDirectionChanged: (Boolean) -> Unit,
//
//    itemsPerRow: Int,
//    onItemsPerRowChange: (Int) -> Unit,
//
//    showDetails: Boolean,
//    onShowDetailsChange: (Boolean) -> Unit,
//    showSource: Boolean,
//    onShowSourceChange: (Boolean) -> Unit,
//    showCount: Boolean,
//    onShowCountChange: (Boolean) -> Unit
//) {
//    if (!showSheet) return
//
//    ModalBottomSheet(
//        onDismissRequest = onDismiss,
//        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
//        tonalElevation = 8.dp,
//        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
//        dragHandle = {},
//        sheetState       = rememberModalBottomSheetState()
//    ) {
//        Column(Modifier.padding(16.dp)) {
//            val tabs = listOf(
//                stringResource(R.string.library_bottom_sheet_tab_filter),
//                stringResource(R.string.library_bottom_sheet_tab_sort),
//                stringResource(R.string.library_bottom_sheet_tab_display)
//            )
//            var tab by rememberSaveable { mutableStateOf(0) }
//
//            TabRow(
//                selectedTabIndex = tab,
//                containerColor = Color.Transparent,
//                contentColor = MaterialTheme.colorScheme.primary,
//            ) {
//                tabs.forEachIndexed { i, t ->
//                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 16.sp, fontWeight = FontWeight.Bold) })
//                }
//            }
//
//            Spacer(Modifier.height(16.dp))
//
//            when (tab) {
//                0 -> {
//
//                    FilterChipsRow(
//                        selectedFilter = selectedFilter,
//                        onFilterSelected = onFilterSelected,
//                       filters = LibraryViewModel.FilterType.entries
//                    )
//
//
//
//                }
//                1 -> {
//                    SortOptionsSection(
//                        selectedSort           = selectedSort,
//                        isAscending            = isAscending,
//                        onSortSelected         = onSortSelected,
//                        onSortDirectionChanged = onSortDirectionChanged
//                    )
//                }
//                2 -> {
//                    DisplayOptionsSection(
//                        count             = itemsPerRow,
//                        onCountChange     = onItemsPerRowChange,
//                        showDetails       = showDetails,
//                        onShowDetailsChange = onShowDetailsChange,
//                        showSource        = showSource,
//                        onShowSourceChange  = onShowSourceChange,
//                        showCount         = showCount,
//                        onShowCountChange   = onShowCountChange
//                    )
//                }
//            }
//        }
//    }
//}




//
//@Composable
//fun s (
//    showSheet: Boolean,
//        onDismiss: () -> Unit,
//
//    // now using enums and booleans…
//        selectedFilter: LibraryViewModel.FilterType,
//        onFilterSelected: (LibraryViewModel.FilterType) -> Unit,
//
//        selectedSort: LibraryViewModel.SortType,
//        onSortSelected: (LibraryViewModel.SortType) -> Unit,
//        isAscending: Boolean,
//        onSortDirectionChanged: (Boolean) -> Unit,
//
//        itemsPerRow: Int,
//        onItemsPerRowChange: (Int) -> Unit,
//
//        showDetails: Boolean,
//        onShowDetailsChange: (Boolean) -> Unit,
//        showSource: Boolean,
//        onShowSourceChange: (Boolean) -> Unit,
//        showCount: Boolean,
//        onShowCountChange: (Boolean) -> Unit){
//
//
//    CustomFilterBottomSheet(
//        showSheet = showSheet,
//        onDismiss = { /*...*/ },
//
//        tabs = listOf(
//            stringResource(R.string.library_bottom_sheet_tab_filter),
//            stringResource(R.string.library_bottom_sheet_tab_sort),
//            stringResource(R.string.library_bottom_sheet_tab_display)
//        ),
//        initialTabIndex = 0,
//        pageContents = listOf(
//            {   FilterChipsRow(
//                selectedFilter = selectedFilter,
//                onFilterSelected = onFilterSelected,
//                filters = LibraryViewModel.FilterType.entries
//            )
//                 },
//            {   SortOptionsSection(
//                selectedSort           = selectedSort,
//                isAscending            = isAscending,
//                onSortSelected         = onSortSelected,
//                onSortDirectionChanged = onSortDirectionChanged
//            ) },
//            {    DisplayOptionsSection(
//                count             = itemsPerRow,
//                onCountChange     = onItemsPerRowChange,
//                showDetails       = showDetails,
//                onShowDetailsChange = onShowDetailsChange,
//                showSource        = showSource,
//                onShowSourceChange  = onShowSourceChange,
//                showCount         = showCount,
//                onShowCountChange   = onShowCountChange
//            ) }
//        ),
//
//        // your existing filter/sort/display args
//        selectedFilter = selectedFilter,
//        onFilterSelected = onFilterSelected,
//        selectedSort = selectedSort,
//        onSortSelected = onSortSelected,
//        isAscending = isAscending,
//        onSortDirectionChanged = onSortDirectionChanged,
//        itemsPerRow = itemsPerRow,
//        onItemsPerRowChange = onItemsPerRowChange,
//        showDetails = showDetails,
//        onShowDetailsChange = onShowDetailsChange,
//        showSource = showSource,
//        onShowSourceChange = onShowSourceChange,
//        showCount = showCount,
//        onShowCountChange = onShowCountChange
//    )
//
//}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomFilterBottomSheet(
    showSheet: Boolean,
    onDismiss: () -> Unit,

    // Dynamic tabs and content
    tabs: List<String>,
    initialTabIndex: Int = 0,
    pageContents: List<@Composable () -> Unit>,

) {
    if (!showSheet) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 8.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = {},
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(Modifier.padding(16.dp)) {
            var tabIndex by rememberSaveable { mutableStateOf(initialTabIndex) }

            TabRow(
                selectedTabIndex = tabIndex,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = { Text(text = title, fontSize = 16.sp) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Display the corresponding page content
            pageContents.getOrNull(tabIndex)?.invoke()
        }
    }
}
