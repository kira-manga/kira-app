package me.manga.kira.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.presentation.reader.ReaderIntent
import me.manga.kira.presentation.reader.ReaderState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.reader_next_chapter
import me.manga.kira.ui.generated.resources.reader_previous_chapter
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReaderPageScrubberTest {
    @Test
    fun singlePageChapterButtonsKeepEnablementAndDispatchOnlyEnabledPointerActions() =
        runComposeUiTest {
            val surface = render(onePageState(previous = false, next = false))
            for ((previous, next) in listOf(false to false, true to false, false to true, true to true)) {
                runOnIdle {
                    surface.state = onePageState(previous, next)
                    surface.intents.clear()
                }
                assertButtons(surface, previous, next)
                onNode(sliderMatcher).assertDoesNotExist()
                onNodeWithContentDescription(surface.previousLabel).performTouchInput { click() }
                onNodeWithContentDescription(surface.nextLabel).performTouchInput { click() }
                val expected =
                    buildList {
                        if (previous) add(ReaderIntent.OnPrevChapter)
                        if (next) add(ReaderIntent.OnNextChapter)
                    }
                runOnIdle { assertEquals(expected, surface.intents, "previous=$previous, next=$next") }
            }
        }

    @Test
    fun activeOnePageChapterKeepsControlsInsideAppendedFeedWhileEmptyAndLoadingStayAbsent() =
        runComposeUiTest {
            val surface = render(ReaderState())
            for (loading in listOf(false, true)) {
                runOnIdle { surface.state = ReaderState(isLoading = loading) }
                onNodeWithContentDescription(surface.previousLabel).assertDoesNotExist()
                onNodeWithContentDescription(surface.nextLabel).assertDoesNotExist()
                onNode(sliderMatcher).assertDoesNotExist()
            }
            runOnIdle { surface.state = appendedSinglePageState() }
            assertButtons(surface, previous = true, next = true)
            onNode(sliderMatcher).assertDoesNotExist()
            onNodeWithContentDescription(surface.nextLabel).performTouchInput { click() }
            runOnIdle { assertEquals(listOf<ReaderIntent>(ReaderIntent.OnNextChapter), surface.intents) }
        }

    @Test
    fun multiPageSliderSeeksAbsoluteFeedIndexAndRebindsAcrossSinglePageChapter() =
        runComposeUiTest {
            val multiPage = feedState(listOf("a", "b", "c"), listOf("a", "a", "b", "b", "b"), index = 3)
            val surface = render(multiPage)
            assertSlider(current = 1f)
            onNode(sliderMatcher).performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
            runOnIdle {
                assertEquals(
                    listOf<ReaderIntent>(ReaderIntent.OnPageChanged(EXPECTED_ABSOLUTE_SEEK_INDEX)),
                    surface.intents,
                )
                surface.state = surface.state.copy(currentPageIndex = EXPECTED_ABSOLUTE_SEEK_INDEX)
            }
            assertSlider(current = 2f)
            onNode(sliderMatcher).performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
            runOnIdle {
                assertEquals(
                    listOf<ReaderIntent>(ReaderIntent.OnPageChanged(EXPECTED_ABSOLUTE_SEEK_INDEX)),
                    surface.intents,
                )
            }
            runOnIdle { surface.state = appendedSinglePageState() }
            assertButtons(surface, previous = true, next = true)
            onNode(sliderMatcher).assertDoesNotExist()
            runOnIdle { surface.state = multiPage }
            assertSlider(current = 1f)
        }

    private fun ComposeUiTest.render(initial: ReaderState): ScrubberSurface {
        val surface = ScrubberSurface(initial)
        setContent {
            KiraTheme(darkTheme = false) {
                surface.previousLabel = stringResource(Res.string.reader_previous_chapter)
                surface.nextLabel = stringResource(Res.string.reader_next_chapter)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(Modifier.width(320.dp)) {
                        ReaderPageScrubber(state = surface.state, onIntent = surface.intents::add)
                    }
                }
            }
        }
        return surface
    }

    private fun ComposeUiTest.assertButtons(
        surface: ScrubberSurface,
        previous: Boolean,
        next: Boolean,
    ) {
        val previousButton = onNodeWithContentDescription(surface.previousLabel).assertIsDisplayed()
        val nextButton = onNodeWithContentDescription(surface.nextLabel).assertIsDisplayed()
        if (previous) previousButton.assertIsEnabled() else previousButton.assertIsNotEnabled()
        if (next) nextButton.assertIsEnabled() else nextButton.assertIsNotEnabled()
        val previousBounds = previousButton.fetchSemanticsNode().boundsInRoot
        val nextBounds = nextButton.fetchSemanticsNode().boundsInRoot
        assertTrue(nextBounds.right <= previousBounds.left, "Next stays left of previous without overlap")
    }

    private fun ComposeUiTest.assertSlider(current: Float) {
        val slider = onNode(sliderMatcher).assertIsDisplayed().fetchSemanticsNode()
        assertEquals(
            ProgressBarRangeInfo(current, 0f..2f, steps = 1),
            slider.config[SemanticsProperties.ProgressBarRangeInfo],
        )
    }

    private fun onePageState(
        previous: Boolean,
        next: Boolean,
    ): ReaderState {
        val chapters =
            buildList {
                if (previous) add("before")
                add("current")
                if (next) add("after")
            }
        return feedState(chapters, listOf("current"), index = 0)
    }

    private fun appendedSinglePageState(): ReaderState =
        feedState(
            listOf("a", "b", "c"),
            listOf("a", "a", "b", "c", "c"),
            index = 2,
        )

    private fun feedState(
        chapterNames: List<String>,
        owners: List<String>,
        index: Int,
    ): ReaderState {
        val chapters =
            chapterNames.map { name ->
                Chapter(name, name, "chapter-$name", date = null, isDownloaded = false, isBookmarked = false)
            }
        return ReaderState(
            chapter = chapters.first { it.url == "chapter-${owners.first()}" },
            chapters = chapters,
            pages = owners.mapIndexed { i, _ -> Page("page-$i", emptyMap()) },
            pageChapters = owners.map { "chapter-$it" },
            currentPageIndex = index,
        )
    }

    private class ScrubberSurface(
        initial: ReaderState,
    ) {
        var state by mutableStateOf(initial)
        val intents = mutableListOf<ReaderIntent>()
        var previousLabel = ""
        var nextLabel = ""
    }

    private companion object {
        const val EXPECTED_ABSOLUTE_SEEK_INDEX = 4
        val sliderMatcher = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
    }
}
