package me.manga.kira.platform.cbz

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlin.time.TimeSource
import libwebp.WebPEncodeRGBA
import libwebp.WebPFree
import me.manga.kira.platform.download.BgDownloadLog
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData

/**
 * iOS-native WebP page encoder (Option K). The non-Android analogue of Android's
 * `Bitmap.compress(WEBP_LOSSY)` and the replacement for the skiko-based [SkiaWebpEncoder] on iOS.
 *
 * Why this exists: the Skia path forces ~190 MiB decoded webtoon-strip bitmaps + their interop objects
 * through the **Kotlin/Native heap**, whose stop-the-world GC then froze the UI for ~½ s during the
 * COMPRESSING stage. This encoder keeps the heavy pixel buffer in **CoreGraphics native memory** the whole
 * time — ImageIO decodes into a `CGBitmapContext`-owned RGBA buffer, libwebp's `WebPEncodeRGBA` reads band
 * slices of that buffer **by pointer** (no per-band copy), and only the small encoded WebP bytes
 * (~1–4 MiB/band) are ever copied into a Kotlin `ByteArray`. So the K/N heap never sees the big bitmap →
 * no GC stalls, mirroring Android's native pixel pipeline.
 *
 * Output stays **WebP** (cross-platform/sharing parity with Android, drop-in CBZ format). Banding is
 * preserved (≤ [maxHeight], ≤ WebP's 16383-px hard limit) so tall webtoon strips stay crisp. A page Skia
 * could decode but libwebp can't is signalled by a `null` return → [IosCbzWriter] stores it verbatim.
 *
 * Premultiplied-RGBA note: manga pages are opaque (alpha = 255), for which premultiplied RGBA is identical
 * to straight RGBA, so feeding the premultiplied buffer to `WebPEncodeRGBA` is colour-correct here.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosLibWebpEncoder {

    private const val RGBA = 4
    private const val WEBP_MAX_DIMENSION = 16383 // libwebp hard width/height limit

    /**
     * Decode [source] (any ImageIO-decodable format) and re-encode to one or more WebP bands at [quality]
     * (0..100), splitting taller-than-[maxHeight] images into vertical bands. Returns the WebP byte arrays
     * top-to-bottom, or `null` if the source can't be decoded (caller stores verbatim). [maxMemoryBytes] is
     * accepted for API parity with [SkiaWebpEncoder]; banding here is bounded by [maxHeight] + the WebP
     * dimension limit (bands share the one native buffer, so no per-band budget is needed).
     */
    fun encodeToWebpPages(
        source: ByteArray,
        quality: Int,
        maxHeight: Int,
        @Suppress("UNUSED_PARAMETER") maxMemoryBytes: Long,
    ): List<ByteArray>? {
        if (source.isEmpty()) return null
        val mark = TimeSource.Monotonic.markNow()
        val nsData: NSData = source.usePinned {
            NSData.create(bytes = it.addressOf(0), length = source.size.toULong())
        }
        val cfData: CFDataRef = CFBridgingRetain(nsData)?.reinterpret() ?: return null
        try {
            val cgSource = CGImageSourceCreateWithData(cfData, null) ?: return null
            try {
                val cgImage = CGImageSourceCreateImageAtIndex(cgSource, 0uL, null) ?: return null
                try {
                    return encodeImage(cgImage, quality, maxHeight, source.size, mark)
                } finally {
                    CGImageRelease(cgImage)
                }
            } finally {
                CFRelease(cgSource)
            }
        } finally {
            CFRelease(cfData)
        }
    }

    private fun encodeImage(
        cgImage: CGImageRef,
        quality: Int,
        maxHeight: Int,
        srcBytes: Int,
        mark: TimeSource.Monotonic.ValueTimeMark,
    ): List<ByteArray>? {
        val width = CGImageGetWidth(cgImage).toInt()
        val height = CGImageGetHeight(cgImage).toInt()
        if (width <= 0 || height <= 0) return null
        val rowBytes = width * RGBA

        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        try {
            // data = null → CoreGraphics owns the ~width*height*4 pixel buffer (NATIVE memory, not the K/N
            // heap). Freed when the context is released.
            val ctx = CGBitmapContextCreate(
                data = null,
                width = width.toULong(),
                height = height.toULong(),
                bitsPerComponent = 8u,
                bytesPerRow = rowBytes.toULong(),
                space = colorSpace,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            ) ?: return null
            try {
                CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), cgImage)
                val base: CPointer<UByteVar> = CGBitmapContextGetData(ctx)?.reinterpret() ?: return null
                val decodeMs = mark.elapsedNow().inWholeMilliseconds

                val bandHeight = effectiveBandHeight(maxHeight)
                val pages = ArrayList<ByteArray>()
                var top = 0
                while (top < height) {
                    val h = minOf(bandHeight, height - top)
                    val bandPtr = (base + (top.toLong() * rowBytes)) ?: return null
                    val webp = encodeBand(bandPtr, width, h, rowBytes, quality) ?: return null
                    pages.add(webp)
                    top += h
                }
                BgDownloadLog.dlperf(
                    "webpEncode",
                    "enc" to "libwebp",
                    "dims" to "${width}x$height",
                    "decodedMiB" to (width.toLong() * height * RGBA / (1024 * 1024)),
                    "bands" to pages.size,
                    "srcKiB" to (srcBytes / 1024),
                    "outKiB" to (pages.sumOf { it.size } / 1024),
                    "decodeMs" to decodeMs,
                    "totalMs" to mark.elapsedNow().inWholeMilliseconds,
                    "q" to quality,
                )
                return pages
            } finally {
                CGContextRelease(ctx)
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
    }

    /** libwebp-encode one band read directly from the native RGBA buffer at [rgba]; copies the encoded
     *  output into a Kotlin [ByteArray] and frees libwebp's buffer. */
    private fun encodeBand(rgba: CPointer<UByteVar>, width: Int, height: Int, stride: Int, quality: Int): ByteArray? =
        memScoped {
            val out = alloc<CPointerVar<UByteVar>>()
            val size = WebPEncodeRGBA(rgba, width, height, stride, quality.toFloat(), out.ptr)
            val ptr = out.value
            if (size.toLong() <= 0L || ptr == null) {
                if (ptr != null) WebPFree(ptr)
                return@memScoped null
            }
            val bytes = ptr.readBytes(size.toInt())
            WebPFree(ptr)
            bytes
        }

    private fun effectiveBandHeight(maxHeight: Int): Int =
        minOf(if (maxHeight > 0) maxHeight else WEBP_MAX_DIMENSION, WEBP_MAX_DIMENSION).coerceAtLeast(1)
}
