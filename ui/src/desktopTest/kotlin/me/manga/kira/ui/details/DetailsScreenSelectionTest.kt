package me.manga.kira.ui.details

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.presentation.details.ChapterDownloadProgress
import me.manga.kira.presentation.details.DetailsIntent
import me.manga.kira.presentation.details.DetailsState
import me.manga.kira.ui.theme.KiraTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
                showDetails(state, intents)
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

    @Test
    fun liveCompletionExposesDeleteWhileActiveSavedChapterKeepsCancel() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            runComposeUiTest {
                val loaded = selectionState()
                val state = mutableStateOf(loaded)
                val intents = mutableListOf<DetailsIntent>()
                showDetails(state, intents)
                assertCompletedSelection(state, loaded, intents)
                assertActiveDownloadPriority(state, loaded, intents)
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun downloadSizesRelocalizeWithoutChangingDetailsState() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            runComposeUiTest {
                val mib = 1024L * 1024
                val loaded = selectionState().copy(
                    chapterDownloads = mapOf(
                        "c/2" to ChapterDownloadProgress(DownloadState.SUCCESS, progress = 100, sizeBytes = 12 * mib),
                        "c/1" to ChapterDownloadProgress(DownloadState.SUCCESS, progress = 100, sizeBytes = mib),
                    ),
                )
                val state = mutableStateOf(loaded)
                val locale = mutableStateOf(Locale.US)
                showDetails(state, mutableListOf(), locale)
                assertDownloadSizes("12.00 MB", "13.00 MB • 2 downloaded")
                runOnIdle {
                    Locale.setDefault(Locale.FRENCH)
                    locale.value = Locale.FRENCH
                }
                assertDownloadSizes("12,00 Mo", "13,00 Mo • 2 téléchargés")
                assertSame(loaded, state.value)
                assertEquals(12 * mib, state.value.chapterSizeBytes("c/2"))
                assertEquals(13 * mib, state.value.totalDownloadedSizeBytes)
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private fun ComposeUiTest.showDetails(
        state: State<DetailsState>,
        intents: MutableList<DetailsIntent>,
        locale: State<Locale>? = null,
    ) {
        setContent {
            // Mirror the Desktop root: a static provider invalidates resource reads throughout
            // the subtree when the JVM locale changes, without replacing the Details state.
            CompositionLocalProvider(LocalDetailsTestLocale provides (locale?.value ?: Locale.getDefault())) {
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
        }
    }

    private suspend fun ComposeUiTest.assertDownloadSizes(chapterLabel: String, totalLabel: String) {
        awaitIdle()
        onNode(hasScrollToIndexAction()).performScrollToIndex(FIRST_CHAPTER_ITEM_INDEX - 1)
        awaitIdle()
        onNodeWithText(chapterLabel).assertIsDisplayed()
        onNodeWithText(totalLabel).assertIsDisplayed()
    }

    private suspend fun ComposeUiTest.assertCompletedSelection(
        state: MutableState<DetailsState>,
        loaded: DetailsState,
        intents: MutableList<DetailsIntent>,
    ) {
        val chapter = requireNotNull(loaded.details).chapters.first()
        runOnIdle {
            state.value =
                loaded.copy(
                    selectedChapterUrls = setOf(chapter.url),
                    chapterDownloads =
                        mapOf(
                            chapter.url to ChapterDownloadProgress(DownloadState.SUCCESS, progress = 100),
                        ),
                )
        }
        awaitIdle()
        onNode(hasScrollToIndexAction()).performScrollToIndex(FIRST_CHAPTER_ITEM_INDEX)
        awaitIdle()
        onNodeWithContentDescription(DOWNLOADED_LABEL).assertIsDisplayed().assertIsNotEnabled()
        onNodeWithContentDescription(DELETE_DOWNLOADED_LABEL).assertIsDisplayed().performClick()
        awaitIdle()
        assertEquals(listOf<DetailsIntent>(DetailsIntent.OnDeleteSelectedDownloads), intents)
    }

    private suspend fun ComposeUiTest.assertActiveDownloadPriority(
        state: MutableState<DetailsState>,
        loaded: DetailsState,
        intents: MutableList<DetailsIntent>,
    ) {
        val details = requireNotNull(loaded.details)
        val chapter = details.chapters.first().copy(isDownloaded = true)
        val saved = loaded.copy(details = details.copy(chapters = listOf(chapter)))
        listOf(
            DownloadState.QUEUED,
            DownloadState.RUNNING,
            DownloadState.COMPRESSING,
            DownloadState.DOWNLOADED,
        ).forEach { status ->
            runOnIdle {
                intents.clear()
                state.value =
                    saved.copy(
                        chapterDownloads = mapOf(chapter.url to ChapterDownloadProgress(status, progress = 42)),
                    )
            }
            awaitIdle()
            onNode(hasScrollToIndexAction()).performScrollToIndex(FIRST_CHAPTER_ITEM_INDEX)
            awaitIdle()
            onNodeWithContentDescription(DOWNLOADED_LABEL).assertDoesNotExist()
            onNodeWithContentDescription(CANCEL_DOWNLOAD_LABEL).assertIsDisplayed().performClick()
            awaitIdle()
            assertEquals(listOf<DetailsIntent>(DetailsIntent.OnCancelChapterDownload(chapter)), intents)
        }
    }
}

private const val INCLUSIVE_ACTION_LABEL = "Mark this and below as read"
private const val RESUME_ACTION_LABEL = "Resume"
private const val DOWNLOADED_LABEL = "Downloaded"
private const val DELETE_DOWNLOADED_LABEL = "Delete downloaded chapters"
private const val CANCEL_DOWNLOAD_LABEL = "Cancel chapter download"
private const val FIRST_CHAPTER_ITEM_INDEX = 2

private val LocalDetailsTestLocale = staticCompositionLocalOf { Locale.getDefault() }

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
