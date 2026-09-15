package me.manga.kira.platform.media

import me.manga.kira.platform.image.AvifTestFixtures
import okio.FileSystem
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Real ImageIO metadata/status + bounded thumbnails; does not establish a device RSS ceiling. */
class IosPageMediaInspectorTest {
    private val inspector = IosPageMediaInspector()

    @Test
    fun pngJpegWebpGifAndBmpUseRealImageIoValidation() {
        val images =
            mapOf(
                PageImageFormat.PNG to PageMediaTestImages.png(),
                PageImageFormat.JPEG to encoded(EncodedImageFormat.JPEG),
                PageImageFormat.WEBP to encoded(EncodedImageFormat.WEBP),
                PageImageFormat.GIF to PageMediaTestImages.gif(),
                PageImageFormat.BMP to PageMediaTestImages.bmp(),
            )
        for ((format, bytes) in images) {
            assertEquals(
                format,
                assertIs<PageInspection.Valid>(inspector.inspect(bytes), "$format must decode").metadata.format,
            )
        }
    }

    @Test
    fun avifIsNativelyValidatedOrReportsTheExplicitOsCodecCapabilityGap() {
        val result = inspector.inspect(AvifTestFixtures.regular())
        if (result is PageInspection.Rejected) {
            // Older ImageIO versions do not support AVIF. This branch is NOT AVIF validation proof.
            assertEquals(PageInspectionRejection.DECODER_UNAVAILABLE, result.reason)
        } else {
            val metadata = assertIs<PageInspection.Valid>(result).metadata
            assertEquals(PageImageFormat.AVIF, metadata.format)
            assertEquals(320, metadata.width)
            assertEquals(640, metadata.height)
        }
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
                encoded(EncodedImageFormat.JPEG).dropLast(8).toByteArray(),
                AvifTestFixtures.regular().dropLast(32).toByteArray(),
            )
        invalid.forEach { assertFalse(inspector.inspect(it) is PageInspection.Valid) }
    }

    @Test
    fun injectedBytePixelAndAxisLimitsStayDistinct() {
        val bytes = IosPageMediaInspector(PageInspectionPolicy(bytePolicy = PageBytePolicy(1)))
        assertEquals(
            PageInspectionRejection.ENCODED_BYTES,
            assertIs<PageInspection.Rejected>(bytes.inspect(PageMediaTestImages.png())).reason,
        )
        val pixels = IosPageMediaInspector(PageInspectionPolicy(maxSourcePixels = 71))
        assertEquals(
            PageInspectionRejection.SOURCE_PIXELS,
            assertIs<PageInspection.Rejected>(pixels.inspect(PageMediaTestImages.png())).reason,
        )
        val axes = IosPageMediaInspector(PageInspectionPolicy(maxSourceDimension = 8))
        assertEquals(
            PageInspectionRejection.SOURCE_AXIS,
            assertIs<PageInspection.Rejected>(axes.inspect(PageMediaTestImages.png())).reason,
        )
    }

    @Test
    fun mappedFileValidationIgnoresItsNameAndMissingFilesReportReadFailure() {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "page-media-${Random.nextLong().toULong()}.jpg"
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
            requireNotNull(image.encodeToData(format, 90)).use { it.bytes }
        }
}
