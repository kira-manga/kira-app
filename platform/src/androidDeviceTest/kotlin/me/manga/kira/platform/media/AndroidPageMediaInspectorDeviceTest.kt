package me.manga.kira.platform.media

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import me.manga.kira.platform.image.AvifTestFixtures
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Real BitmapFactory/ImageDecoder/libavif. Run on API26/27 and API28+; host mocks cannot qualify it. */
class AndroidPageMediaInspectorDeviceTest {
    private val inspector = AndroidPageMediaInspector()

    @Test
    fun nativeCodecsValidateAllSixFormatsWithActualMetadata() {
        val images =
            mapOf(
                PageImageFormat.PNG to PageMediaTestImages.png(),
                PageImageFormat.JPEG to encoded(Bitmap.CompressFormat.JPEG),
                PageImageFormat.WEBP to encoded(Bitmap.CompressFormat.WEBP),
                PageImageFormat.GIF to PageMediaTestImages.gif(),
                PageImageFormat.BMP to PageMediaTestImages.bmp(),
                PageImageFormat.AVIF to AvifTestFixtures.regular(),
            )
        for ((format, bytes) in images) {
            assertEquals(
                format,
                assertIs<PageInspection.Valid>(inspector.inspect(bytes), "$format must decode").metadata.format,
            )
        }
        val tall = assertIs<PageInspection.Valid>(inspector.inspect(AvifTestFixtures.tall())).metadata
        assertEquals(TALL_AVIF_WIDTH, tall.width)
        assertEquals(TALL_AVIF_HEIGHT, tall.height)
    }

    @Test
    fun htmlTruncationPngCrcAndCrcCorrectBrokenPixelsNeverValidate() {
        val invalid =
            listOf(
                PageMediaTestImages.html(),
                byteArrayOf(),
                PageMediaTestImages.png().dropLast(1).toByteArray(),
                PageMediaTestImages.badPngCrc(),
                PageMediaTestImages.corruptPngPixels(),
                encoded(Bitmap.CompressFormat.JPEG).dropLast(JPEG_TRUNCATED_TAIL_BYTES).toByteArray(),
                AvifTestFixtures.regular().dropLast(AVIF_TRUNCATED_TAIL_BYTES).toByteArray(),
            )
        invalid.forEach { assertFalse(inspector.inspect(it) is PageInspection.Valid) }
    }

    @Test
    fun injectedBytePixelAndAxisLimitsRefuseBeforeLargeSamples() {
        val bytes = AndroidPageMediaInspector(PageInspectionPolicy(bytePolicy = PageBytePolicy(1)))
        assertEquals(
            PageInspectionRejection.ENCODED_BYTES,
            assertIs<PageInspection.Rejected>(bytes.inspect(PageMediaTestImages.png())).reason,
        )
        val pixels = AndroidPageMediaInspector(PageInspectionPolicy(maxSourcePixels = 71))
        assertEquals(
            PageInspectionRejection.SOURCE_PIXELS,
            assertIs<PageInspection.Rejected>(pixels.inspect(PageMediaTestImages.png())).reason,
        )
        val axes = AndroidPageMediaInspector(PageInspectionPolicy(maxSourceDimension = 8))
        assertEquals(
            PageInspectionRejection.SOURCE_AXIS,
            assertIs<PageInspection.Rejected>(axes.inspect(PageMediaTestImages.png())).reason,
        )
    }

    @Test
    fun mappedFileUsesItsBytesNotItsNameAndReadFailureIsNotInvalidContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = (context.cacheDir.absolutePath + "/page-${Random.nextLong().toULong()}.jpg").toPath()
        try {
            FileSystem.SYSTEM.write(path) { write(PageMediaTestImages.png()) }
            assertEquals(PageImageFormat.PNG, assertIs<PageInspection.Valid>(inspector.inspect(path)).metadata.format)
            FileSystem.SYSTEM.write(path) { write(PageMediaTestImages.html()) }
            assertFalse(inspector.inspect(path) is PageInspection.Valid)
            FileSystem.SYSTEM.delete(path)
            assertIs<PageInspection.ReadFailure>(inspector.inspect(path))
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    @Suppress("DEPRECATION")
    private fun encoded(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(ENCODED_WIDTH, ENCODED_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff204060.toInt())
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(format, ENCODING_QUALITY, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TALL_AVIF_WIDTH = 32
        const val TALL_AVIF_HEIGHT = 352
        const val JPEG_TRUNCATED_TAIL_BYTES = 8
        const val AVIF_TRUNCATED_TAIL_BYTES = 32
        const val ENCODED_WIDTH = 8
        const val ENCODED_HEIGHT = 9
        const val ENCODING_QUALITY = 90
    }
}
