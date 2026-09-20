package me.manga.kira.ui.details

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.presentation.details.DetailsIntent
import me.manga.kira.presentation.details.DetailsState
import me.manga.kira.ui.theme.KiraTheme
import kotlin.test.assertEquals

internal class DetailsBottomControlsFixture {
    val state = mutableStateOf(bottomControlsState())
    val finalChapter = state.value.displayChapters.last()
    val finalChapterIndex = HEADER_ITEM_COUNT + state.value.displayChapters.lastIndex
    val intents = mutableListOf<DetailsIntent>()
    var readerNavigationCount = 0
        private set

    fun recordIntent(intent: DetailsIntent) {
        intents += intent
        // Supply state only after the production UI emits the matching selection callback.
        when (intent) {
            is DetailsIntent.OnChapterLongClick ->
                state.value = state.value.copy(selectedChapterUrls = setOf(intent.chapter.url))
            DetailsIntent.OnSelectionClear ->
                state.value = state.value.copy(selectedChapterUrls = emptySet())
            else -> Unit
        }
    }

    fun recordReaderNavigation() {
        readerNavigationCount++
    }

    fun assertOnlyIntentAndReset(expected: DetailsIntent) {
        assertEquals(listOf(expected), intents)
        assertEquals(0, readerNavigationCount)
        intents.clear()
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.showBottomControls(
    fixture: DetailsBottomControlsFixture,
    direction: LayoutDirection,
) {
    setContent {
        KiraTheme(darkTheme = false) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                DetailsScreenContent(
                    state = fixture.state.value,
                    effects = emptyFlow(),
                    onIntent = fixture::recordIntent,
                    onNavigateBack = {},
                    onNavigateToReader = { _, _ -> fixture.recordReaderNavigation() },
                    onNavigateToDownloads = {},
                    onNavigateToBackupExport = {},
                    onOpenInWebView = { _, _ -> },
                )
            }
        }
    }
}

private fun bottomControlsState(): DetailsState {
    val manga = bottomControlsManga()
    return DetailsState(
        manga = manga,
        details =
            MangaDetails(
                api = manga.api,
                language = manga.language,
                title = manga.title,
                url = manga.url,
                coverUrl = manga.coverUrl,
                description = "",
                author = "",
                rating = "",
                status = "",
                genres = emptyList(),
                chapters = bottomControlsChapters(),
            ),
        isInLibrary = true,
        savedOwner = SavedWorkIdentity(1L, WorkLocator(manga.api, manga.url)),
        sortAscending = false,
    )
}

private fun bottomControlsManga(): Manga =
    Manga(
        api = "fixture",
        language = "en",
        title = "Bottom controls fixture",
        url = "manga/bottom-controls",
        coverUrl = "",
        rating = null,
        genres = emptyList(),
    )

private fun bottomControlsChapters(): List<Chapter> =
    (CHAPTER_COUNT downTo 1).map { number ->
        Chapter(
            number = number.toString(),
            name = "",
            url = "c/$number",
            date = null,
            isDownloaded = number == 1,
            isBookmarked = false,
            isRead = false,
        )
    }

private const val CHAPTER_COUNT = 12
private const val HEADER_ITEM_COUNT = 2
