package me.manga.kira.ui.updates

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.presentation.updates.UpdatesIntent
import kotlin.test.assertEquals

private const val SWIPE_STEP = 8f
private const val SWIPE_MOVES = 5
private const val SWIPE_START_STEP = 24f
private const val SWIPE_END_STEP = SWIPE_START_STEP + SWIPE_MOVES - 1
private const val SWIPE_BELOW_THRESHOLD = 32f
private const val SWIPE_RELEASE_PAUSE_MILLIS = 200L

@OptIn(ExperimentalTestApi::class)
internal class UpdatesSwipeDriver(
    private val ui: ComposeUiTest,
) {
    private val fixture = UpdatesRowAccessibilityFixture(ui)

    fun verify(direction: LayoutDirection) {
        fixture.render(direction)
        verifySwipe(readUpdate, direction, toggleRead = true)
        verifySwipe(unreadUpdate, direction, toggleRead = false)
        val rebound = verifyHeldSwipe(direction)
        verifySwipe(rebound, direction, toggleRead = true)
        verifyBelowThreshold(rebound)
        verifyActiveRowDisposal(rebound)
        verifySwipe(rebound, direction, toggleRead = false)
    }

    private fun settle() {
        ui.mainClock.advanceTimeBy(UPDATES_SETTLE_MILLIS)
        ui.waitForIdle()
    }

    private fun verifySwipe(
        entry: UpdateEntry,
        direction: LayoutDirection,
        toggleRead: Boolean,
    ) {
        val bounds = fixture.row(entry).fetchSemanticsNode().boundsInRoot
        fixture.row(entry).performTouchInput {
            if ((direction == LayoutDirection.Ltr) == toggleRead) swipeRight() else swipeLeft()
        }
        settle()
        fixture.expectOnly(if (toggleRead) UpdatesIntent.OnMarkAsRead(entry) else UpdatesIntent.OnRequestDelete(entry))
        assertEquals(bounds, fixture.row(entry).fetchSemanticsNode().boundsInRoot)
        fixture.assertRowActions(entry)
    }

    private fun verifyHeldSwipe(direction: LayoutDirection): UpdateEntry {
        val rebound = readUpdate.copy(isRead = false, chapterNumber = "2.5", chapterUrl = "file:///fixture/2.5")
        val claimed = rebound.copy(isRead = true, chapterNumber = "2.75", chapterUrl = "file:///fixture/2.75")
        val bounds = fixture.row(readUpdate).fetchSemanticsNode().boundsInRoot
        val step = Offset(if (direction == LayoutDirection.Ltr) SWIPE_STEP else -SWIPE_STEP, 0f)
        val input = ui.onRoot()
        input.performTouchInput { down(bounds.center) }
        ui.runOnIdle { fixture.state = fixture.state.copy(items = listOf(unreadUpdate, rebound)) }
        input.performTouchInput {
            repeat(SWIPE_MOVES) { moveTo(bounds.center + step * (SWIPE_START_STEP + it), delayMillis = 80L) }
        }
        fixture.expectOnly(UpdatesIntent.OnMarkAsRead(rebound))
        ui.runOnIdle { fixture.state = fixture.state.copy(items = listOf(unreadUpdate, claimed)) }
        ui.waitForIdle()
        reverseHeldSwipe(claimed, bounds, step)
        return claimed
    }

    private fun reverseHeldSwipe(
        entry: UpdateEntry,
        originalBounds: Rect,
        step: Offset,
    ) {
        val input = ui.onRoot()
        val movedBounds = fixture.row(entry).fetchSemanticsNode().boundsInRoot
        // The opposite edge is clipped during the drag; use the exposed edge in each direction.
        val offset =
            if (step.x > 0) movedBounds.left - originalBounds.left else movedBounds.right - originalBounds.right
        input.performTouchInput {
            moveTo(originalBounds.center + step * SWIPE_END_STEP - Offset(offset, 0f), delayMillis = 160L)
        }
        assertEquals(originalBounds, fixture.row(entry).fetchSemanticsNode().boundsInRoot)
        input.performTouchInput {
            repeat(SWIPE_MOVES) { moveTo(originalBounds.center - step * (SWIPE_START_STEP + it), delayMillis = 80L) }
        }
        fixture.expectOnly()
        input.performTouchInput { up() }
        settle()
        fixture.expectOnly()
        assertEquals(originalBounds, fixture.row(entry).fetchSemanticsNode().boundsInRoot)
    }

    private fun verifyBelowThreshold(entry: UpdateEntry) {
        val bounds = fixture.row(entry).fetchSemanticsNode().boundsInRoot
        ui.onRoot().performTouchInput {
            down(bounds.center)
            moveTo(bounds.center + Offset(SWIPE_BELOW_THRESHOLD, 0f), delayMillis = 600L)
            advanceEventTime(SWIPE_RELEASE_PAUSE_MILLIS)
            up()
        }
        settle()
        fixture.expectOnly()
        assertEquals(bounds, fixture.row(entry).fetchSemanticsNode().boundsInRoot)
    }

    private fun verifyActiveRowDisposal(entry: UpdateEntry) {
        val start =
            fixture
                .row(entry)
                .fetchSemanticsNode()
                .boundsInRoot.center
        val input = ui.onRoot()
        input.performTouchInput { down(start) }
        ui.runOnIdle { fixture.state = fixture.state.copy(pendingDeleteIds = setOf(entry.id)) }
        settle()
        fixture.row(entry).assertDoesNotExist()
        // Actual row disposal cancels its pointer coroutine; Skiko's cancel() injector is a no-op.
        input.performTouchInput {
            moveTo(start + Offset(UPDATES_PHONE_WIDTH.toFloat(), 0f), delayMillis = 80L)
            up()
        }
        fixture.expectOnly()
        ui.runOnIdle { fixture.state = fixture.state.copy(pendingDeleteIds = emptySet()) }
        settle()
        fixture.assertRowActions(entry)
    }
}
