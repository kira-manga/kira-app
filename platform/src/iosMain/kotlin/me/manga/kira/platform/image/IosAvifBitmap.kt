package me.manga.kira.platform.image

import coil3.asImage
import coil3.decode.DecodeResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.ensureActive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGInterpolationQuality
import platform.CoreGraphics.CGRectMake
import kotlin.coroutines.CoroutineContext

/** The allocation was checked before thumbnail creation; no unchecked Int product reaches a buffer. */
@OptIn(ExperimentalForeignApi::class)
internal fun drawIosAvifBitmap(
    thumbnail: CGImageRef,
    output: AvifPixelSize,
    allocation: AvifOutputAllocation,
    isSampled: Boolean,
    context: CoroutineContext,
): DecodeResult {
    val pixels = ByteArray(allocation.byteCount)
    val colorSpace =
        CGColorSpaceCreateDeviceRGB()
            ?: throw AvifDecodeException("Unable to create the AVIF output color space.")
    try {
        drawAvifPixels(thumbnail, output, allocation.rowBytes, pixels, colorSpace)
    } finally {
        CGColorSpaceRelease(colorSpace)
    }
    return installAvifPixels(pixels, output, allocation.rowBytes, isSampled, context)
}

private fun installAvifPixels(
    pixels: ByteArray,
    output: AvifPixelSize,
    rowBytes: Int,
    isSampled: Boolean,
    context: CoroutineContext,
): DecodeResult {
    context.ensureActive()
    val bitmap = Bitmap()
    var handedOff = false
    try {
        val info = ImageInfo(output.width, output.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        if (!bitmap.installPixels(info, pixels, rowBytes)) {
            throw AvifDecodeException("Unable to install the AVIF output pixels.")
        }
        bitmap.setImmutable()
        context.ensureActive()
        val result = DecodeResult(bitmap.asImage(), isSampled)
        handedOff = true
        return result
    } finally {
        if (!handedOff) bitmap.close()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun drawAvifPixels(
    image: CGImageRef,
    output: AvifPixelSize,
    rowBytes: Int,
    pixels: ByteArray,
    colorSpace: CGColorSpaceRef,
) {
    pixels.usePinned { pinned ->
        val context =
            CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = output.width.toULong(),
                height = output.height.toULong(),
                bitsPerComponent = BITS_PER_CHANNEL,
                bytesPerRow = rowBytes.toULong(),
                space = colorSpace,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            ) ?: throw AvifDecodeException("Unable to create the bounded AVIF output context.")
        try {
            CGContextSetInterpolationQuality(context, CGInterpolationQuality.kCGInterpolationHigh)
            CGContextDrawImage(
                context,
                CGRectMake(0.0, 0.0, output.width.toDouble(), output.height.toDouble()),
                image,
            )
        } finally {
            CGContextRelease(context)
        }
    }
}

private const val BITS_PER_CHANNEL: ULong = 8u
