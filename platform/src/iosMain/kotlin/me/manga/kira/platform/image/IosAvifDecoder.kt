package me.manga.kira.platform.image

import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.pxOrElse
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import okio.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreGraphics.CGBitmapContextCreate
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
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize

/**
 * iOS AVIF decoder (#3-REAL). Skiko's bundled Skia ships no libavif, so AVIF-served pages can't be
 * decoded by [HighQualitySkiaImageDecoder] — they previously failed on iOS (the broken-page path).
 * Android decodes AVIF via its own `AvifDecoderCoil`; this restores parity on iOS.
 *
 * Decoding goes straight through ImageIO at the requested target size: `CGImageSourceCreateWithData`
 * then `CGImageSourceCreateThumbnailAtIndex` with `kCGImageSourceThumbnailMaxPixelSize` derived from
 * `options.size` (bounded by `options.maxBitmapSize`). The downsampled `CGImage` is drawn once into a
 * premultiplied-RGBA CoreGraphics bitmap and the pixels are installed directly into a Skia [Bitmap].
 * This avoids the previous full-resolution `UIImage` raster + lossless-PNG round-trip + second
 * full-res Skia decode (a multi-hundred-MB transient spike on tall webtoon strips, decoded
 * concurrently by Coil while the reader preloads). ImageIO accepts every AVIF variant the system
 * supports — `mif1`-major-brand files included — not just the `avif` major brand.
 *
 * Registered AHEAD of [HighQualitySkiaImageDecoder] in [IosImageDecoderRegistry]; the [Factory]
 * sniffs the ISO-BMFF `ftyp` box and only claims AVIF (`avif`/`avis`, major or compatible brand)
 * inputs — every other format falls through to the Skia decoder unchanged.
 *
 * NOTE (needs-device-smoke): only a real iOS runtime proves the system decoder accepts a given
 * source's AVIF variant. The compile gate + this code are necessary but not sufficient — verify on
 * device/simulator with a known AVIF-serving source (online render + offline CBZ read-back).
 *
 * Uses only the standard Kotlin/Native Apple platform libraries (ImageIO / CoreGraphics /
 * Foundation) — no custom cinterop block is required (same posture as the sibling
 * `IosDominantColorExtractor` / `IosScreenshotProvider`).
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosAvifDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val avif = source.source().use { it.readByteArray() }
        return decodeAvifAtTargetSize(avif, options)
            ?: throw IllegalStateException("iOS system decoder could not decode the AVIF page bytes.")
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? = if (isAvif(result.source)) IosAvifDecoder(result.source, options) else null
    }
}

/**
 * Decode [avif] bytes via ImageIO directly at the size [options] requests and install the
 * downsampled pixels into a Skia [Bitmap]. Returns null if the system decoder cannot decode the
 * bytes (caller surfaces a Coil-friendly decode failure).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun decodeAvifAtTargetSize(avif: ByteArray, options: Options): DecodeResult? {
    if (avif.isEmpty()) return null

    // Longest-edge cap for the thumbnail: the larger requested dimension, bounded by the request's
    // max bitmap size (Coil default 4096). When the request is ORIGINAL we still cap at maxBitmapSize
    // so a tall strip is never rastered at full resolution.
    val maxBitmap = options.maxBitmapSize
    val maxBitmapEdge = maxOf(
        maxBitmap.width.pxOrElse { DEFAULT_MAX_EDGE },
        maxBitmap.height.pxOrElse { DEFAULT_MAX_EDGE },
    )
    val requestedEdge = maxOf(options.size.width.pxOrZero(), options.size.height.pxOrZero())
    val maxPixelSize = (if (requestedEdge > 0) minOf(requestedEdge, maxBitmapEdge) else maxBitmapEdge)
        .coerceAtLeast(1)

    val nsData: NSData = avif.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = avif.size.toULong())
    }

    // CFBridgingRetain hands ImageIO a +1 CFTypeRef; reinterpret it to the toll-free-bridged
    // CFDataRef (a legal CPointer→CPointer reinterpret, unlike the illegal ObjC-object `as CF…`).
    val cfData: CFDataRef = CFBridgingRetain(nsData)?.reinterpret() ?: return null
    try {
        val cgSource = CGImageSourceCreateWithData(cfData, null) ?: return null
        try {
            return decodeThumbnail(cgSource, maxPixelSize)
        } finally {
            CFRelease(cgSource)
        }
    } finally {
        CFRelease(cfData)
    }
}

/**
 * Create a downsampled thumbnail (longest edge ≤ [maxPixelSize]) from [cgSource] and install it into
 * a Skia bitmap. The options dictionary uses null key/value callbacks: its keys are immortal ImageIO
 * constants and `kCFBooleanTrue`, and the one owned value ([cfSize]) is held alive for the duration
 * of the thumbnail call and released afterwards, so no retain/release callbacks are needed.
 */
@OptIn(ExperimentalForeignApi::class)
private fun decodeThumbnail(
    cgSource: CGImageSourceRef,
    maxPixelSize: Int,
): DecodeResult? = memScoped {
    val options = CFDictionaryCreateMutable(null, 0, null, null) ?: return null
    val sizeVar = alloc<IntVar> { value = maxPixelSize }
    val cfSize: CFNumberRef? = CFNumberCreate(null, kCFNumberIntType, sizeVar.ptr)
    try {
        CFDictionaryAddValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionaryAddValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
        if (cfSize != null) {
            CFDictionaryAddValue(options, kCGImageSourceThumbnailMaxPixelSize, cfSize)
        }
        val cgImage = CGImageSourceCreateThumbnailAtIndex(cgSource, 0uL, options) ?: return null
        try {
            return drawCgImageToSkiaResult(cgImage)
        } finally {
            CGImageRelease(cgImage)
        }
    } finally {
        if (cfSize != null) CFRelease(cfSize)
        CFRelease(options)
    }
}

/** Draw a (downsampled) [cgImage] into a premultiplied-RGBA buffer and install it into a Skia Bitmap. */
@OptIn(ExperimentalForeignApi::class)
private fun drawCgImageToSkiaResult(cgImage: CGImageRef): DecodeResult? {
    val width = CGImageGetWidth(cgImage).toInt()
    val height = CGImageGetHeight(cgImage).toInt()
    if (width <= 0 || height <= 0) return null

    val rowBytes = width * RGBA_BYTES_PER_PIXEL
    val pixels = ByteArray(rowBytes * height)

    val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
    try {
        pixels.usePinned { pinned ->
            val ctx = CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = width.toULong(),
                height = height.toULong(),
                bitsPerComponent = BITS_PER_CHANNEL,
                bytesPerRow = rowBytes.toULong(),
                space = colorSpace,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
            ) ?: return null
            try {
                CGContextDrawImage(
                    ctx,
                    CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
                    cgImage,
                )
            } finally {
                CGContextRelease(ctx)
            }
        }
    } finally {
        CGColorSpaceRelease(colorSpace)
    }

    val bitmap = Bitmap()
    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
    if (!bitmap.installPixels(info, pixels, rowBytes)) {
        bitmap.close()
        return null
    }
    bitmap.setImmutable()
    return DecodeResult(image = bitmap.asImage(), isSampled = true)
}

private fun Dimension.pxOrZero(): Int = pxOrElse { 0 }

/**
 * Peek the source's `ftyp` box and accept it as AVIF when any 4-byte brand it lists (the major brand
 * at bytes 8..11 OR any compatible brand that follows) is `avif`/`avis`. Reading the full box (bounded
 * to [FTYP_PEEK_BYTES]) — not just the major brand — accepts files whose major brand is e.g. `mif1`
 * with `avif` in the compatible list. Peeking does not consume the source the decoder later reads; any
 * read error declines (returns false) so a non-AVIF input falls through to the Skia decoder.
 */
private fun isAvif(source: ImageSource): Boolean = try {
    source.source().peek().use { peek ->
        if (!peek.request(FTYP_HEADER_BYTES.toLong())) {
            false
        } else {
            // request() guarantees at least the 12-byte header; read up to FTYP_PEEK_BYTES, taking
            // whatever the buffer holds so a short file is not rejected by an over-large read.
            peek.request(FTYP_PEEK_BYTES.toLong())
            val h = peek.readByteArray(minOf(FTYP_PEEK_BYTES.toLong(), peek.buffer.size))
            if (h.size < FTYP_HEADER_BYTES ||
                h[4] != 0x66.toByte() || h[5] != 0x74.toByte() ||
                h[6] != 0x79.toByte() || h[7] != 0x70.toByte()
            ) {
                false
            } else {
                var i = FTYP_HEADER_BYTES - 4 // start at the major brand (offset 8)
                var matched = false
                while (i + 4 <= h.size && !matched) {
                    matched = h[i] == 0x61.toByte() && h[i + 1] == 0x76.toByte() &&
                        h[i + 2] == 0x69.toByte() &&
                        (h[i + 3] == 0x66.toByte() || h[i + 3] == 0x73.toByte())
                    i += 4
                }
                matched
            }
        }
    }
} catch (_: Throwable) {
    false
}

private const val RGBA_BYTES_PER_PIXEL = 4
private const val BITS_PER_CHANNEL: ULong = 8u
private const val DEFAULT_MAX_EDGE = 4096
// "....ftyp" + the 4-byte major brand = 12 bytes; scan compatible brands up to 64 bytes.
private const val FTYP_HEADER_BYTES = 12
private const val FTYP_PEEK_BYTES = 64
