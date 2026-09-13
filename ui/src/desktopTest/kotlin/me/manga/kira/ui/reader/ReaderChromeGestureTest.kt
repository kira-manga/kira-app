package me.manga.kira.ui.reader

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.presentation.reader.ReaderIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val AUTO_HIDE_MS = 3_000L

@OptIn(ExperimentalTestApi::class)
class ReaderChromeGestureTest {
    @Test
    fun loadedSingleTapsToggleExactlyOnceInEveryModeBeforeAndAfterRealAutoHide() =
        runReaderChromeTest {
            for (mode in ReadingMode.entries) {
                reset(loadedReaderState(mode, visible = true))
                awaitPage()
                assertChrome(visible = true, toggles = 0)
                tapChrome(visible = false, toggles = 1)
                tapChrome(visible = true, toggles = 2)
                assertAutoHide(previousToggles = 2)
                tapChrome(visible = true, toggles = 4)
                tapChrome(visible = false, toggles = 5)
            }
        }

    @Test
    fun swipesAdvanceEveryLayoutWithoutTogglesInBothAmbientDirections() =
        runReaderChromeTest {
            for (mode in ReadingMode.entries) {
                for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                    reset(loadedReaderState(mode), direction)
                    awaitPage()
                    assertForwardSwipe()
                }
            }
        }

    @Test
    fun doubleTapZoomResetPinchAndZoomedPanMovePixelsWithoutTogglingChrome() =
        runReaderChromeTest {
            for (mode in ReadingMode.entries) {
                reset(loadedReaderState(mode))
                awaitPage()
                assertZoomAndPan()
            }
        }

    @Test
    fun toolbarPointerActionsStayIndependentOfPageTaps() =
        runReaderChromeTest {
            reset(loadedReaderState(ReadingMode.WEBTOON, visible = true))
            awaitPage()
            test.runOnIdle { intents.clear() }
            for (label in listOf(backLabel, bookmarkLabel, shareLabel)) {
                test.onNodeWithContentDescription(label).performTouchInput { click() }
                advance(doubleTapTimeout + READER_SETTLE_MS)
                assertChrome(visible = true, toggles = 0)
            }
            test.onNodeWithContentDescription(settingsLabel).performTouchInput { click() }
            advance(CHROME_ANIMATION_MS)
            test.onNodeWithText(modeDialogLabel).assertIsDisplayed()
            test.onNodeWithText(revertLabel).performTouchInput { click() }
            advance(doubleTapTimeout + CHROME_ANIMATION_MS)
            test.onNodeWithText(modeDialogLabel).assertDoesNotExist()
            assertChrome(visible = true, toggles = 0)
            test.runOnIdle {
                assertEquals(
                    listOf(ReaderIntent.OnBackClick, ReaderIntent.OnToggleBookmark, ReaderIntent.OnShareCurrentPage),
                    intents,
                )
            }
        }

    @Test
    fun noPageFallbackAndControlsSurviveLoadedEmptyTransitionsWithoutCompetingDetectors() =
        runReaderChromeTest {
            for (empty in emptyReaderStates()) {
                reset(empty)
                tapChrome(visible = true, toggles = 1)
                assertNoPageControls()
                tapChrome(visible = false, toggles = 2)
                // Deliberately keep the same composition identity as the fallback is removed/inserted.
                replace(loadedReaderState(ReadingMode.VERTICAL))
                awaitPage()
                tapChrome(visible = true, toggles = 3)
                replace(empty)
                advance(CHROME_ANIMATION_MS)
                tapChrome(visible = true, toggles = 4)
            }
        }

    @Test
    fun semanticRevealIsLocalizedNonmergingIdempotentAndRearmedAfterAutoHide() =
        runReaderChromeTest {
            val states = ReadingMode.entries.map { loadedReaderState(it) } + emptyReaderStates()
            for (initial in states) {
                reset(initial)
                if (initial.hasPages) awaitPage()
                assertSemanticReveal()
            }
        }

    private fun ReaderChromeTestFixture.assertNoPageControls() {
        test.onNodeWithContentDescription(backLabel).performTouchInput { click() }
        advance(doubleTapTimeout + READER_SETTLE_MS)
        assertTrue(ReaderIntent.OnBackClick in intents)
        val retry = test.onNodeWithText(retryLabel)
        if (state.isInitialLoading) {
            retry.assertDoesNotExist()
        } else {
            retry.assertIsDisplayed().performTouchInput { click() }
            advance(doubleTapTimeout + READER_SETTLE_MS)
            assertTrue(ReaderIntent.OnRetry in intents)
        }
        assertChrome(visible = true, toggles = 1)
    }

    private fun ReaderChromeTestFixture.assertSemanticReveal() {
        val node = revealNode().assertIsDisplayed().fetchSemanticsNode()
        assertFalse(node.config.isMergingSemanticsOfDescendants, "Do not swallow child controls")
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
        assertEquals(revealLabel, node.config[SemanticsActions.OnClick].label)
        if (!state.hasPages && !state.isInitialLoading) {
            test.onNodeWithText(retryLabel).performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertTrue(ReaderIntent.OnRetry in intents, "Hidden reveal must preserve child Retry semantics")
            assertChrome(visible = false, toggles = 0)
        }
        val retainedAction = requireNotNull(node.config[SemanticsActions.OnClick].action)
        assertUnusedRetainedActionCannotHide(retainedAction)
        revealNode().performSemanticsAction(SemanticsActions.OnClick) { action ->
            // Both calls occur before recomposition acknowledges visibility: exercise the pending latch.
            assertTrue(action())
            assertTrue(action())
        }
        advance(CHROME_ANIMATION_MS)
        assertChrome(visible = true, toggles = 1)
        revealNode().assertDoesNotExist()
        test.runOnIdle { assertTrue(retainedAction()) }
        assertChrome(visible = true, toggles = 1)
        assertSemanticRearm()
    }

    private fun ReaderChromeTestFixture.assertUnusedRetainedActionCannotHide(action: () -> Boolean) {
        // A used action's pending latch could mask a missing latest-visibility guard.
        replace(state.copy(isUiVisible = true))
        advance(CHROME_ANIMATION_MS)
        revealNode().assertDoesNotExist()
        test.runOnIdle { assertTrue(action()) }
        assertChrome(visible = true, toggles = 0)
        replace(state.copy(isUiVisible = false))
        advance(CHROME_ANIMATION_MS)
        assertChrome(visible = false, toggles = 0)
    }

    private fun ReaderChromeTestFixture.assertSemanticRearm() {
        assertAutoHide(previousToggles = 1)
        revealNode().performSemanticsAction(SemanticsActions.OnClick) { action -> assertTrue(action()) }
        advance(CHROME_ANIMATION_MS)
        assertChrome(visible = true, toggles = 3)
        revealNode().assertDoesNotExist()
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ReaderChromeTestFixture.revealNode() =
    test.onNodeWithContentDescription(
        revealLabel,
        useUnmergedTree = true,
    )

@OptIn(ExperimentalTestApi::class)
private fun ReaderChromeTestFixture.assertAutoHide(previousToggles: Int) {
    val beforeDeadline = visibleSince + AUTO_HIDE_MS - 1
    val remaining = beforeDeadline - test.mainClock.currentTime
    assertTrue(remaining >= 0, "Test must reach the actual three-second deadline deliberately")
    test.mainClock.advanceTimeBy(remaining, ignoreFrameDuration = true)
    test.runOnIdle {
        assertTrue(state.isUiVisible, "Chrome must not hide early: $context")
        assertEquals(previousToggles, toggleCount)
    }
    test.mainClock.advanceTimeBy(2, ignoreFrameDuration = true)
    test.runOnIdle {
        assertFalse(state.isUiVisible, "Actual three-second effect must hide chrome: $context")
        assertEquals(previousToggles + 1, toggleCount)
    }
    advance(CHROME_ANIMATION_MS)
    assertChrome(visible = false, toggles = previousToggles + 1)
}
