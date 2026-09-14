package me.manga.kira.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FastScrollerGeometryTest {
    @Test
    fun unavailableGeometryHasNoTrack() {
        val invalidNumbers = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val cases =
            listOf(
                Input(height = -1),
                Input(height = 0),
                Input(height = 40),
                Input(height = 48),
                Input(height = 53, thumb = 48f * 1.1f),
                Input(height = 140, after = 96),
                Input(height = 144, after = 96),
                Input(height = 60, after = 16),
                Input(top = -1f),
                Input(bottom = -1f),
                Input(after = -1),
                Input(thumb = 0f),
                Input(thumb = 0.4f),
                Input(height = Int.MAX_VALUE, bottom = Float.MAX_VALUE, after = Int.MAX_VALUE),
            ) + invalidNumbers.flatMap { listOf(Input(top = it), Input(bottom = it), Input(thumb = it)) }
        cases.forEach { assertNull(it.geometry(), "Unavailable geometry: $it") }
    }

    @Test
    fun renderedBoundsRespectPaddingAndDensityRounding() {
        val cases =
            listOf(
                BoundsCase(Input(), top = 0, end = 352, thumb = 48),
                BoundsCase(Input(height = 54, thumb = 48f * 1.1f), top = 0, end = 1, thumb = 53),
                BoundsCase(Input(top = 16f, bottom = 8f, after = 24), top = 16, end = 320, thumb = 48),
                BoundsCase(Input(top = 12.5f, bottom = 7.5f, after = 96, thumb = 48.5f), 13, 247, 49),
            )
        cases.forEach { case ->
            val geometry = assertNotNull(case.input.geometry())
            assertEquals(case.top, geometry.minOffsetPx)
            assertEquals(case.end, geometry.maxOffsetPx)
            assertEquals(case.thumb, geometry.thumbHeightPx)
            assertTrue(geometry.trackHeightPx > 0f && geometry.trackHeightPx.isFinite())
            assertEquals(case.top, geometry.placementOffset(-Float.MAX_VALUE))
            assertEquals(case.end, geometry.placementOffset(Float.MAX_VALUE))
            assertEquals(0f, geometry.scrollRatio(case.top.toFloat()))
            assertEquals(1f, geometry.scrollRatio(case.end.toFloat()))
        }
    }

    @Test
    fun positionsRemainFiniteAndRebaseBeforeTheNextDragDelta() {
        val smaller = assertNotNull(Input(height = 160, top = 12f).geometry())
        assertEquals(112, smaller.placementOffset(300f))
        assertEquals(102f, smaller.offsetAfterDelta(300f, -10f))
        assertEquals(112f, smaller.offsetAfterDelta(100f, Float.MAX_VALUE))
        assertEquals(12f, smaller.offsetAfterDelta(100f, -Float.MAX_VALUE))
        assertEquals(62f, smaller.offsetForScroll(0.5f))
        assertEquals(12f, smaller.offsetForScroll(-1f))
        assertEquals(112f, smaller.offsetForScroll(2f))
        assertEquals(0f, smaller.scrollRatio(-Float.MAX_VALUE))
        assertEquals(1f, smaller.scrollRatio(Float.MAX_VALUE))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            assertEquals(12, smaller.placementOffset(invalid))
            assertEquals(112f, smaller.offsetAfterDelta(300f, invalid))
            assertEquals(0f, smaller.scrollRatio(invalid))
            assertEquals(12f, smaller.offsetForScroll(invalid))
        }
    }

    private data class Input(
        val height: Int = 400,
        val top: Float = 0f,
        val bottom: Float = 0f,
        val after: Int = 0,
        val thumb: Float = 48f,
    ) {
        fun geometry(): FastScrollerGeometry? = FastScrollerGeometry.create(height, top, bottom, after, thumb)
    }

    private data class BoundsCase(
        val input: Input,
        val top: Int,
        val end: Int,
        val thumb: Int,
    )
}
