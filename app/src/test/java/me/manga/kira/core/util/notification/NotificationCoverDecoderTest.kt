package me.manga.kira.core.util.notification

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationCoverDecoderTest {
    @Test
    fun compressed4096Png_isBoundsFirstAndSampledBeforePixels() {
        val png = notificationPng(4096, 4096)
        assertTrue(png.size < 100_000)
        val passes = mutableListOf<Boolean>()
        val decoder =
            NotificationCoverDecoder { bytes, offset, size, options ->
                passes += options.inJustDecodeBounds
                if (!options.inJustDecodeBounds) {
                    assertEquals(listOf(true, false), passes)
                    assertEquals(16, options.inSampleSize)
                    assertFalse(options.inScaled)
                    assertEquals(0, options.inDensity)
                    assertEquals(0, options.inTargetDensity)
                    assertEquals(Bitmap.Config.ARGB_8888, options.inPreferredConfig)
                    assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), options.inPreferredColorSpace)
                }
                BitmapFactory.decodeByteArray(bytes, offset, size, options)
            }
        val bitmap = checkNotNull(decoder.decode(NotificationCoverBytes(png, png.size)))
        assertEquals(listOf(true, false), passes)
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        assertTrue(bitmap.allocationByteCount <= 262_144)
    }

    @Test
    fun invalidUnsupportedAndOversizedBounds_neverDecodePixels() {
        val denied =
            listOf(
                "<html>not an image</html>".toByteArray(),
                notificationPng(8193, 1),
                notificationPng(4097, 4096, rows = 1),
                Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="),
            )
        denied.forEach { encoded ->
            var boundsPasses = 0
            val decoder =
                NotificationCoverDecoder { bytes, offset, size, options ->
                    assertTrue("A rejected source must not reach pixel allocation", options.inJustDecodeBounds)
                    boundsPasses++
                    BitmapFactory.decodeByteArray(bytes, offset, size, options)
                }
            assertNull(decoder.decode(NotificationCoverBytes(encoded, encoded.size)))
            assertEquals(1, boundsPasses)
        }
    }

    @Test
    fun sizingRejectsInvalidAreaAndOverflow_andRoundsBothAxesUp() {
        listOf(0 to 1, -1 to 256, 1 to 8193, 4097 to 4096, Int.MAX_VALUE to Int.MAX_VALUE)
            .forEach { (width, height) -> assertNull(NotificationCoverLimits.sampleSize(width, height)) }
        assertEquals(2, NotificationCoverLimits.sampleSize(257, 1))
        assertEquals(2, NotificationCoverLimits.sampleSize(1, 257))
        assertEquals(32, NotificationCoverLimits.sampleSize(8192, 1))
        assertEquals(32, NotificationCoverLimits.sampleSize(1, 8192))
        assertEquals(16, NotificationCoverLimits.sampleSize(4096, 4096))
    }

    @Test
    fun jpegUsesTheSamePlatformRasterRoute_andReturnedAllocationIsChecked() {
        val tiny = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().also { tiny.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        assertNotNull(NotificationCoverDecoder().decode(NotificationCoverBytes(jpeg, jpeg.size)))
        assertRejectedBitmap(jpeg, Bitmap.createBitmap(257, 1, Bitmap.Config.ARGB_8888))
        // Small controlled allocation violation with otherwise valid dimensions, not an OOM attempt.
        val retained =
            Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                reconfigure(128, 128, Bitmap.Config.ARGB_8888)
            }
        assertTrue(retained.allocationByteCount > NotificationCoverLimits.ICON_BYTES)
        assertRejectedBitmap(jpeg, retained)
    }
}

private fun assertRejectedBitmap(
    jpeg: ByteArray,
    returned: Bitmap,
) {
    val badResult =
        NotificationCoverDecoder { bytes, offset, size, options ->
            if (options.inJustDecodeBounds) {
                BitmapFactory.decodeByteArray(bytes, offset, size, options)
            } else {
                returned
            }
        }
    assertNull(badResult.decode(NotificationCoverBytes(jpeg, jpeg.size)))
}
