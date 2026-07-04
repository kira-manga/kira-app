package me.manga.kira.ui.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import me.manga.kira.presentation.updates.RowDownloadStatus
import me.manga.kira.ui.theme.KiraTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pixel-level visibility check for the Updates per-row download affordance (backlog L4).
 *
 * Native tinted the QUEUED spinner `onPrimary` — white-on-white against the light-theme row
 * background, i.e. an invisible spinner (a native bug the rework initially ported faithfully).
 * These tests pin the fix (`onSurfaceVariant`) by rendering the REAL composable on the screen's
 * actual row background (`colorScheme.background`, UpdatesScreenContent's list container) and
 * counting captured pixels that differ from that background: with the old `onPrimary` tint the
 * light-theme count is ~0 and the test fails.
 *
 * The indeterminate arc's sweep oscillates, so each check samples several animation phases and
 * takes the max — a single capture could land on a minimal-sweep phase.
 */
@OptIn(ExperimentalTestApi::class)
class DownloadAffordanceVisibilityTest {

    /** Renders the affordance on the screen's row background; returns that background color. */
    private fun ComposeUiTest.renderAffordance(
        darkTheme: Boolean,
        status: RowDownloadStatus,
    ): Color {
        var background = Color.Unspecified
        setContent {
            KiraTheme(darkTheme = darkTheme) {
                background = MaterialTheme.colorScheme.background
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.background)
                        .testTag(TAG),
                    contentAlignment = Alignment.Center,
                ) {
                    DownloadAffordance(status = status, onDownloadClick = {})
                }
            }
        }
        return background
    }

    /** Max non-background pixel count across several animation phases of the indeterminate arc. */
    private fun ComposeUiTest.maxVisiblePixels(background: Color): Int {
        mainClock.autoAdvance = false
        var best = 0
        repeat(6) {
            mainClock.advanceTimeBy(200)
            best = maxOf(best, countPixelsDifferingFrom(onNodeWithTag(TAG).captureToImage(), background))
        }
        return best
    }

    private fun countPixelsDifferingFrom(image: ImageBitmap, background: Color): Int {
        val pixels = image.toPixelMap()
        var count = 0
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) {
                val p = pixels[x, y]
                val delta = abs(p.red - background.red) +
                    abs(p.green - background.green) +
                    abs(p.blue - background.blue)
                if (delta > CHANNEL_DELTA_THRESHOLD) count++
            }
        }
        return count
    }

    @Test
    fun queuedSpinner_visiblyRenders_inLightTheme() = runComposeUiTest {
        val background = renderAffordance(darkTheme = false, status = RowDownloadStatus.QUEUED)
        val visible = maxVisiblePixels(background)
        assertTrue(
            visible >= MIN_VISIBLE_PIXELS,
            "QUEUED spinner is invisible on the light row background ($visible px differ; " +
                "the pre-L4 onPrimary tint fails here)",
        )
    }

    @Test
    fun queuedSpinner_visiblyRenders_inDarkTheme() = runComposeUiTest {
        val background = renderAffordance(darkTheme = true, status = RowDownloadStatus.QUEUED)
        val visible = maxVisiblePixels(background)
        assertTrue(
            visible >= MIN_VISIBLE_PIXELS,
            "QUEUED spinner is invisible on the dark row background ($visible px differ)",
        )
    }

    /** Control: the RUNNING spinner (primary tint, unchanged by L4) must also stay visible. */
    @Test
    fun runningSpinner_visiblyRenders_inLightTheme() = runComposeUiTest {
        val background = renderAffordance(darkTheme = false, status = RowDownloadStatus.RUNNING)
        val visible = maxVisiblePixels(background)
        assertTrue(visible >= MIN_VISIBLE_PIXELS, "RUNNING spinner is invisible ($visible px differ)")
    }

    private companion object {
        const val TAG = "download-affordance"

        /**
         * Sum-of-RGB-channel delta a pixel must exceed to count as "visible" against the
         * background — generous enough to ignore antialiased fringe blending, small enough that
         * any real stroke pixel counts.
         */
        const val CHANNEL_DELTA_THRESHOLD = 0.15f

        /**
         * The 24dp/2dp-stroke arc at its minimum sweep still covers well over this many pixels;
         * an invisible (background-colored) spinner counts ~0.
         */
        const val MIN_VISIBLE_PIXELS = 10
    }
}
