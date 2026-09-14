package me.manga.kira.platform.media

import kotlinx.coroutines.CancellationException
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.EOFException
import okio.IOException
import okio.Source
import okio.buffer
import okio.use

/** Framing is necessary but NEVER sufficient: only the native adapter can return Valid. */
internal fun inspectPageInput(
    policy: PageInspectionPolicy,
    size: Long,
    source: () -> Source,
    decode: (PageImageFormat) -> PageInspection,
): PageInspection {
    encodedPageRejection(size, policy)?.let { return it }
    return try {
        val format = source().buffer().use { inspectPageFraming(it, size) }
        val result = decode(format)
        if (result is PageInspection.Valid && result.metadata.format != format) invalidPage() else result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: PageFramingException) {
        PageInspection.Invalid(failure.reason)
    } catch (_: EOFException) {
        invalidPage()
    } catch (failure: IOException) {
        PageInspection.ReadFailure(failure)
    }
}

internal fun encodedPageRejection(
    size: Long,
    policy: PageInspectionPolicy,
): PageInspection? =
    when {
        size <= 0 -> PageInspection.Invalid(PageInvalidReason.EMPTY)
        size > policy.bytePolicy.maxEncodedBytes ->
            PageInspection.Rejected(PageInspectionRejection.ENCODED_BYTES, policy.bytePolicy.maxEncodedBytes, size)
        else -> null
    }

private fun inspectPageFraming(
    source: BufferedSource,
    size: Long,
): PageImageFormat =
    when {
        source.rangeEquals(0, JPEG_START) -> inspectJpegFraming(source, size)
        source.rangeEquals(0, PNG_START) -> {
            inspectPngFraming(source, size)
            PageImageFormat.PNG
        }
        source.rangeEquals(0, "RIFF".encodeUtf8()) && source.rangeEquals(8, "WEBP".encodeUtf8()) -> {
            inspectWebpFraming(source, size)
            PageImageFormat.WEBP
        }
        source.rangeEquals(0, "GIF87a".encodeUtf8()) || source.rangeEquals(0, "GIF89a".encodeUtf8()) -> inspectGifFraming(source, size)
        source.rangeEquals(0, "BM".encodeUtf8()) -> inspectBmpFraming(source, size)
        source.rangeEquals(4, "ftyp".encodeUtf8()) -> {
            inspectAvifFraming(source, size)
            PageImageFormat.AVIF
        }
        else -> throw PageFramingException(PageInvalidReason.UNSUPPORTED_FORMAT)
    }

private fun inspectJpegFraming(
    source: BufferedSource,
    size: Long,
): PageImageFormat {
    requirePageFraming(size >= 4)
    source.skip(size - 2)
    requirePageFraming(source.readByteString(2) == JPEG_END)
    return PageImageFormat.JPEG
}

private fun inspectGifFraming(
    source: BufferedSource,
    size: Long,
): PageImageFormat {
    requirePageFraming(size >= 14)
    source.skip(size - 1)
    requirePageFraming(source.readByte().toInt() == 0x3b)
    return PageImageFormat.GIF
}

private fun inspectBmpFraming(
    source: BufferedSource,
    size: Long,
): PageImageFormat {
    requirePageFraming(size >= 26)
    source.skip(2)
    requirePageFraming(source.readUnsignedIntLe() == size)
    source.skip(4)
    val pixelsAt = source.readUnsignedIntLe()
    requirePageFraming(pixelsAt in 14 until size)
    source.skip(size - 14)
    return PageImageFormat.BMP
}

internal fun invalidPage(): PageInspection.Invalid = PageInspection.Invalid(PageInvalidReason.INCOMPLETE_OR_CORRUPT)

internal fun requirePageFraming(valid: Boolean) {
    if (!valid) throw PageFramingException(PageInvalidReason.INCOMPLETE_OR_CORRUPT)
}

internal class PageFramingException(
    val reason: PageInvalidReason,
) : Exception()

internal fun BufferedSource.readUnsignedInt(): Long = readInt().toLong() and 0xffff_ffffL

internal fun BufferedSource.readUnsignedIntLe(): Long = readIntLe().toLong() and 0xffff_ffffL

private val JPEG_START = "ffd8".decodeHex()
private val JPEG_END = "ffd9".decodeHex()
internal val PNG_START = "89504e470d0a1a0a".decodeHex()
