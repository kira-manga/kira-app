package me.manga.kira.platform.media

import okio.FileSystem
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Executes actual Skia metadata and bounded readPixels, not a magic-byte fake. JVM test-host only. */
class DesktopPageMediaInspectorTest {
    private val inspector = DesktopPageMediaInspector()

    @Test
    fun pngJpegWebpGifAndBmpAreValidatedByTheNativeCodecWithActualFormats() {
        val images =
            mapOf(
                PageImageFormat.PNG to PageMediaTestImages.png(),
                PageImageFormat.JPEG to encoded(EncodedImageFormat.JPEG),
                PageImageFormat.WEBP to encoded(EncodedImageFormat.WEBP),
                PageImageFormat.GIF to PageMediaTestImages.gif(),
                PageImageFormat.BMP to PageMediaTestImages.bmp(),
            )
        for ((format, bytes) in images) {
            val valid = assertIs<PageInspection.Valid>(inspector.inspect(bytes), "$format must decode")
            assertEquals(format, valid.metadata.format)
        }
    }

    @Test
    fun htmlTruncationPngCrcAndCorruptPixelStreamNeverValidate() {
        val invalid =
            listOf(
                PageMediaTestImages.html(),
                byteArrayOf(),
                PageMediaTestImages.png().dropLast(1).toByteArray(),
                PageMediaTestImages.badPngCrc(),
                PageMediaTestImages.corruptPngPixels(),
                encoded(EncodedImageFormat.JPEG).dropLast(JPEG_TRUNCATED_TAIL_BYTES).toByteArray(),
            )
        invalid.forEach { assertFalse(inspector.inspect(it) is PageInspection.Valid) }
    }

    @Test
    fun byteAndDimensionPoliciesRejectBeforeAnyLargeDestination() {
        val byteLimited = DesktopPageMediaInspector(PageInspectionPolicy(bytePolicy = PageBytePolicy(1)))
        assertEquals(
            PageInspectionRejection.ENCODED_BYTES,
            assertIs<PageInspection.Rejected>(byteLimited.inspect(PageMediaTestImages.png())).reason,
        )
        val axisLimited = DesktopPageMediaInspector(PageInspectionPolicy(maxSourceDimension = 8))
        assertEquals(
            PageInspectionRejection.SOURCE_AXIS,
            assertIs<PageInspection.Rejected>(axisLimited.inspect(PageMediaTestImages.png())).reason,
        )
        val pixelLimited = DesktopPageMediaInspector(PageInspectionPolicy(maxSourcePixels = 71))
        assertEquals(
            PageInspectionRejection.SOURCE_PIXELS,
            assertIs<PageInspection.Rejected>(pixelLimited.inspect(PageMediaTestImages.png())).reason,
        )
    }

    @Test
    fun fileExtensionCannotOverrideActualBytesAndMissingFileIsAReadFailure() {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "media-${Random.nextLong().toULong()}.jpg"
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

    private fun encoded(format: EncodedImageFormat): ByteArray =
        Image.makeFromEncoded(PageMediaTestImages.png()).use { image ->
            requireNotNull(image.encodeToData(format, ENCODING_QUALITY)).use { it.bytes }
        }

    private companion object {
        const val JPEG_TRUNCATED_TAIL_BYTES = 8
        const val ENCODING_QUALITY = 90
    }
}
