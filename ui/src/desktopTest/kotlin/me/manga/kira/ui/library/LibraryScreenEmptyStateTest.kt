package me.manga.kira.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.presentation.library.LibraryIntent
import me.manga.kira.presentation.library.LibraryState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.contentDescription_search
import me.manga.kira.ui.generated.resources.content_description_close_search
import me.manga.kira.ui.generated.resources.dropdown_button_refresh
import me.manga.kira.ui.generated.resources.library_empty_desc_format
import me.manga.kira.ui.generated.resources.library_empty_message_format
import me.manga.kira.ui.generated.resources.library_more_options
import me.manga.kira.ui.generated.resources.library_no_matching_items
import me.manga.kira.ui.generated.resources.library_tab_likes
import me.manga.kira.ui.generated.resources.library_tab_watching_now
import me.manga.kira.ui.generated.resources.no_results_found
import me.manga.kira.ui.generated.resources.title_library
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class LibraryScreenEmptyStateTest {
    @Test
    fun trulyEmptyLibraryUsesLibraryCopyEvenWithSearchAndCategory() =
        runComposeUiTest {
            var labels = LibraryScreenLabels()
            val state =
                mutableStateOf(
                    LibraryState(isLoading = false, searchQuery = QUERY, category = LibraryCategory.LIKED),
                )
            showScreen(state) { labels = libraryScreenLabels() }
            awaitIdle()
            onNodeWithText(labels.empty.library).assertIsDisplayed()
            onNodeWithContentDescription(labels.empty.descriptions.first()).assertIsDisplayed()
            onNodeWithText(labels.empty.search).assertDoesNotExist()
            onNodeWithText(labels.empty.filters).assertDoesNotExist()
            labels.empty.descriptions
                .drop(1)
                .forEach { onNodeWithContentDescription(it).assertDoesNotExist() }
        }

    @Test
    fun filtersAndCategoryShowNoMatchesAndOverflowStillRefreshes() =
        runComposeUiTest {
            var labels = LibraryScreenLabels()
            val intents = mutableListOf<LibraryIntent>()
            val categoryEmpty =
                LibraryState(isLoading = false, hasLibraryItems = true, category = LibraryCategory.LIKED)
            val state = mutableStateOf(categoryEmpty)
            showScreen(state, { intents += it }) { labels = libraryScreenLabels() }
            awaitIdle()
            assertNoMatches(labels.empty.filters, labels.empty)
            runOnIdle {
                state.value = categoryEmpty.copy(category = LibraryCategory.NAN, filter = LibraryFilter.UNREAD)
            }
            awaitIdle()
            assertNoMatches(labels.empty.filters, labels.empty)
            assertCenteredInEmptyViewport(labels.empty.filters)
            onNodeWithContentDescription(labels.moreOptions).performClick()
            awaitIdle()
            onNodeWithText(labels.refreshAction).assertIsDisplayed().performClick()
            awaitIdle()
            assertEquals(listOf(LibraryIntent.OnEnter, LibraryIntent.OnRefresh), intents)
            assertEquals(LibraryFilter.UNREAD, state.value.filter)
        }

    @Test
    fun realSearchNoResultsSupportsPullToRefreshWithoutClosingSearch() =
        runComposeUiTest {
            var labels = LibraryScreenLabels()
            val intents = mutableListOf<LibraryIntent>()
            val state = showSearchableLibrary(intents) { labels = libraryScreenLabels() }
            awaitIdle()
            onNodeWithText(
                state.value.items
                    .single()
                    .manga.title,
            ).assertIsDisplayed()
            onNodeWithContentDescription(labels.searchAction).performClick()
            awaitIdle()
            onNode(hasSetTextAction()).performClick().performTextInput(QUERY)
            awaitIdle()
            onNodeWithContentDescription(labels.moreOptions).assertDoesNotExist()
            assertNoMatches(labels.empty.search, labels.empty)
            assertCenteredInEmptyViewport(labels.empty.search)
            emptyViewport().performTouchInput { swipeDown() }
            awaitIdle()
            assertEquals(1, intents.count { it == LibraryIntent.OnRefresh })
            assertEquals(QUERY, state.value.searchQuery)
            onNodeWithText(QUERY).assertIsDisplayed()
            onNodeWithContentDescription(labels.closeSearch).assertIsDisplayed()
        }

    private fun ComposeUiTest.showSearchableLibrary(
        intents: MutableList<LibraryIntent>,
        readLabels: @Composable () -> Unit,
    ): MutableState<LibraryState> {
        val state =
            mutableStateOf(
                LibraryState(isLoading = false, hasLibraryItems = true, items = listOf(savedManga())),
            )
        showScreen(
            state = state,
            onIntent = { intent ->
                intents += intent
                if (intent is LibraryIntent.OnSearchQueryChange) {
                    state.value = state.value.copy(searchQuery = intent.query, items = emptyList())
                }
            },
            readLabels = readLabels,
        )
        return state
    }

    private fun ComposeUiTest.showScreen(
        state: State<LibraryState>,
        onIntent: (LibraryIntent) -> Unit = {},
        readLabels: @Composable () -> Unit,
    ) {
        setContent {
            KiraTheme(darkTheme = false) {
                readLabels()
                Box(Modifier.size(SCREEN_WIDTH_DP.dp, SCREEN_HEIGHT_DP.dp).testTag(SCREEN_TAG)) {
                    LibraryScreenContent(
                        state = state.value,
                        effects = emptyFlow(),
                        onIntent = onIntent,
                        onNavigateToDetails = {},
                        onNavigateToDownloads = {},
                        onNavigateToBackupExport = {},
                        coverModel = { null },
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.assertNoMatches(
        expected: String,
        labels: EmptyCopy,
    ) {
        onNodeWithText(expected).assertIsDisplayed()
        listOf(labels.library, labels.search, labels.filters).filter { it != expected }.forEach {
            onNodeWithText(it).assertDoesNotExist()
        }
        labels.descriptions.forEach { onNodeWithContentDescription(it).assertDoesNotExist() }
    }

    private fun ComposeUiTest.emptyViewport() =
        onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        )

    private fun ComposeUiTest.assertCenteredInEmptyViewport(message: String) {
        val screen = onNodeWithTag(SCREEN_TAG).fetchSemanticsNode().boundsInRoot
        val viewport = emptyViewport().fetchSemanticsNode().boundsInRoot
        val text = onNodeWithText(message).fetchSemanticsNode().boundsInRoot
        assertEquals(screen.bottom, viewport.bottom, absoluteTolerance = 1f)
        assertEquals(screen.width, viewport.width, absoluteTolerance = 1f)
        assertTrue(viewport.height > screen.height / 2)
        assertEquals(viewport.center.x, text.center.x, absoluteTolerance = 1f)
        assertEquals(viewport.center.y, text.center.y, absoluteTolerance = 1f)
    }

    private companion object {
        const val SCREEN_WIDTH_DP = 400
        const val SCREEN_HEIGHT_DP = 720
        const val SCREEN_TAG = "library-screen-under-test"
        const val QUERY = "unmatched-query"
    }
}

private data class EmptyCopy(
    val library: String = "",
    val search: String = "",
    val filters: String = "",
    val descriptions: List<String> = emptyList(),
)

private data class LibraryScreenLabels(
    val empty: EmptyCopy = EmptyCopy(),
    val searchAction: String = "",
    val closeSearch: String = "",
    val moreOptions: String = "",
    val refreshAction: String = "",
)

@Composable
private fun libraryScreenLabels(): LibraryScreenLabels {
    val libraryName = stringResource(Res.string.title_library)
    return LibraryScreenLabels(
        empty =
            EmptyCopy(
                library = stringResource(Res.string.library_empty_message_format, libraryName),
                search = stringResource(Res.string.no_results_found),
                filters = stringResource(Res.string.library_no_matching_items),
                descriptions =
                    listOf(
                        libraryName,
                        stringResource(Res.string.library_tab_likes),
                        stringResource(Res.string.library_tab_watching_now),
                    ).map { stringResource(Res.string.library_empty_desc_format, it) },
            ),
        searchAction = stringResource(Res.string.contentDescription_search),
        closeSearch = stringResource(Res.string.content_description_close_search),
        moreOptions = stringResource(Res.string.library_more_options),
        refreshAction = stringResource(Res.string.dropdown_button_refresh),
    )
}

private fun savedManga(): LibraryManga =
    LibraryManga(
        manga =
            Manga(
                api = "fixture",
                language = "en",
                title = "Saved manga",
                url = "https://example.invalid/manga",
                coverUrl = "",
                rating = null,
                genres = emptyList(),
            ),
        addedAt = Instant.fromEpochMilliseconds(0),
        unreadCount = 0,
        hasDownloads = false,
        totalChapters = 1,
        lastReadAt = null,
        lastOpenedAt = Instant.fromEpochMilliseconds(0),
        bookmarkedCount = 0,
        downloadedCount = 0,
        isLiked = false,
        isWatchingNow = false,
    )
