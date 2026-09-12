@file:OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)

package me.manga.kira.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntRect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val REVEAL_ITEM_INDEX = 20
private const val REVEAL_OFFSET_PX = 7
private const val ALTERNATE_REVEAL_OFFSET_PX = 9
private const val REVEAL_SAMPLE_ADVANCE_MS = 160L
private const val DRAG_START_DELTA_PX = 32f
private const val FORWARD_DRAG_DELTA_PX = 80f
private const val MIN_FORWARD_ITEM_PROGRESS = 50
private const val END_SEEK_DELTA_PX = 400f
private const val END_RETURN_DELTA_PX = 48f
private const val MIN_BACKWARD_ITEM_PROGRESS = 20
private const val SHRUNK_GRID_HEIGHT_PX = 280
private const val SHRUNK_LIST_HEIGHT_PX = 240
private const val RESIZED_TOP_PADDING_PX = 8
private const val RESIZED_DRAG_DELTA_PX = 12f
private const val MIN_RESIZED_PROGRESS_PX = 10
private const val MIDPOINT_LOW_ITEM_INDEX = 400
private const val MIDPOINT_HIGH_ITEM_INDEX = 600
private const val IDLE_SHRINK_HEIGHT_PX = 200
private const val MIN_IDLE_REFRESH_PX = 8
private const val REACQUIRED_DRAG_DELTA_PX = 16f
private const val INVALIDATED_DRAG_DELTA_PX = 8f
private const val NEGATIVE_GRID_TRAVEL_PX = 4
private const val NEGATIVE_LIST_TRAVEL_PX = 8
private const val INVALID_EDGE_DRAG_DELTA_PX = 24f
private const val SCROLLER_THUMB_WIDTH_PX = 12
private const val MAGENTA_HIGH_CHANNEL = 0.7f
private const val MAGENTA_LOW_CHANNEL = 0.3f
private const val SETTLE_FRAME_COUNT = 3

class VerticalFastScrollerViewportTest {
    @Test
    fun listThumbSurvivesConstrainedViewportLifecycle() =
        runComposeUiTest {
            verifyLifecycle(FastScrollerViewportFixture(grid = false))
        }

    @Test
    fun paddedGridThumbSurvivesConstrainedViewportLifecycle() =
        runComposeUiTest {
            verifyLifecycle(FastScrollerViewportFixture(grid = true))
        }

    private fun ComposeUiTest.verifyLifecycle(fixture: FastScrollerViewportFixture) {
        mainClock.autoAdvance = false
        setContent { fixture.content() }
        frames()
        assertContent(fixture)
        assertContentSemanticsWork()
        revealThumb(fixture)
        dragThroughEndAndBack(fixture, phase = "initial")
        shrinkWhilePointerIsHeld(fixture)
        refreshAfterReturningToIdle(fixture)
        invalidateWhilePointerIsHeld(fixture)
        runOnIdle { fixture.heightPx = SCROLLER_INITIAL_HEIGHT_PX }
        frames()
        assertContent(fixture)
        assertContentSemanticsWork()
        revealThumb(fixture)
        dragThroughEndAndBack(fixture, phase = "restored")
        releasePointer()
        assertThumb(fixture)
    }

    private fun ComposeUiTest.assertContent(fixture: FastScrollerViewportFixture) {
        val bounds = onNodeWithTag(SCROLLER_CONTENT_TAG).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(SCROLLER_WIDTH_PX, bounds.width.roundToInt())
        assertEquals(fixture.heightPx, bounds.height.roundToInt())
        runOnIdle {
            assertEquals(SCROLLER_ITEM_COUNT, fixture.totalCount)
            assertTrue(fixture.visibleCount in 1 until fixture.totalCount)
            assertEquals(fixture.expectedAfterPaddingPx, fixture.afterPaddingPx)
        }
    }

    private fun ComposeUiTest.revealThumb(fixture: FastScrollerViewportFixture) {
        // Start the replay-zero flow's collector before producing a new pixel-offset change.
        frames()
        val previous = runOnIdle { fixture.firstOffset }
        runOnIdle {
            fixture.scrollTo(
                REVEAL_ITEM_INDEX,
                if (previous == REVEAL_OFFSET_PX) ALTERNATE_REVEAL_OFFSET_PX else REVEAL_OFFSET_PX,
            )
        }
        mainClock.advanceTimeBy(REVEAL_SAMPLE_ADVANCE_MS) // Cross sample(100), not the 2000ms fade delay.
        frames()
        runOnIdle {
            assertTrue(fixture.firstOffset != previous)
            assertFalse(fixture.isScrolling)
        }
        assertThumb(fixture)
    }

    private fun ComposeUiTest.dragThroughEndAndBack(
        fixture: FastScrollerViewportFixture,
        phase: String,
    ) {
        val thumb = assertThumb(fixture)
        val initial = runOnIdle { fixture.firstIndex }
        press(Offset((thumb.left + thumb.right) / 2f, (thumb.top + thumb.bottom) / 2f))
        moveHeld(DRAG_START_DELTA_PX) // Initiate dragging, then let the interaction collector recompose.
        moveHeld(FORWARD_DRAG_DELTA_PX)
        val (middle, middleDetails) = runOnIdle { fixture.firstIndex to fixture.progressDetails() }
        assertTrue(
            middle > initial + MIN_FORWARD_ITEM_PROGRESS,
            "A visible thumb must make useful intermediate progress: phase=$phase, initial=$initial, " +
                "middle=$middle, $middleDetails",
        )
        moveHeld(FORWARD_DRAG_DELTA_PX)
        runOnIdle {
            assertTrue(
                fixture.firstIndex > middle + MIN_FORWARD_ITEM_PROGRESS,
                "The same held drag must keep moving forward: phase=$phase, initial=$initial, middle=$middle, " +
                    "after=${fixture.firstIndex}, ${fixture.progressDetails()}",
            )
        }
        moveHeld(END_SEEK_DELTA_PX)
        assertAtEnd(fixture)
        val end = runOnIdle { fixture.firstIndex }
        moveHeld(-END_RETURN_DELTA_PX)
        runOnIdle {
            assertTrue(fixture.firstIndex < end - MIN_BACKWARD_ITEM_PROGRESS, "The same held drag must move upward")
        }
        moveHeld(END_RETURN_DELTA_PX)
        assertAtEnd(fixture)
    }

    private fun ComposeUiTest.shrinkWhilePointerIsHeld(fixture: FastScrollerViewportFixture) {
        runOnIdle {
            fixture.heightPx = if (fixture.grid) SHRUNK_GRID_HEIGHT_PX else SHRUNK_LIST_HEIGHT_PX
            fixture.topPaddingPx = RESIZED_TOP_PADDING_PX
        }
        frames()
        assertContent(fixture)
        val clamped = assertThumb(fixture)
        assertEquals(fixture.heightPx - fixture.afterPaddingPx - SCROLLER_THUMB_HEIGHT_PX, clamped.top)
        moveHeld(-RESIZED_DRAG_DELTA_PX)
        val moved = assertThumb(fixture)
        assertTrue(
            moved.top <= clamped.top - MIN_RESIZED_PROGRESS_PX,
            "Resize/top padding must not restart or stall the held drag",
        )
        moveHeld(RESIZED_DRAG_DELTA_PX)
        assertAtEnd(fixture)
    }

    private fun ComposeUiTest.refreshAfterReturningToIdle(fixture: FastScrollerViewportFixture) {
        val travel = fixture.heightPx - fixture.topPaddingPx - fixture.afterPaddingPx - SCROLLER_THUMB_HEIGHT_PX
        moveHeld(-travel / 2f)
        runOnIdle { assertTrue(fixture.firstIndex in MIDPOINT_LOW_ITEM_INDEX..MIDPOINT_HIGH_ITEM_INDEX) }
        runOnIdle { fixture.heightPx = IDLE_SHRINK_HEIGHT_PX }
        frames()
        val whileHeld = assertThumb(fixture)
        releasePointer()
        val idle = assertThumb(fixture)
        assertTrue(
            idle.top < whileHeld.top - MIN_IDLE_REFRESH_PX,
            "Idle must refresh the geometry change skipped during dragging",
        )
        // Reacquire a visible, working thumb before invalidating the next held gesture.
        press(Offset((idle.left + idle.right) / 2f, (idle.top + idle.bottom) / 2f))
        moveHeld(DRAG_START_DELTA_PX)
        moveHeld(REACQUIRED_DRAG_DELTA_PX)
        runOnIdle { assertTrue(fixture.firstIndex > MIDPOINT_HIGH_ITEM_INDEX) }
    }

    private fun ComposeUiTest.invalidateWhilePointerIsHeld(fixture: FastScrollerViewportFixture) {
        val thumb = assertThumb(fixture)
        val edgeX = (thumb.left + thumb.right) / 2f
        runOnIdle {
            fixture.topPaddingPx = 0
            fixture.heightPx = fixture.expectedAfterPaddingPx + SCROLLER_THUMB_HEIGHT_PX
        }
        frames()
        assertContent(fixture)
        assertNull(thumbBounds(), "Zero rendered travel must remove the overlay")
        runOnIdle {
            assertTrue(fixture.firstIndex > SCROLLER_ITEM_COUNT / 2, "Removing the overlay must not reset content")
        }
        moveHeld(-INVALIDATED_DRAG_DELTA_PX)
        releasePointer()
        runOnIdle { fixture.heightPx -= if (fixture.grid) NEGATIVE_GRID_TRAVEL_PX else NEGATIVE_LIST_TRAVEL_PX }
        frames()
        assertContent(fixture)
        assertNull(thumbBounds(), "Negative travel must also remove the overlay")
        press(Offset(edgeX, fixture.heightPx / 2f))
        moveHeld(-INVALID_EDGE_DRAG_DELTA_PX)
        releasePointer()
        assertContentSemanticsWork()
        assertNull(thumbBounds())
    }

    private fun ComposeUiTest.assertAtEnd(fixture: FastScrollerViewportFixture) {
        runOnIdle { assertFalse(fixture.canScrollForward, "Thumb-to-end must reach the content end") }
        onNodeWithTag(scrollerItemTag(SCROLLER_ITEM_COUNT - 1)).assertIsDisplayed()
        assertThumb(fixture)
    }
}

private fun FastScrollerViewportFixture.progressDetails(): String =
    "offset=$firstOffset, scrolling=$isScrolling, canScrollForward=$canScrollForward, " +
        "heightPx=$heightPx, topPaddingPx=$topPaddingPx, afterPaddingPx=$afterPaddingPx"

private fun ComposeUiTest.assertContentSemanticsWork() {
    onNodeWithTag(SCROLLER_CONTENT_TAG).performScrollToIndex(REVEAL_ITEM_INDEX)
    frames()
    onNodeWithTag(scrollerItemTag(REVEAL_ITEM_INDEX)).assertIsDisplayed()
}

private fun ComposeUiTest.assertThumb(fixture: FastScrollerViewportFixture): IntRect {
    val bounds = assertNotNull(thumbBounds(), "Expected actual visible thumb pixels")
    // Exact painted dimensions also reject a thumb clipped by the captured host boundary.
    assertEquals(SCROLLER_THUMB_WIDTH_PX, bounds.width)
    assertEquals(SCROLLER_THUMB_HEIGHT_PX, bounds.height)
    assertTrue(bounds.top >= fixture.topPaddingPx)
    assertTrue(bounds.bottom <= fixture.heightPx - fixture.afterPaddingPx)
    return bounds
}

private fun ComposeUiTest.thumbBounds(): IntRect? {
    val image = onNodeWithTag(SCROLLER_HOST_TAG).captureToImage()
    val pixels = image.toPixelMap()
    var left = image.width
    var top = image.height
    var right = -1
    var bottom = -1
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val color = pixels[x, y]
            if (
                color.red > MAGENTA_HIGH_CHANNEL &&
                color.blue > MAGENTA_HIGH_CHANNEL &&
                color.green < MAGENTA_LOW_CHANNEL
            ) {
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
            }
        }
    }
    return if (right < 0) null else IntRect(left, top, right + 1, bottom + 1)
}

private fun ComposeUiTest.press(position: Offset) {
    onNodeWithTag(SCROLLER_HOST_TAG).performTouchInput { down(position) }
    frames()
}

private fun ComposeUiTest.moveHeld(deltaY: Float) {
    // Each block is a separate batch; the same pointer survives recomposition and resize.
    onNodeWithTag(SCROLLER_HOST_TAG).performTouchInput {
        moveTo(checkNotNull(currentPosition()) + Offset(0f, deltaY))
    }
    frames()
}

private fun ComposeUiTest.releasePointer() {
    onNodeWithTag(SCROLLER_HOST_TAG).performTouchInput { up() }
    frames()
}

private fun ComposeUiTest.frames() {
    runOnUiThread { mainClock.scheduler.runCurrent() }
    repeat(SETTLE_FRAME_COUNT) { mainClock.advanceTimeByFrame() }
    runOnUiThread { mainClock.scheduler.runCurrent() }
    waitForIdle()
}
