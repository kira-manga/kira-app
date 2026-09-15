package me.manga.kira.platform.image

import android.graphics.Color
import org.aomedia.avif.android.AvifDecoder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real patched Java/JNI/dav1d tests, NOT_RUN. Never replace these calls with host doubles. */
class AvifNativeLimitsDeviceTest {
    @Test
    fun ordinaryFixtureHonorsPixelAndContainerAxisBoundaries() {
        val input = nativeAvifInput(AvifTestFixtures.regular())
        val info = AvifDecoder.Info()
        val pixels = AvifNativeLimitFixture.SOURCE_PIXELS
        val edge = AvifNativeLimitFixture.SOURCE_HEIGHT
        assertTrue(AvifDecoder.getInfoWithLimits(input, input.remaining(), info, pixels, edge))
        assertEquals(AvifNativeLimitFixture.SOURCE_WIDTH, info.width)
        assertEquals(edge, info.height)
        assertFalse(AvifDecoder.getInfoWithLimits(input, input.remaining(), info, pixels - 1, edge))
        assertFalse(AvifDecoder.getInfoWithLimits(input, input.remaining(), info, pixels, edge - 1))
        withNativeAvifBitmap { bitmap ->
            assertFalse(AvifDecoder.decodeWithLimits(input, input.remaining(), bitmap, 1, pixels - 1, edge))
            assertTrue(AvifDecoder.decodeWithLimits(input, input.remaining(), bitmap, 1, pixels, edge))
            assertNativeAvifPixelsDrawn(bitmap)
        }
    }

    @Test
    fun smallDeclaredContainerStillNeedsTheActualAv1FramePixelBudget() {
        val input = nativeAvifInput(AvifNativeLimitFixture.declaredSmall())
        val info = AvifDecoder.Info()
        val pixels = AvifNativeLimitFixture.SOURCE_PIXELS
        val edge = AvifNativeLimitFixture.DECLARED_EDGE
        assertTrue(
            AvifDecoder.getInfoWithLimits(input, input.remaining(), info, AvifNativeLimitFixture.DECLARED_PIXELS, edge),
        )
        assertEquals(edge, info.width)
        assertEquals(edge, info.height)
        withNativeAvifBitmap { bitmap ->
            // Both controls MUST decode. A malformed-only false is not native-limit proof.
            assertTrue(AvifDecoder.decode(input, input.remaining(), bitmap, 1))
            assertNativeAvifPixelsDrawn(bitmap)
            bitmap.eraseColor(Color.TRANSPARENT)
            assertFalse(AvifDecoder.decodeWithLimits(input, input.remaining(), bitmap, 1, pixels - 1, edge))
            bitmap.eraseColor(Color.TRANSPARENT)
            assertTrue(AvifDecoder.decodeWithLimits(input, input.remaining(), bitmap, 1, pixels, edge))
            assertNativeAvifPixelsDrawn(bitmap)
            bitmap.eraseColor(Color.TRANSPARENT)
            assertTrue(AvifDecoder.decodeWithLimits(input, input.remaining(), bitmap, 1, pixels + 1, edge))
            assertNativeAvifPixelsDrawn(bitmap)
        }
        assertEquals(0, input.position())
    }

    @Test
    fun containerAxisLimitIsNotMisrepresentedAsAnActualAv1AxisLimit() {
        val input = nativeAvifInput(AvifNativeLimitFixture.declaredSmall())
        val edge = AvifNativeLimitFixture.DECLARED_EDGE
        withNativeAvifBitmap(edge, edge) { bitmap ->
            // Real AV1 is 320x640 (>32 per axis); dav1d exposes a frame-pixel cap, not this axis cap.
            val pixels = AvifNativeLimitFixture.SOURCE_PIXELS
            assertTrue(AvifDecoder.decodeWithLimits(input, input.remaining(), bitmap, 1, pixels, edge))
            assertNativeAvifPixelsDrawn(bitmap)
        }
    }

    @Test
    fun existingApisAndBoundedApisBothDecodeTheTallFixture() {
        val input = nativeAvifInput(AvifTestFixtures.tall())
        val info = AvifDecoder.Info()
        val width = AvifNativeLimitFixture.TALL_WIDTH
        val height = AvifNativeLimitFixture.TALL_HEIGHT
        assertTrue(AvifDecoder.getInfo(input, input.remaining(), info))
        assertEquals(width, info.width)
        assertEquals(height, info.height)
        withNativeAvifBitmap(width, height) { bitmap ->
            assertTrue(AvifDecoder.decode(input, input.remaining(), bitmap, 1))
            assertNativeAvifPixelsDrawn(bitmap)
            bitmap.eraseColor(Color.TRANSPARENT)
            assertTrue(AvifDecoder.decodeWithLimits(input, input.remaining(), bitmap, 1, width * height, height))
            assertNativeAvifPixelsDrawn(bitmap)
        }
    }
}
