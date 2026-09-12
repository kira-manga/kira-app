package me.manga.kira.ui.details

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.presentation.details.DetailsIntent
import me.manga.kira.presentation.details.DetailsState
import me.manga.kira.ui.theme.KiraTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DetailsScreenSelectionTest {
    @Test
    fun markThisAndBelowHasInclusiveLabelAndDispatchesOnlyForSingleSelection() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            runComposeUiTest {
                val loaded = selectionState()
                val state = mutableStateOf(loaded)
                val intents = mutableListOf<DetailsIntent>()
                setContent {
                    KiraTheme(darkTheme = false) {
                        DetailsScreenContent(
                            state = state.value,
                            effects = emptyFlow(),
                            onIntent = { intents += it },
                            onNavigateBack = {},
                            onNavigateToReader = { _, _ -> },
                            onNavigateToDownloads = {},
                            onNavigateToBackupExport = { _, _, _ -> },
                            onOpenInWebView = { _, _ -> },
                        )
                    }
                }
                awaitIdle()
                onNodeWithContentDescription(INCLUSIVE_ACTION_LABEL).assertDoesNotExist()
                onAllNodesWithContentDescription(RESUME_ACTION_LABEL).assertCountEquals(2)

                runOnIdle { state.value = loaded.copy(selectedChapterUrls = setOf("c/2")) }
                awaitIdle()
                onAllNodesWithContentDescription(RESUME_ACTION_LABEL).assertCountEquals(1)
                onNodeWithContentDescription(INCLUSIVE_ACTION_LABEL).assertIsDisplayed().performClick()
                awaitIdle()
                assertEquals(listOf<DetailsIntent>(DetailsIntent.OnMarkSelectedDownRead), intents)

                runOnIdle { state.value = loaded.copy(selectedChapterUrls = setOf("c/2", "c/1")) }
                awaitIdle()
                onNodeWithContentDescription(INCLUSIVE_ACTION_LABEL).assertDoesNotExist()
                onAllNodesWithContentDescription(RESUME_ACTION_LABEL).assertCountEquals(1)
                assertEquals(listOf<DetailsIntent>(DetailsIntent.OnMarkSelectedDownRead), intents)

                runOnIdle { state.value = loaded }
                awaitIdle()
                onNodeWithContentDescription(INCLUSIVE_ACTION_LABEL).assertDoesNotExist()
                onAllNodesWithContentDescription(RESUME_ACTION_LABEL).assertCountEquals(2)
                assertEquals(listOf<DetailsIntent>(DetailsIntent.OnMarkSelectedDownRead), intents)
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}

private const val INCLUSIVE_ACTION_LABEL = "Mark this and below as read"
private const val RESUME_ACTION_LABEL = "Resume"

private fun selectionState(): DetailsState {
    val manga =
        Manga(
            api = "fixture",
            language = "en",
            title = "Selection fixture",
            url = "manga/fixture",
            coverUrl = "",
            rating = null,
            genres = emptyList(),
        )
    val chapters =
        listOf("c/2", "c/1").map { url ->
            Chapter(
                number = url.substringAfterLast('/'),
                name = "",
                url = url,
                date = null,
                isDownloaded = false,
                isBookmarked = false,
            )
        }
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
                chapters = chapters,
            ),
        isInLibrary = true,
    )
}
