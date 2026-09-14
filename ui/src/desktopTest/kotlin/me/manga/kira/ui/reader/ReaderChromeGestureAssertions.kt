@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package me.manga.kira.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.presentation.reader.ReaderIntent
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal const val CHROME_ANIMATION_MS = 320L
private const val MOTION_DURATION_MS = 500L
private const val ZOOM_SETTLE_MS = 900L
private const val PIXEL_TOLERANCE = 4
private const val EMPTY_TAP_X = 0.1f
private const val EMPTY_TAP_Y = 0.3f
private const val SWIPE_NEAR_EDGE = 0.15f
private const val SWIPE_FAR_EDGE = 0.85f
private const val MIN_DOUBLE_TAP_ZOOM = 1.7f
private const val PAN_WIDTH_FRACTION = 0.12f
private const val MIN_PAN_PIXELS = 10f
private const val PINCH_START_LEFT = 0.4f
private const val PINCH_START_RIGHT = 0.6f
private const val PINCH_END_LEFT = 0.25f
private const val PINCH_END_RIGHT = 0.75f
private const val MIN_PINCH_ZOOM = 1.5f
private const val MAGENTA_MIN_RED_BLUE = 0.6f
private const val MAGENTA_MAX_GREEN = 0.3f
private const val BACKGROUND_SAMPLE_DIVISOR = 4

internal fun ReaderChromeTestFixture.assertChrome(
    visible: Boolean,
    toggles: Int,
) {
    test.runOnIdle {
        assertEquals(toggles, toggleCount, "Chrome dispatch count: $context")
        assertEquals(visible, state.isUiVisible, "Chrome state: $context")
    }
    val back = test.onNodeWithContentDescription(backLabel)
    if (visible) back.assertIsDisplayed() else back.assertDoesNotExist()
}

internal fun ReaderChromeTestFixture.tapChrome(
    visible: Boolean,
    toggles: Int,
) {
    root.performTouchInput {
        // Empty-state tap avoids both the centered Retry button/spinner and the top bar.
        click(if (state.hasPages) center else Offset(width * EMPTY_TAP_X, height * EMPTY_TAP_Y))
    }
    // zoomable 2.12 defers a clean single tap until the actual double-tap window has closed.
    advance(doubleTapTimeout + CHROME_ANIMATION_MS)
    assertChrome(visible, toggles)
}

internal fun ReaderChromeTestFixture.assertForwardSwipe() {
    root.performTouchInput {
        val start =
            when (state.readingMode) {
                ReadingMode.LEFT_TO_RIGHT -> Offset(width * SWIPE_FAR_EDGE, centerY)
                ReadingMode.RIGHT_TO_LEFT -> Offset(width * SWIPE_NEAR_EDGE, centerY)
                else -> Offset(centerX, height * SWIPE_FAR_EDGE)
            }
        val end =
            when (state.readingMode) {
                ReadingMode.LEFT_TO_RIGHT -> Offset(width * SWIPE_NEAR_EDGE, centerY)
                ReadingMode.RIGHT_TO_LEFT -> Offset(width * SWIPE_FAR_EDGE, centerY)
                else -> Offset(centerX, height * SWIPE_NEAR_EDGE)
            }
        swipe(start, end, MOTION_DURATION_MS)
    }
    advance(ZOOM_SETTLE_MS)
    test.runOnIdle {
        assertTrue(state.currentPageIndex > 0, "A real swipe must advance content: $context")
        assertTrue(intents.filterIsInstance<ReaderIntent.OnPageChanged>().any { it.pageIndex > 0 })
    }
    assertChrome(visible = false, toggles = 0)
}

internal fun ReaderChromeTestFixture.assertZoomAndPan() {
    val original = stripe()
    root.performTouchInput { doubleClick(center) }
    advance(ZOOM_SETTLE_MS)
    val zoomed = stripe()
    assertTrue(zoomed.width > original.width * MIN_DOUBLE_TAP_ZOOM, "Double tap must visibly zoom: $context")
    assertChrome(visible = false, toggles = 0)
    assertZoomedPan(zoomed)
    root.performTouchInput { doubleClick(center) }
    advance(ZOOM_SETTLE_MS)
    val restored = stripe()
    assertTrue(abs(restored.width - original.width) <= PIXEL_TOLERANCE, "Zoom reset: $context")
    assertTrue(abs(restored.centerX - original.centerX) <= PIXEL_TOLERANCE, "Pan reset: $context")
    assertChrome(visible = false, toggles = 0)
    assertPinch(original)
}

private fun ReaderChromeTestFixture.assertZoomedPan(zoomed: ReaderChromeStripe) {
    root.performTouchInput {
        swipe(center, center + Offset(width * PAN_WIDTH_FRACTION, 0f), MOTION_DURATION_MS)
    }
    advance(ZOOM_SETTLE_MS)
    val panned = stripe()
    assertTrue(abs(panned.centerX - zoomed.centerX) > MIN_PAN_PIXELS, "Zoomed pan must move pixels: $context")
    assertTrue(abs(panned.width - zoomed.width) <= PIXEL_TOLERANCE, "Pan must retain scale: $context")
    assertEquals(0, state.currentPageIndex, "Pan inside zoomed content must not turn page: $context")
    assertChrome(visible = false, toggles = 0)
}

private fun ReaderChromeTestFixture.assertPinch(original: ReaderChromeStripe) {
    root.performTouchInput {
        pinch(
            start0 = Offset(width * PINCH_START_LEFT, centerY),
            end0 = Offset(width * PINCH_END_LEFT, centerY),
            start1 = Offset(width * PINCH_START_RIGHT, centerY),
            end1 = Offset(width * PINCH_END_RIGHT, centerY),
            durationMillis = MOTION_DURATION_MS,
        )
    }
    advance(ZOOM_SETTLE_MS)
    assertTrue(stripe().width > original.width * MIN_PINCH_ZOOM, "Pinch must visibly zoom: $context")
    assertChrome(visible = false, toggles = 0)
}

internal data class ReaderChromeStripe(
    val width: Int,
    val centerX: Float,
    val viewportWidth: Int,
    val hasGreenBackground: Boolean,
)

internal fun ReaderChromeTestFixture.stripe(): ReaderChromeStripe {
    val pixels = root.captureToImage().toPixelMap()
    val row = pixels.height / 2
    val stripe =
        (0 until pixels.width).filter { x ->
            val pixel = pixels[x, row]
            pixel.red > MAGENTA_MIN_RED_BLUE && pixel.blue > MAGENTA_MIN_RED_BLUE && pixel.green < MAGENTA_MAX_GREEN
        }
    assertTrue(stripe.isNotEmpty(), "Successful image's magenta stripe must be painted: $context")
    val background = pixels[pixels.width / BACKGROUND_SAMPLE_DIVISOR, row]
    return ReaderChromeStripe(
        width = stripe.last() - stripe.first() + 1,
        centerX = (stripe.first() + stripe.last()) / 2f,
        viewportWidth = pixels.width,
        hasGreenBackground = background.green > 0.55f && background.red < 0.15f && background.blue < 0.5f,
    )
}
