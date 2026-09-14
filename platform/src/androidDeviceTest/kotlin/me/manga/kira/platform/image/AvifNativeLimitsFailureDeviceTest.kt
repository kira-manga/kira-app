package me.manga.kira.platform.image

import android.graphics.Bitmap
import android.graphics.Color
import org.aomedia.avif.android.AvifDecoder
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Recovery must execute the real JNI implementation, including a failure after bitmap locking. */
class AvifNativeLimitsFailureDeviceTest {
    @Test
    fun positiveLimitAndSnapshotChecksRejectInvalidArguments() {
        val bytes = AvifTestFixtures.regular()
        val input = nativeAvifInput(bytes)
        val info = AvifDecoder.Info()
        val pixels = AvifNativeLimitFixture.SOURCE_PIXELS
        val edge = AvifNativeLimitFixture.SOURCE_HEIGHT
        for (invalid in listOf(0, -1, Int.MAX_VALUE)) {
            assertFalse(AvifDecoder.getInfoWithLimits(input, bytes.size, info, invalid, edge))
            assertFalse(AvifDecoder.getInfoWithLimits(input, bytes.size, info, pixels, invalid))
            assertFalse(AvifDecoder.getInfoWithLimits(input, invalid, info, pixels, edge))
        }
        assertFalse(AvifDecoder.getInfoWithLimits(ByteBuffer.wrap(bytes), bytes.size, info, pixels, edge))
        assertFalse(AvifDecoder.getInfoWithLimits(null, bytes.size, info, pixels, edge))
        assertFalse(AvifDecoder.getInfoWithLimits(input, bytes.size, null, pixels, edge))
        input.position(1)
        assertFalse(AvifDecoder.getInfoWithLimits(input, input.remaining(), info, pixels, edge))
        input.position(0)
        input.limit(bytes.size - 1)
        assertFalse(AvifDecoder.getInfoWithLimits(input, bytes.size, info, pixels, edge))
        input.limit(bytes.size)
        assertTrue(AvifDecoder.getInfoWithLimits(input, bytes.size, info, pixels, edge))
    }

    @Test
    fun boundedDecodeChecksLimitsAndDestinationBeforeNativeWork() {
        val input = nativeAvifInput(AvifTestFixtures.regular())
        val pixels = AvifNativeLimitFixture.SOURCE_PIXELS
        val edge = AvifNativeLimitFixture.SOURCE_HEIGHT
        withNativeAvifBitmap { bitmap ->
            for (invalid in listOf(0, -1, Int.MAX_VALUE)) {
                assertFalse(AvifDecoder.decodeWithLimits(input, input.remaining(), bitmap, 1, invalid, edge))
                assertFalse(AvifDecoder.decodeWithLimits(input, input.remaining(), bitmap, 1, pixels, invalid))
                assertFalse(AvifDecoder.decodeWithLimits(input, invalid, bitmap, 1, pixels, edge))
            }
            assertFalse(AvifDecoder.decodeWithLimits(null, input.remaining(), bitmap, 1, pixels, edge))
            val recycled = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            recycled.recycle()
            assertFalse(AvifDecoder.decodeWithLimits(input, input.remaining(), recycled, 1, pixels, edge))
            val immutable = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            try {
                assertFalse(AvifDecoder.decodeWithLimits(input, input.remaining(), immutable, 1, pixels, edge))
            } finally {
                immutable.recycle()
            }
            assertFalse(AvifDecoder.decodeWithLimits(input, input.remaining(), null, 1, pixels, edge))
            assertTrue(AvifDecoder.decodeWithLimits(input, input.remaining(), bitmap, 1, pixels, edge))
            assertNativeAvifPixelsDrawn(bitmap)
        }
    }

    @Test
    fun postLockScaleFailureAllowsTheSameBitmapToBeReconfiguredAndReused() {
        val input = nativeAvifInput(AvifTestFixtures.regular())
        val pixels = AvifNativeLimitFixture.SOURCE_PIXELS
        val edge = AvifNativeLimitFixture.SOURCE_HEIGHT
        // About 128 KiB of destination pixels: no oversized source or OOM experiment is needed.
        withNativeAvifBitmap(width = AvifNativeLimitFixture.NATIVE_AXIS_LIMIT + 1, height = 1) { bitmap ->
            // avifImageScale rejects this destination axis after AndroidBitmap_lockPixels succeeds.
            assertFalse(AvifDecoder.decodeWithLimits(input, input.remaining(), bitmap, 1, pixels, edge))
            bitmap.reconfigure(
                AvifNativeLimitFixture.OUTPUT_EDGE,
                AvifNativeLimitFixture.OUTPUT_EDGE,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(Color.TRANSPARENT)
            assertTrue(AvifDecoder.decodeWithLimits(input, input.remaining(), bitmap, 1, pixels, edge))
            assertNativeAvifPixelsDrawn(bitmap)
        }
    }
}
