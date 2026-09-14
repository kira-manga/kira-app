package me.manga.kira.platform.cbz

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.platform.media.PageImageFormat
import me.manga.kira.platform.media.PageImageMetadata
import okio.IOException
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.impl.use as skiaUse

/** Strict, admitted streaming codec for the compiled iOS rollback path; no Desktop behavior change. */
internal suspend fun streamValidatedSkiaPage(
    source: ByteArray,
    metadata: PageImageMetadata,
    quality: Int,
    maxHeight: Int,
    maxMemoryBytes: Long,
    decode: (ByteArray) -> Image,
    emit: suspend (ByteArray) -> Unit,
): CbzPageEncoding {
    currentCoroutineContext().ensureActive()
    val admission = CbzTranscodeBudget.admit(metadata.width, metadata.height, source.size.toLong(), maxHeight, maxMemoryBytes)
    val plan =
        when (admission) {
            CbzTranscodeAdmission.PreserveBudget -> return CbzPageEncoding.PreserveOriginal(CbzPreservationReason.MEMORY_BUDGET)
            CbzTranscodeAdmission.PreserveWebpDimensions ->
                return CbzPageEncoding.PreserveOriginal(CbzPreservationReason.WEBP_DIMENSIONS)
            is CbzTranscodeAdmission.Admitted -> admission.plan
        }
    // Skiko's bundled Skia has no AVIF codec. Only the iOS native inspector's prior VALID result
    // permits this known transcode-capability preservation; an arbitrary Skia failure does not.
    if (metadata.format == PageImageFormat.AVIF) {
        return CbzPageEncoding.PreserveOriginal(CbzPreservationReason.UNSUPPORTED_TRANSCODE)
    }
    val image = decode(source)
    try {
        if (image.width != plan.width || image.height != plan.height) {
            throw IOException("CBZ Skia dimensions differ from validated metadata")
        }
        var top = 0
        var count = 0
        while (top < plan.height) {
            currentCoroutineContext().ensureActive()
            val height = minOf(plan.bandHeight, plan.height - top)
            emitOneSkiaBand(image, plan, top, height, quality, emit)
            currentCoroutineContext().ensureActive()
            count++
            top += height
        }
        return CbzPageEncoding.Encoded(count)
    } finally {
        image.close()
    }
}

/** The outer loop never retains this array in its suspension state while the next encode starts. */
private suspend fun emitOneSkiaBand(
    source: Image,
    plan: CbzTranscodePlan,
    top: Int,
    height: Int,
    quality: Int,
    emit: suspend (ByteArray) -> Unit,
) {
    val encoded = encodeSkiaBand(source, plan, top, height, quality)
    currentCoroutineContext().ensureActive()
    emit(encoded)
}

/** All per-band native objects close before the caller receives this one bounded encoded array. */
private fun encodeSkiaBand(
    source: Image,
    plan: CbzTranscodePlan,
    top: Int,
    height: Int,
    quality: Int,
): ByteArray {
    val bitmap = Bitmap()
    try {
        if (!bitmap.allocN32Pixels(plan.width, height)) throw IOException("CBZ Skia band allocation failed")
        if (bitmap.width != plan.width ||
            bitmap.height != height ||
            bitmap.rowBytes.toLong() > plan.width.toLong() * BAND_BITMAP_BYTES_PER_PIXEL
        ) {
            throw IOException("CBZ Skia band differs from its admitted dimensions or stride")
        }
        Canvas(bitmap).skiaUse { canvas ->
            canvas.drawImageRect(
                source,
                Rect.makeXYWH(0f, top.toFloat(), plan.width.toFloat(), height.toFloat()),
                Rect.makeWH(plan.width.toFloat(), height.toFloat()),
            )
        }
        bitmap.setImmutable()
        return Image.makeFromBitmap(bitmap).skiaUse { image ->
            val data = image.encodeToData(EncodedImageFormat.WEBP, quality) ?: throw IOException("CBZ Skia WebP encode failed")
            data.skiaUse {
                if (it.size <= 0 || it.size > plan.maxEncodedBandBytes) {
                    throw IOException("CBZ Skia WebP output exceeds its admitted allowance")
                }
                it.bytes
            }
        }
    } finally {
        bitmap.close()
    }
}

private const val BAND_BITMAP_BYTES_PER_PIXEL = 8
