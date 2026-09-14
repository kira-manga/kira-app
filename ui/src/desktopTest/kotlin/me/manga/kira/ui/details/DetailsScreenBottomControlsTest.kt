package me.manga.kira.ui.details

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DetailsScreenBottomControlsTest {
    @Test
    fun phoneLtrKeepsDeleteAndCancelTouchable() = runCase(Size(PHONE_WIDTH_PX, PHONE_HEIGHT_PX), LayoutDirection.Ltr)

    @Test
    fun phoneRtlKeepsDeleteAndCancelTouchable() = runCase(Size(PHONE_WIDTH_PX, PHONE_HEIGHT_PX), LayoutDirection.Rtl)

    @Test
    fun compactLtrKeepsDeleteAndCancelTouchable() {
        runCase(Size(COMPACT_WIDTH_PX, COMPACT_HEIGHT_PX), LayoutDirection.Ltr)
    }

    @Test
    fun compactRtlKeepsDeleteAndCancelTouchable() {
        runCase(Size(COMPACT_WIDTH_PX, COMPACT_HEIGHT_PX), LayoutDirection.Rtl)
    }

    private fun runCase(
        viewport: Size,
        direction: LayoutDirection,
    ) {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            runSkikoComposeUiTest(size = viewport, density = Density(TEST_DENSITY)) {
                val fixture = DetailsBottomControlsFixture()
                showBottomControls(fixture, direction)
                awaitIdle()
                assertEquals(emptyList(), fixture.intents)
                assertDeleteRoutesToFinalChapter(fixture, direction)
                assertCancelRoutesToSelectionClear(fixture, direction)
                assertEquals(emptyList(), fixture.intents)
                assertEquals(0, fixture.readerNavigationCount)
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}

private const val PHONE_WIDTH_PX = 360f
private const val PHONE_HEIGHT_PX = 640f
private const val COMPACT_WIDTH_PX = 320f
private const val COMPACT_HEIGHT_PX = 480f
private const val TEST_DENSITY = 1f
