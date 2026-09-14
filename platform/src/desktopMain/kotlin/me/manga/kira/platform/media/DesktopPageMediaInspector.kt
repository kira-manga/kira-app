package me.manga.kira.platform.media

import kotlinx.coroutines.CancellationException
import okio.FileSystem
import okio.IOException
import okio.Path
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.makeFromFileName

/**
 * Minimal JVM test-host adapter. Only codecs able to fill a bounded destination can validate a
 * page here; this is not Desktop feature qualification or a reason to full-decode large images.
 */
class DesktopPageMediaInspector(
    private val policy: PageInspectionPolicy = PageInspectionPolicy(),
    private val system: FileSystem = FileSystem.SYSTEM,
) : PageMediaInspector {
    override fun inspect(encoded: ByteArray): PageInspection {
        encodedPageRejection(encoded.size.toLong(), policy)?.let { return it }
        return Data.makeFromBytes(encoded).use(::inspectSnapshot)
    }

    override fun inspect(path: Path): PageInspection =
        try {
            val metadata = system.metadata(path)
            if (!metadata.isRegularFile) throw IOException("Page is not a regular file")
            val size = metadata.size ?: throw IOException("Page size is unavailable")
            // SkData owns this native file snapshot. Neither framing nor the decoder reopens the path.
            encodedPageRejection(size, policy) ?: Data.makeFromFileName(path.toString()).use(::inspectSnapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            PageInspection.ReadFailure(failure)
        } catch (failure: IllegalArgumentException) {
            PageInspection.ReadFailure(failure)
        }

    private fun inspectSnapshot(data: Data): PageInspection =
        inspectPageInput(policy, data.size.toLong(), { DesktopPageDataSource(data) }) { format ->
            if (format == PageImageFormat.AVIF) {
                PageInspection.Rejected(PageInspectionRejection.DECODER_UNAVAILABLE)
            } else {
                inspectCodec(data, format)
            }
        }

    private fun inspectCodec(
        data: Data,
        expected: PageImageFormat,
    ): PageInspection =
        try {
            Codec.makeFromData(data).use { codec ->
                if (codec.encodedImageFormat != expected.skiaFormat() || codec.width <= 0 || codec.height <= 0) {
                    return invalidPage()
                }
                val metadata = PageImageMetadata(expected, codec.width, codec.height)
                policy.rejectionFor(metadata) ?: inspectCodecSample(codec, metadata)
            }
        } catch (_: IllegalArgumentException) {
            invalidPage()
        } catch (_: UnsupportedOperationException) {
            PageInspection.Rejected(PageInspectionRejection.DECODER_UNAVAILABLE)
        }

    private fun inspectCodecSample(
        codec: Codec,
        metadata: PageImageMetadata,
    ): PageInspection {
        val longest = maxOf(codec.width, codec.height)
        val edge = minOf(longest, policy.sampleMaxDimension)
        val width = maxOf(1, (codec.width.toLong() * edge / longest).toInt())
        val height = maxOf(1, (codec.height.toLong() * edge / longest).toInt())
        return Bitmap().use { sample ->
            if (!sample.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))) {
                throw IOException("Could not allocate bounded page sample")
            }
            // Unlike Image.makeFromEncoded + drawing, this requests a small codec destination.
            // Skia's readPixels checks native result status, including partial/error input.
            try {
                codec.readPixels(sample)
                PageInspection.Valid(metadata)
            } catch (_: IllegalArgumentException) {
                // The bridge reports incomplete input and unsupported scaling with the same
                // exception type. Neither establishes readable bytes; no full-size retry.
                PageInspection.Rejected(PageInspectionRejection.BOUNDED_DECODER_REJECTED)
            }
        }
    }
}

private fun PageImageFormat.skiaFormat(): EncodedImageFormat? =
    when (this) {
        PageImageFormat.JPEG -> EncodedImageFormat.JPEG
        PageImageFormat.PNG -> EncodedImageFormat.PNG
        PageImageFormat.WEBP -> EncodedImageFormat.WEBP
        PageImageFormat.GIF -> EncodedImageFormat.GIF
        PageImageFormat.BMP -> EncodedImageFormat.BMP
        PageImageFormat.AVIF -> null
    }
