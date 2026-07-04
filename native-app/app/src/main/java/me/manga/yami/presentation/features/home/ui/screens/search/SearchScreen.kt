package me.manga.yamiapk.presentation.features.home.ui.screens.search

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import me.manga.yamiapk.R
import me.manga.yamiapk.ad_mob.bannars.BannerAdView
import me.manga.yamiapk.ad_mob.util.ListEntryWithAd
import me.manga.yamiapk.ad_mob.util.ads_lists.interleaveAds
import me.manga.yamiapk.ad_mob.util.ads_lists.interleaveAdsCustom
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.presentation.common.componants.app_bars.SearchAppBar
import me.manga.yamiapk.presentation.common.componants.flow_chips.ChipsRow
import me.manga.yamiapk.presentation.common.screens.ErrorScreen
import me.manga.yamiapk.presentation.common.screens.LoadingScreen
import me.manga.yamiapk.presentation.features.home.data.ApiTitle
import me.manga.yamiapk.presentation.features.home.ui.components.MangaSearchItems
import me.manga.yamiapk.presentation.features.home.ui.components.SearchBottomSheet

@Composable
fun SearchScreen(
    searchResultsState: State<List<MangaItem>>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenInWebView: () -> Unit,
    onHelp: () -> Unit,
    savedTitles: Set<ApiTitle>,
    sortTypes: Set<String>,
    onSortClick: (String, String, String) -> Unit,             // ← new callback
    genres: Set<String>,
    onMangaClick: (String, String,String, Boolean) -> Unit,
    onGenreClicked: (String) -> Unit,
    selectedGenres: String,
    buildImageRequest:(Context, String, String) -> ImageRequest,
    multiSearchState: Map<String, State<List<MangaItem>>>,
    onMultiSearch:(query :String)-> Unit,
    onRepoChange:suspend (String) -> Unit


) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })

    var selectedTab by remember { mutableStateOf("Search") }
    val tabOptions = listOf(stringResource(R.string.search), stringResource(R.string.multi_search))
    LaunchedEffect(selectedTab) {
        val index = tabOptions.indexOf(selectedTab)
        if (index != -1) pagerState.scrollToPage(index)
    }
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = tabOptions.getOrNull(pagerState.currentPage) ?: "Search"
    }

    // Local state for the text field inside SearchScreen
    var localQuery by remember { mutableStateOf(searchQuery) }
    val keyboardController = LocalSoftwareKeyboardController.current
    var ShowSetting by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            SearchAppBar(
                query = localQuery,

                onQueryChange = {localQuery = it},
                onToggleSearch = onToggleSearch,
                onSearch ={
                    if (pagerState.currentPage ==1) onMultiSearch(localQuery)
                    if (pagerState.currentPage==0 )onSearchChange(localQuery)

                    try {
                        keyboardController?.hide()
                    } catch (_: UnsupportedOperationException) { /* no-op */ }

                },
                actions = {
                    IconButton(onClick = {
                        ShowSetting = true
                    }) {
                        Icon(Icons.Default.Settings,
                            contentDescription = stringResource(R.string.title_settings)
                        )
                    } },
            )

        }
    ) { paddingValues ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {

                ChipsRow(
                    items = tabOptions,
                    selectedItem = selectedTab,
                    onItemSelected = { selectedTab = it }
                )
            HorizontalPager(
                state = pagerState,
                pageSpacing = 16.dp
            )
            { page ->
                if (page == 0) {
                    when (searchResultsState) {
                        is State.Loading -> {
                            LoadingScreen()

                        }

                        is State.Error -> {
                            ErrorScreen(

                                message = stringResource(
                                    R.string.failed_to_load,
                                    searchResultsState.message
                                ),
                                onRetry = { onSearchChange(localQuery) },
                                onOpenInBrowser = { onOpenInWebView() },
                                onHelp = { onHelp() }
                            )

                        }

                        is State.Success -> {

                            MangaSearchItems(
                                items = searchResultsState.data,
                                onMangaSearchClick = onMangaClick,
                                savedTitles = savedTitles,
                                buildImageRequest = buildImageRequest
                            )
                        }
                    }
                }else{
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                    ) {
                        MultiRepoResults(
                            multiSearchState = multiSearchState,
                            savedTitles= savedTitles,
                            onMangaClick     = onMangaClick,
                            buildImageRequest= buildImageRequest,
                            onRepoChange = onRepoChange
                        )
                    }
                }
            }}


        }
    }

    if (ShowSetting){
        // Sample state for “Type” checkboxes
        val noSortTypesLabel = stringResource(R.string.no_sort_types)
        // Sample state for “Sort” dropdown
        var selectedSort by remember { mutableStateOf(if (!sortTypes.isNullOrEmpty())sortTypes.first() else noSortTypesLabel) }

        SearchBottomSheet(
            showSheet = ShowSetting,
            onDismiss = { ShowSetting = false },


            // “Sort” dropdown
            selectedSort = selectedSort,
            onSortSelected = {sortTypes , gen ->
                selectedSort = sortTypes
                onSortClick(sortTypes, localQuery,gen)
            },

            genres = genres,
            onGenreClicked = onGenreClicked,
            allSortOptions = sortTypes,
            selectedGenre = selectedGenres

        )
    }


    }
@Preview(showBackground = true)
@Composable
fun PreviewSearchScreen() {
    val dummyMangaList = List(5) {
        MangaItem(
            url = "url$it",
            title = "Manga Title $it",
            imageUrl = "",
            api = "api",
            language = "en",
            genres = emptyList(),
            rating = 0,
            chapters = emptyList()
        )
    }

    SearchScreen(
        searchResultsState = State.Success(dummyMangaList),
        searchQuery = "",
        onSearchChange = {},
        onToggleSearch = {},
        onOpenInWebView = {},
        onHelp = {},
        savedTitles = emptySet(),
        sortTypes = setOf("Popular", "Newest"),
        onSortClick = { _, _, _ -> },
        genres = setOf("Action", "Drama"),
        onMangaClick = { _, _, _, _ -> },
        onGenreClicked = {},
        selectedGenres = "",
        buildImageRequest = { context, _, _ ->
            ImageRequest.Builder(context).data("").build()
        },
        multiSearchState = mapOf(
            "source1" to State.Success(dummyMangaList),
            "source2" to State.Loading
        ),
        onMultiSearch = {},
        onRepoChange = {}
    )
}
