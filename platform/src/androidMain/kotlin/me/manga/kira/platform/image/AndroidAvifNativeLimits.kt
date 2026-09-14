package me.manga.kira.platform.image

/** Same 32-byte source estimate for display decodes and synchronous download probes. */
internal fun androidAvifNativePixelLimit(
    limits: AvifDecodeLimits,
    encodedBytes: Int,
    output: AvifPixelSize? = null,
    bitmapBytesPerPixel: Int = 4,
): Int =
    limits.nativePixelLimit(
        encodedBytes = encodedBytes,
        output = output,
        memory = AvifMemoryModel(bitmapBytesPerPixel = bitmapBytesPerPixel, encodedCopies = ENCODED_COPIES),
        nativeMaxPixels = NATIVE_MAX_PIXELS,
    )

internal const val ANDROID_AVIF_NATIVE_MAX_DIMENSION = 32_768
private const val ENCODED_COPIES = 3
private const val NATIVE_MAX_PIXELS = 268_435_456
