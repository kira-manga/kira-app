package me.manga.kira.platform.cbz

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.readBytes
import libwebp.WebPEncodeRGBA
import libwebp.WebPFree
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceRef

/**
 * Thin native-call seam: production always uses ImageIO/CoreGraphics/libwebp. Tests can observe the
 * actual full-decode/context boundary and inject a late native failure without replacing the writer,
 * media inspection, admission, ZIP IO, or the earlier successful native bands.
 */
@OptIn(ExperimentalForeignApi::class)
internal open class IosCbzNativeCodec {
    open fun decode(source: CGImageSourceRef): CGImageRef? = CGImageSourceCreateImageAtIndex(source, 0uL, null)

    open fun createContext(plan: CbzTranscodePlan, colorSpace: CGColorSpaceRef): CGContextRef? =
        CGBitmapContextCreate(
            data = null,
            width = plan.width.toULong(),
            height = plan.height.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = plan.rgbaRowBytes.toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
        )

    open fun encodeBand(
        rgba: CPointer<UByteVar>,
        width: Int,
        height: Int,
        stride: Int,
        quality: Int,
        output: CPointer<CPointerVar<UByteVar>>,
    ): ULong = WebPEncodeRGBA(rgba, width, height, stride, quality.toFloat(), output)

    open fun copyEncoded(pointer: CPointer<UByteVar>, size: Int): ByteArray = pointer.readBytes(size)

    open fun freeEncoded(pointer: CPointer<UByteVar>) = WebPFree(pointer)

    open fun releaseImage(image: CGImageRef) = CGImageRelease(image)

    open fun releaseContext(context: CGContextRef) = CGContextRelease(context)
}
