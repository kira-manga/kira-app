package me.manga.kira.platform.cbz

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.platform.media.PageImageFormat
import okio.IOException
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.impl.use as skiaUse

/** Strict, admitted streaming codec for the compiled iOS rollback path; no Desktop behavior change. */
internal suspend fun streamValidatedSkiaPage(
    page: ValidatedCbzPage,
    options: CbzEncodingOptions,
    decode: (ByteArray) -> Image,
    emit: suspend (ByteArray) -> Unit,
): CbzPageEncoding {
    currentCoroutineContext().ensureActive()
    return when (val admission = options.admit(page)) {
        CbzTranscodeAdmission.PreserveBudget -> CbzPageEncoding.PreserveOriginal(CbzPreservationReason.MEMORY_BUDGET)
        CbzTranscodeAdmission.PreserveWebpDimensions ->
            CbzPageEncoding.PreserveOriginal(CbzPreservationReason.WEBP_DIMENSIONS)
        is CbzTranscodeAdmission.Admitted ->
            // Skiko's bundled Skia has no AVIF codec. Only the iOS native inspector's prior VALID
            // result permits this preservation; an arbitrary Skia failure does not.
            if (page.metadata.format == PageImageFormat.AVIF) {
                CbzPageEncoding.PreserveOriginal(CbzPreservationReason.UNSUPPORTED_TRANSCODE)
            } else {
                streamAdmittedSkiaPage(page.bytes, admission.plan, options.quality, decode, emit)
            }
    }
}

private suspend fun streamAdmittedSkiaPage(
    source: ByteArray,
    plan: CbzTranscodePlan,
    quality: Int,
    decode: (ByteArray) -> Image,
    emit: suspend (ByteArray) -> Unit,
): CbzPageEncoding.Encoded {
    val image = decode(source)
    try {
        if (image.width != plan.width || image.height != plan.height) {
            throw IOException("CBZ Skia dimensions differ from validated metadata")
        }
        var top = 0
        var count = 0
        while (top < plan.height) {
            currentCoroutineContext().ensureActive()
            val band = SkiaBandRegion(top, minOf(plan.bandHeight, plan.height - top))
            emitOneSkiaBand(image, plan, band, quality, emit)
            currentCoroutineContext().ensureActive()
            count++
            top += band.height
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
    band: SkiaBandRegion,
    quality: Int,
    emit: suspend (ByteArray) -> Unit,
) {
    val encoded = encodeSkiaBand(source, plan, band, quality)
    currentCoroutineContext().ensureActive()
    emit(encoded)
}

/** All per-band native objects close before the caller receives this one bounded encoded array. */
private fun encodeSkiaBand(
    source: Image,
    plan: CbzTranscodePlan,
    band: SkiaBandRegion,
    quality: Int,
): ByteArray {
    val bitmap = Bitmap()
    try {
        allocateAdmittedSkiaBand(bitmap, plan, band)
        Canvas(bitmap).skiaUse { canvas ->
            canvas.drawImageRect(
                source,
                Rect.makeXYWH(0f, band.top.toFloat(), plan.width.toFloat(), band.height.toFloat()),
                Rect.makeWH(plan.width.toFloat(), band.height.toFloat()),
            )
        }
        bitmap.setImmutable()
        return encodeBoundedSkiaBitmap(bitmap, quality, plan.maxEncodedBandBytes)
    } finally {
        bitmap.close()
    }
}

private fun allocateAdmittedSkiaBand(
    bitmap: Bitmap,
    plan: CbzTranscodePlan,
    band: SkiaBandRegion,
) {
    if (!bitmap.allocN32Pixels(plan.width, band.height)) throw IOException("CBZ Skia band allocation failed")
    if (bitmap.width != plan.width ||
        bitmap.height != band.height ||
        bitmap.rowBytes.toLong() > plan.width.toLong() * BAND_BITMAP_BYTES_PER_PIXEL
    ) {
        throw IOException("CBZ Skia band differs from its admitted dimensions or stride")
    }
}

private fun encodeBoundedSkiaBitmap(
    bitmap: Bitmap,
    quality: Int,
    maxEncodedBytes: Int,
): ByteArray =
    Image.makeFromBitmap(bitmap).skiaUse { image ->
        val data =
            image.encodeToData(EncodedImageFormat.WEBP, quality) ?: throw IOException("CBZ Skia WebP encode failed")
        data.skiaUse {
            if (it.size <= 0 || it.size > maxEncodedBytes) {
                throw IOException("CBZ Skia WebP output exceeds its admitted allowance")
            }
            it.bytes
        }
    }

private data class SkiaBandRegion(
    val top: Int,
    val height: Int,
)

private const val BAND_BITMAP_BYTES_PER_PIXEL = 8
