package me.manga.kira.platform.image

import coil3.decode.DecodeUtils
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.util.component1
import coil3.util.component2

internal data class AvifPixelSize(
    val width: Int,
    val height: Int,
) {
    init {
        if (width <= 0 || height <= 0) throw AvifDecodeException("Invalid AVIF dimensions.")
    }

    // A product of two positive Ints fits in Long, including malformed maximum-sized metadata.
    val pixels: Long get() = width.toLong() * height

    fun isSmallerThan(source: AvifPixelSize): Boolean = width < source.width || height < source.height
}

/** Mirror Coil's two-axis sizing, including an undefined axis and independent bitmap caps. */
internal fun avifDecodeSize(
    source: AvifPixelSize,
    target: Size,
    scale: Scale,
    precision: Precision,
    maxBitmapSize: Size,
): AvifPixelSize {
    val (width, height) =
        DecodeUtils.computeDstSize(
            srcWidth = source.width,
            srcHeight = source.height,
            targetSize = target,
            scale = scale,
            maxSize = maxBitmapSize,
        )
    var multiplier =
        DecodeUtils.computeSizeMultiplier(
            srcWidth = source.width,
            srcHeight = source.height,
            dstWidth = width,
            dstHeight = height,
            scale = scale,
            maxSize = maxBitmapSize,
        )
    if (precision == Precision.INEXACT) multiplier = multiplier.coerceAtMost(1.0)
    return AvifPixelSize(
        width = (source.width * multiplier).toInt().coerceAtLeast(1),
        height = (source.height * multiplier).toInt().coerceAtLeast(1),
    )
}
