package me.manga.kira.platform.media

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.io.InputStream
import java.nio.ByteBuffer

@TargetApi(Build.VERSION_CODES.P)
internal fun inspectAndroidImageDecoderPage(
    encoded: ByteBuffer,
    expected: PageImageFormat,
    policy: PageInspectionPolicy,
): PageInspection {
    var metadata: PageImageMetadata? = null
    return try {
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(encoded.asReadOnlyBuffer())) { decoder, info, _ ->
                val actual = androidPageFormat(info.mimeType) ?: throw PageProbeAbort(invalidPage())
                if (actual != expected || info.size.width <= 0 || info.size.height <= 0) throw PageProbeAbort(invalidPage())
                val native = PageImageMetadata(actual, info.size.width, info.size.height)
                policy.rejectionFor(native)?.let { throw PageProbeAbort(it) }
                metadata = native
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
                val (width, height) = pageSampleDimensions(native, policy.sampleMaxDimension)
                decoder.setTargetSize(width, height)
            }
        try {
            val native = metadata
            if (native == null || !validSample(bitmap, policy.sampleMaxDimension)) invalidPage() else PageInspection.Valid(native)
        } finally {
            bitmap.recycle()
        }
    } catch (aborted: PageProbeAbort) {
        aborted.result
    } catch (_: ImageDecoder.DecodeException) {
        invalidPage()
    } catch (_: IllegalArgumentException) {
        invalidPage()
    }
}

/**
 * API 26/27 have no strict partial-image callback. Framing/PNG CRC + a real sampled decode reject
 * ordinary truncation/corruption, but BitmapFactory can tolerate some damaged JPEG entropy. This
 * is not a claim of strict all-entropy validation on those releases; device fixtures remain a gate.
 */
internal fun inspectAndroidBitmapFactoryPage(
    encoded: ByteBuffer,
    expected: PageImageFormat,
    policy: PageInspectionPolicy,
): PageInspection {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(PageBufferInputStream(encoded), null, bounds)
    val format = androidPageFormat(bounds.outMimeType) ?: return invalidPage()
    if (format != expected || bounds.outWidth <= 0 || bounds.outHeight <= 0) return invalidPage()
    val metadata = PageImageMetadata(format, bounds.outWidth, bounds.outHeight)
    policy.rejectionFor(metadata)?.let { return it }
    val options =
        BitmapFactory.Options().apply {
            inSampleSize = pagePowerOfTwoSample(metadata, policy.sampleMaxDimension)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
    val sample = BitmapFactory.decodeStream(PageBufferInputStream(encoded), null, options) ?: return invalidPage()
    return try {
        if (validSample(sample, policy.sampleMaxDimension)) PageInspection.Valid(metadata) else invalidPage()
    } finally {
        sample.recycle()
    }
}

internal fun pageSampleDimensions(
    metadata: PageImageMetadata,
    maxEdge: Int,
): Pair<Int, Int> {
    val longest = maxOf(metadata.width, metadata.height)
    val edge = minOf(longest, maxEdge)
    return maxOf(1, (metadata.width.toLong() * edge / longest).toInt()) to
        maxOf(1, (metadata.height.toLong() * edge / longest).toInt())
}

private fun pagePowerOfTwoSample(
    metadata: PageImageMetadata,
    maxEdge: Int,
): Int {
    var sample = 1
    val longest = maxOf(metadata.width, metadata.height).toLong()
    while ((longest + sample - 1) / sample > maxEdge) sample *= 2
    return sample
}

private fun validSample(
    bitmap: Bitmap,
    edge: Int,
): Boolean = bitmap.width in 1..edge && bitmap.height in 1..edge && bitmap.allocationByteCount in 1..MAX_SAMPLE_BYTES

private fun androidPageFormat(mime: String?): PageImageFormat? =
    when (mime?.lowercase()) {
        "image/jpeg" -> PageImageFormat.JPEG
        "image/png" -> PageImageFormat.PNG
        "image/webp" -> PageImageFormat.WEBP
        "image/gif" -> PageImageFormat.GIF
        "image/bmp", "image/x-ms-bmp" -> PageImageFormat.BMP
        else -> null
    }

private class PageProbeAbort(
    val result: PageInspection,
) : RuntimeException()

private class PageBufferInputStream(
    encoded: ByteBuffer,
) : InputStream() {
    private val cursor = encoded.asReadOnlyBuffer()

    override fun read(): Int = if (cursor.hasRemaining()) cursor.get().toInt() and 0xff else -1

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (!cursor.hasRemaining()) return -1
        val count = minOf(length, cursor.remaining())
        cursor.get(bytes, offset, count)
        return count
    }
}

private const val MAX_SAMPLE_BYTES: Int = 128 * 1024
