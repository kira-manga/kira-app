package me.manga.kira.platform.media

import android.graphics.Bitmap
import me.manga.kira.platform.image.AvifDecodeException
import me.manga.kira.platform.image.AvifDecodeLimits
import me.manga.kira.platform.image.AvifPixelSize
import me.manga.kira.platform.image.androidAvifNativePixelLimit
import me.manga.kira.platform.image.withAndroidAvifNativePermit
import org.aomedia.avif.android.AvifDecoder
import java.nio.ByteBuffer

/**
 * Requires the qualified bounded-API producer, NOT the old Maven AVIF artifact. The small output
 * bitmap alone does not limit AV1 memory: decoder/dav1d pixel limits apply before Parse/NextImage.
 * The shared allowance is an estimate, not an aggregate native allocator/RSS guarantee; the
 * decoder's per-axis limit covers container dimensions, not a proven actual AV1 per-axis cap.
 */
internal fun inspectAndroidAvifPage(
    encoded: ByteBuffer,
    policy: PageInspectionPolicy,
): PageInspection =
    withAndroidAvifNativePermit {
        try {
            probeAndroidAvif(encoded, policy)
        } catch (_: AvifDecodeException) {
            PageInspection.Rejected(PageInspectionRejection.BOUNDED_DECODER_REJECTED)
        }
    }

private fun probeAndroidAvif(
    encoded: ByteBuffer,
    policy: PageInspectionPolicy,
): PageInspection {
    val limits =
        AvifDecodeLimits(
            maxEncodedBytes = policy.bytePolicy.maxEncodedBytes.toInt(),
            maxSourcePixels = policy.maxSourcePixels,
            maxSourceDimension = policy.maxSourceDimension,
            maxOutputBytes = 128L * 1024,
        )
    val length = encoded.remaining()
    val headerPixelLimit = androidAvifNativePixelLimit(limits, length)
    val direct =
        if (encoded.isDirect) {
            encoded.asReadOnlyBuffer()
        } else {
            ByteBuffer.allocateDirect(length).apply {
                put(encoded.asReadOnlyBuffer())
                flip()
            }
        }
    require(direct.position() == 0 && length > 0 && length <= direct.remaining())
    val info = AvifDecoder.Info()
    if (!AvifDecoder.getInfoWithLimits(direct, length, info, headerPixelLimit, limits.maxSourceDimension)) {
        // A false result can mean malformed input OR the pre-parse native policy refused it.
        return PageInspection.Rejected(PageInspectionRejection.BOUNDED_DECODER_REJECTED)
    }
    return inspectAvifInfo(direct, length, info, policy, limits)
}

private fun inspectAvifInfo(
    direct: ByteBuffer,
    length: Int,
    info: AvifDecoder.Info,
    policy: PageInspectionPolicy,
    limits: AvifDecodeLimits,
): PageInspection {
    if (info.width <= 0 || info.height <= 0) return invalidPage()
    val metadata = PageImageMetadata(PageImageFormat.AVIF, info.width, info.height)
    return policy.rejectionFor(metadata) ?: inspectAvifSample(direct, length, metadata, policy, limits)
}

private fun inspectAvifSample(
    direct: ByteBuffer,
    length: Int,
    metadata: PageImageMetadata,
    policy: PageInspectionPolicy,
    limits: AvifDecodeLimits,
): PageInspection {
    val (width, height) = pageSampleDimensions(metadata, policy.sampleMaxDimension)
    val pixelLimit = androidAvifNativePixelLimit(limits, length, AvifPixelSize(width, height))
    if (metadata.pixelCount > pixelLimit) {
        return PageInspection.Rejected(
            PageInspectionRejection.BOUNDED_DECODER_REJECTED,
            pixelLimit.toLong(),
            metadata.pixelCount,
        )
    }
    val sample = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return try {
        direct.rewind()
        if (AvifDecoder.decodeWithLimits(direct, length, sample, 1, pixelLimit, limits.maxSourceDimension)) {
            PageInspection.Valid(metadata)
        } else {
            PageInspection.Rejected(PageInspectionRejection.BOUNDED_DECODER_REJECTED)
        }
    } finally {
        sample.recycle()
    }
}
