package me.manga.kira.ui.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.presentation.home.HomeIntent
import me.manga.kira.presentation.home.HomeState
import me.manga.kira.ui.theme.KiraTheme

internal class HomePaginationFixture(
    grid: Boolean,
    itemCount: Int = 1,
) {
    val state =
        mutableStateOf(
            HomeState(
                sourceTabs = listOf(sourceTab("alpha"), sourceTab("beta")),
                isGridView = grid,
                feed = (1..itemCount).map { feedItem("alpha", it) },
            ),
        )
    val requestedSources = mutableListOf<String>()

    fun recordIntent(intent: HomeIntent) {
        if (intent == HomeIntent.OnEndReached) {
            requestedSources += state.value.activeTab!!.api
            // Model the VM accepting a request; keep it suspended until the test supplies a result.
            state.value = state.value.copy(isLoadingNextPage = true)
        }
    }

    fun appendPage() {
        val before = state.value
        state.value =
            before.copy(
                feed = before.feed + feedItem(before.activeTab!!.api, before.page + 1),
                page = before.page + 1,
                isLoadingNextPage = false,
            )
    }

    fun switchSource() {
        state.value =
            state.value.copy(
                activeTabIndex = 1,
                page = 1,
                feed = listOf(feedItem("beta", 1)),
                isLoadingNextPage = false,
            )
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.showHomePagination(fixture: HomePaginationFixture) {
    setContent {
        KiraTheme(darkTheme = false) {
            HomeScreenContent(
                state = fixture.state.value,
                effects = emptyFlow(),
                onIntent = fixture::recordIntent,
                onNavigateToDetails = {},
                onNavigateToReader = {},
                onOpenWebView = { _, _ -> },
                onNavigateToSources = {},
                onHelp = {},
            )
        }
    }
}

private fun sourceTab(api: String) =
    SourceTab(
        api = api,
        language = "en",
        iconKey = null,
        siteState = SiteState.WORKING,
        displayName = api,
    )

private fun feedItem(
    api: String,
    page: Int,
) = HomeFeedItem(
    api = api,
    language = "en",
    title = "Manga $page",
    url = "https://example.invalid/$api/$page",
    coverUrl = "",
    rating = null,
    genres = emptyList(),
    recentChapters = emptyList(),
)
