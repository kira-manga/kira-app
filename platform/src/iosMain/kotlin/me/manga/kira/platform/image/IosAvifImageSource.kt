package me.manga.kira.platform.image

import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DecodeResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberGetTypeID
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFNumberSInt64Type
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceShouldCache
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import kotlin.coroutines.CoroutineContext

/** All retained CF objects are released on failure and cancellation; CFData avoids an autorelease copy. */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun decodeIosAvif(avif: ByteArray, options: Options, limits: AvifDecodeLimits): DecodeResult {
    limits.checkEncoded(avif.size, iosAvifMemory)
    val context = currentCoroutineContext()
    context.ensureActive()
    val data =
        avif.usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret(), avif.size.toLong())
        } ?: throw AvifDecodeException("Unable to create bounded AVIF input data.")
    try {
        val imageSource = createAvifImageSource(data)
        try {
            return decodeImageIoSource(imageSource, avif.size, options, limits, context)
        } finally {
            CFRelease(imageSource)
        }
    } finally {
        CFRelease(data)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createAvifImageSource(data: CFDataRef): CGImageSourceRef {
    val options =
        CFDictionaryCreateMutable(null, 0, null, null)
            ?: throw AvifDecodeException("Unable to allocate ImageIO source options.")
    try {
        // Metadata inspection must not create a cached full-resolution raster.
        CFDictionaryAddValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)
        return CGImageSourceCreateWithData(data, options)
            ?: throw AvifDecodeException("ImageIO could not open the AVIF image.")
    } finally {
        CFRelease(options)
    }
}

@OptIn(ExperimentalCoilApi::class, ExperimentalForeignApi::class)
private fun decodeImageIoSource(
    imageSource: CGImageSourceRef,
    encodedBytes: Int,
    options: Options,
    limits: AvifDecodeLimits,
    context: CoroutineContext,
): DecodeResult {
    val source = readAvifSourceSize(imageSource)
    limits.checkSource(source)
    val output = avifDecodeSize(source, options.size, options.scale, options.precision, options.maxBitmapSize)
    val allocation = limits.admit(encodedBytes, source, output, iosAvifMemory)
    // ImageIO's cap is scalar, but it is derived from the already-computed two-axis output.
    // ImageIO does not upscale: an EXACT upscale is performed by the final bounded drawing step.
    val edge = minOf(maxOf(source.width, source.height), maxOf(output.width, output.height))
    context.ensureActive()
    val thumbnail = createAvifThumbnail(imageSource, edge)
    try {
        checkAvifThumbnail(thumbnail, edge, limits.maxOutputBytes)
        context.ensureActive()
        return drawIosAvifBitmap(thumbnail, output, allocation, output.isSmallerThan(source), context)
    } finally {
        CGImageRelease(thumbnail)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readAvifSourceSize(source: CGImageSourceRef): AvifPixelSize {
    val properties =
        CGImageSourceCopyPropertiesAtIndex(source, 0uL, null)
            ?: throw AvifDecodeException("ImageIO could not read AVIF dimensions.")
    try {
        return AvifPixelSize(
            width = readAvifDimension(properties, kCGImagePropertyPixelWidth),
            height = readAvifDimension(properties, kCGImagePropertyPixelHeight),
        )
    } finally {
        CFRelease(properties)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readAvifDimension(properties: CFDictionaryRef, key: CFStringRef?): Int =
    memScoped {
        val number =
            CFDictionaryGetValue(properties, key)
                ?: throw AvifDecodeException("AVIF dimension metadata is missing.")
        if (CFGetTypeID(number) != CFNumberGetTypeID()) {
            throw AvifDecodeException("AVIF dimension metadata is not numeric.")
        }
        val dimension = alloc<LongVar>()
        if (!CFNumberGetValue(number.reinterpret(), kCFNumberSInt64Type, dimension.ptr) ||
            dimension.value !in 1..Int.MAX_VALUE.toLong()
        ) {
            throw AvifDecodeException("AVIF dimension metadata is invalid or overflowing.")
        }
        dimension.value.toInt()
    }

@OptIn(ExperimentalForeignApi::class)
private fun createAvifThumbnail(source: CGImageSourceRef, edge: Int): CGImageRef =
    memScoped {
        val options =
            CFDictionaryCreateMutable(null, 0, null, null)
                ?: throw AvifDecodeException("Unable to allocate ImageIO thumbnail options.")
        val size = alloc<IntVar> { value = edge }
        val number = CFNumberCreate(null, kCFNumberIntType, size.ptr)
        try {
            if (number == null) throw AvifDecodeException("Unable to allocate the ImageIO thumbnail limit.")
            CFDictionaryAddValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
            CFDictionaryAddValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
            CFDictionaryAddValue(options, kCGImageSourceThumbnailMaxPixelSize, number)
            CGImageSourceCreateThumbnailAtIndex(source, 0uL, options)
                ?: throw AvifDecodeException("ImageIO could not decode the AVIF thumbnail.")
        } finally {
            if (number != null) CFRelease(number)
            CFRelease(options)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun checkAvifThumbnail(image: CGImageRef, maxEdge: Int, maxBytes: Long) {
    val width = CGImageGetWidth(image)
    val height = CGImageGetHeight(image)
    val rowBytes = CGImageGetBytesPerRow(image)
    if (width == 0uL || height == 0uL || width > maxEdge.toULong() || height > maxEdge.toULong()) {
        throw AvifDecodeException("ImageIO returned AVIF dimensions outside the thumbnail limit.")
    }
    if (rowBytes == 0uL || rowBytes > maxBytes.toULong() / height) {
        throw AvifDecodeException("ImageIO returned an AVIF thumbnail outside the output byte limit.")
    }
}

private val iosAvifMemory = AvifMemoryModel(bitmapBytesPerPixel = 4, encodedCopies = 4)
