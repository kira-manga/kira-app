package me.manga.kira.platform.media

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import okio.IOException
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
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
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFNumberSInt64Type
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreGraphics.CGImageGetBytesPerRow
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCopyTypeIdentifiers
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetCount
import platform.ImageIO.CGImageSourceGetStatus
import platform.ImageIO.CGImageSourceGetStatusAtIndex
import platform.ImageIO.CGImageSourceGetType
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceShouldCache
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.ImageIO.kCGImageStatusComplete

/** Source/output dimensions are bounded; ImageIO provides no hard internal allocator/RSS limit. */
@OptIn(ExperimentalForeignApi::class)
internal fun inspectIosPage(data: CFDataRef, expected: PageImageFormat, policy: PageInspectionPolicy): PageInspection {
    if (!imageIoSupports(expected)) return PageInspection.Rejected(PageInspectionRejection.DECODER_UNAVAILABLE)
    val options = CFDictionaryCreateMutable(null, 0, null, null) ?: throw IOException("ImageIO options unavailable")
    val source = try {
        CFDictionaryAddValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)
        CGImageSourceCreateWithData(data, options)
    } finally {
        CFRelease(options)
    } ?: return invalidPage()
    return try {
        if (!imageIoComplete(source)) return invalidPage()
        val format = imageIoFormat(CGImageSourceGetType(source)) ?: return invalidPage()
        if (format != expected) return invalidPage()
        val metadata = imageIoMetadata(source, format)
        policy.rejectionFor(metadata)?.let { return it }
        if (!validateImageIoSample(source, policy.sampleMaxDimension) || !imageIoComplete(source)) return invalidPage()
        PageInspection.Valid(metadata)
    } finally {
        CFRelease(source)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun imageIoComplete(source: CGImageSourceRef): Boolean =
    CGImageSourceGetCount(source) > 0uL &&
        CGImageSourceGetStatus(source) == kCGImageStatusComplete &&
        CGImageSourceGetStatusAtIndex(source, 0uL) == kCGImageStatusComplete

@OptIn(ExperimentalForeignApi::class)
private fun imageIoMetadata(source: CGImageSourceRef, format: PageImageFormat): PageImageMetadata {
    val properties = CGImageSourceCopyPropertiesAtIndex(source, 0uL, null)
        ?: throw PageFramingException(PageInvalidReason.INCOMPLETE_OR_CORRUPT)
    return try {
        PageImageMetadata(format, imageDimension(properties, kCGImagePropertyPixelWidth), imageDimension(properties, kCGImagePropertyPixelHeight))
    } finally {
        CFRelease(properties)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun imageDimension(properties: CFDictionaryRef, key: CFStringRef?): Int = memScoped {
    val value = CFDictionaryGetValue(properties, key)
        ?: throw PageFramingException(PageInvalidReason.INCOMPLETE_OR_CORRUPT)
    requirePageFraming(CFGetTypeID(value) == CFNumberGetTypeID())
    val number = alloc<LongVar>()
    requirePageFraming(CFNumberGetValue(value.reinterpret(), kCFNumberSInt64Type, number.ptr))
    requirePageFraming(number.value in 1..Int.MAX_VALUE.toLong())
    number.value.toInt()
}

@OptIn(ExperimentalForeignApi::class)
private fun validateImageIoSample(source: CGImageSourceRef, edge: Int): Boolean = memScoped {
    val options = CFDictionaryCreateMutable(null, 0, null, null) ?: throw IOException("ImageIO sample options unavailable")
    val size = alloc<IntVar> { value = edge }
    val number = CFNumberCreate(null, kCFNumberIntType, size.ptr)
    try {
        if (number == null) throw IOException("ImageIO sample bound unavailable")
        CFDictionaryAddValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
        CFDictionaryAddValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
        CFDictionaryAddValue(options, kCGImageSourceThumbnailMaxPixelSize, number)
        val image = CGImageSourceCreateThumbnailAtIndex(source, 0uL, options) ?: return@memScoped false
        try {
            val width = CGImageGetWidth(image)
            val height = CGImageGetHeight(image)
            val row = CGImageGetBytesPerRow(image)
            width in 1uL..edge.toULong() && height in 1uL..edge.toULong() &&
                row > 0uL && row <= MAX_NATIVE_SAMPLE_BYTES / height
        } finally {
            CGImageRelease(image)
        }
    } finally {
        if (number != null) CFRelease(number)
        CFRelease(options)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun imageIoSupports(format: PageImageFormat): Boolean {
    val identifiers = CGImageSourceCopyTypeIdentifiers() ?: return false
    return try {
        (0 until CFArrayGetCount(identifiers)).any { index ->
            CFArrayGetValueAtIndex(identifiers, index)?.let { imageIoFormat(it.reinterpret()) == format } == true
        }
    } finally {
        CFRelease(identifiers)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun imageIoFormat(type: CFStringRef?): PageImageFormat? = memScoped {
    if (type == null) return@memScoped null
    val bytes = allocArray<ByteVar>(MAX_IMAGE_TYPE_BYTES)
    if (!CFStringGetCString(type, bytes, MAX_IMAGE_TYPE_BYTES.toLong(), kCFStringEncodingUTF8)) return@memScoped null
    when (bytes.toKString()) {
        "public.jpeg" -> PageImageFormat.JPEG
        "public.png" -> PageImageFormat.PNG
        "org.webmproject.webp" -> PageImageFormat.WEBP
        "com.compuserve.gif" -> PageImageFormat.GIF
        "com.microsoft.bmp" -> PageImageFormat.BMP
        "public.avif", "public.avif-sequence" -> PageImageFormat.AVIF
        else -> null
    }
}

// Allows native row alignment/high-bit-depth thumbnail output, never a full-resolution bitmap.
private const val MAX_NATIVE_SAMPLE_BYTES: ULong = 128uL * 1024uL
private const val MAX_IMAGE_TYPE_BYTES: Int = 128
