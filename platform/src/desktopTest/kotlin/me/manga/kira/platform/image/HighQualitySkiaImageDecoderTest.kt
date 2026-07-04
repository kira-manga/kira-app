package me.manga.kira.platform.image

import coil3.PlatformContext
import coil3.request.Options
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #3-INTERIM — [decodeEncodedImageWithSkia] (the shared Skia decode path used by
 * [HighQualitySkiaImageDecoder] and the iOS AVIF decoder) must decode a valid image and turn an
 * undecodable input (e.g. AVIF, which Skiko's Skia can't decode) into a clean
 * [IllegalStateException] rather than letting a bare native exception escape.
 *
 * Desktop (JVM/skiko-awt) is the only :platform test source set; the decode API is the identical
 * `org.jetbrains.skia` surface on iOS.
 */
class HighQualitySkiaImageDecoderTest {

    private val options = Options(PlatformContext.INSTANCE)

    @Test
    fun decodesValidImage() {
        val result = decodeEncodedImageWithSkia(solidPng(width = 8, height = 8), options)
        assertTrue(result.image.width >= 1 && result.image.height >= 1, "a valid PNG decodes to a bitmap")
    }

    @Test
    fun undecodableBytes_throwCleanDecodeError() {
        val garbage = ByteArray(64) { it.toByte() }
        assertFailsWith<IllegalStateException>("undecodable bytes surface a clean decode error") {
            decodeEncodedImageWithSkia(garbage, options)
        }
    }

    /** A valid PNG of a solid colour, built with Skia so the test has no binary fixtures. */
    private fun solidPng(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
        try {
            Canvas(bitmap).clear(Color.makeRGB(10, 120, 200))
            bitmap.setImmutable()
            val image = Image.makeFromBitmap(bitmap)
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG, 100)
                    ?: error("PNG encode failed in test setup")
                try {
                    return data.bytes
                } finally {
                    data.close()
                }
            } finally {
                image.close()
            }
        } finally {
            bitmap.close()
        }
    }
}
