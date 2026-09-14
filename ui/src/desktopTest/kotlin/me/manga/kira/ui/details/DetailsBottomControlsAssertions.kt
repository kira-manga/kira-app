@file:OptIn(ExperimentalTestApi::class)

package me.manga.kira.ui.details

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import me.manga.kira.presentation.details.DetailsIntent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal suspend fun ComposeUiTest.assertDeleteRoutesToFinalChapter(
    fixture: DetailsBottomControlsFixture,
    direction: LayoutDirection,
) {
    val expandedBounds = resumeFab.requireFullyVisible().boundsInRoot
    assertTrue(chapterList.fetchSemanticsNode().boundsInRoot.bottom <= expandedBounds.top)
    chapterList.performScrollToIndex(fixture.finalChapterIndex)
    awaitIdle()
    val collapsedFab = resumeFab.requireFullyVisible()
    assertTrue(collapsedFab.boundsInRoot.width < expandedBounds.width, "Scroll must collapse Resume")
    val screenCenter = onRoot().fetchSemanticsNode().boundsInRoot.center.x
    val fabCenter = collapsedFab.boundsInRoot.center.x
    assertTrue(if (direction == LayoutDirection.Ltr) fabCenter > screenCenter else fabCenter < screenCenter)
    val finalRow = hasText(fixture.finalChapter.number) and hasAnyAncestor(hasScrollToIndexAction())
    assertFinalRowClear(finalRow, collapsedFab)
    val delete = onNode(hasContentDescription(DELETE_LABEL) and hasAnyAncestor(finalRow))
    touchCenter(delete)
    awaitIdle()
    fixture.assertOnlyIntentAndReset(DetailsIntent.OnDeleteChapter(fixture.finalChapter))
}

internal suspend fun ComposeUiTest.assertCancelRoutesToSelectionClear(
    fixture: DetailsBottomControlsFixture,
    direction: LayoutDirection,
) {
    selectFinalChapter(fixture)
    resumeFab.assertDoesNotExist()
    val actions = selectionActions
    val before = actions.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
    val start = before.value()
    assertTrue(before.maxValue() > 0f, "The complete selection action set must overflow this narrow viewport")
    actions.performTouchInput {
        if (direction == LayoutDirection.Ltr) swipeLeft() else swipeRight()
    }
    awaitIdle()
    val after = actions.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
    assertTrue(after.value() > start, "Cancel must be reached by scrolling the real action row")
    val cancel = onNodeWithContentDescription(CANCEL_LABEL)
    val bounds = cancel.requireFullyVisible().boundsInRoot
    assertTrue(chapterList.fetchSemanticsNode().boundsInRoot.bottom <= bounds.top)
    touchCenter(cancel)
    awaitIdle()
    fixture.assertOnlyIntentAndReset(DetailsIntent.OnSelectionClear)
    resumeFab.assertIsDisplayed()
}

private suspend fun ComposeUiTest.selectFinalChapter(fixture: DetailsBottomControlsFixture) {
    // The text center belongs to the row, not one of its independently clickable trailing buttons.
    onNodeWithText(fixture.finalChapter.number, useUnmergedTree = true).performTouchInput { longClick(center) }
    awaitIdle()
    fixture.assertOnlyIntentAndReset(DetailsIntent.OnChapterLongClick(fixture.finalChapter))
    assertTrue(fixture.state.value.isInChapterSelectionMode)
}

private fun ComposeUiTest.assertFinalRowClear(
    rowMatcher: SemanticsMatcher,
    fab: SemanticsNode,
) {
    assertDisjoint(onNode(rowMatcher).requireFullyVisible(), fab)
    val buttons = onAllNodes(hasClickAction() and hasAnyAncestor(rowMatcher)).fetchSemanticsNodes()
    assertTrue(buttons.isNotEmpty(), "The final saved chapter must expose its trailing controls")
    buttons.forEach { assertDisjoint(it, fab) }
    assertTrue(chapterList.fetchSemanticsNode().boundsInRoot.bottom <= fab.boundsInRoot.top)
}

private fun assertDisjoint(
    control: SemanticsNode,
    fab: SemanticsNode,
) {
    val visual = control.boundsInRoot
    val touch = control.touchBoundsInRoot
    assertTrue(visual.width > 0f && visual.height > 0f, "A clipped-away control is not a disjointness proof")
    assertTrue(touch.width > 0f && touch.height > 0f)
    assertFalse(visual.overlaps(fab.boundsInRoot), "Chapter visual bounds overlap Resume")
    assertFalse(touch.overlaps(fab.touchBoundsInRoot), "Chapter touch bounds overlap Resume")
}

private fun SemanticsNodeInteraction.requireFullyVisible(): SemanticsNode {
    assertIsDisplayed().assertHasClickAction()
    val node = fetchSemanticsNode()
    val layoutBounds = Rect(node.positionInRoot, node.size.toSize())
    assertTrue(layoutBounds.width > 0f && layoutBounds.height > 0f)
    assertEquals(layoutBounds, node.boundsInRoot, "The entire clickable control must be visible")
    return node
}

private fun ComposeUiTest.touchCenter(target: SemanticsNodeInteraction) {
    val targetBounds = target.requireFullyVisible().boundsInRoot
    val root = onRoot()
    val position = targetBounds.center - root.fetchSemanticsNode().boundsInRoot.topLeft
    // Inject through the root so z-order and pointer hit testing, not semantic OnClick, choose the receiver.
    root.performTouchInput { click(position) }
}

private val ComposeUiTest.chapterList: SemanticsNodeInteraction
    get() = onNode(hasScrollToIndexAction())

private val ComposeUiTest.resumeFab: SemanticsNodeInteraction
    get() =
        onNode(
            hasContentDescription(RESUME_LABEL) and
                hasClickAction() and
                !hasAnyAncestor(hasScrollToIndexAction()),
        )

private val ComposeUiTest.selectionActions: SemanticsNodeInteraction
    get() =
        onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
                hasAnyDescendant(hasContentDescription(CANCEL_LABEL)),
        )

private const val RESUME_LABEL = "Resume"
private const val DELETE_LABEL = "Delete chapter"
private const val CANCEL_LABEL = "Cancel"
