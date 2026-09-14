package me.manga.kira.platform.image

import okio.IOException

internal class AvifDecodeException(message: String) : IOException(message)

/**
 * Per-decode admission, not an assertion about a native codec's allocator or the process RSS.
 * The encoded and output limits are enforced before the corresponding application allocations.
 * Android derives a native frame/tile pixel cap from the same allowance. This is not an aggregate
 * native byte budget or an independent inner-AV1 axis limit. ImageIO admission remains metadata-
 * based because it does not expose an allocation-budget API.
 */
internal data class AvifDecodeLimits(
    val maxEncodedBytes: Int = DEFAULT_ENCODED_BYTES,
    val maxSourcePixels: Long = DEFAULT_SOURCE_PIXELS,
    val maxSourceDimension: Int = DEFAULT_SOURCE_DIMENSION,
    val maxOutputBytes: Long = DEFAULT_OUTPUT_BYTES,
    val maxWorkingBytes: Long = DEFAULT_WORKING_BYTES,
) {
    init {
        require(maxEncodedBytes > 0)
        require(maxSourcePixels > 0)
        require(maxSourceDimension > 0)
        require(maxOutputBytes > 0)
        require(maxWorkingBytes > 0)
    }

    fun checkEncoded(byteCount: Int, memory: AvifMemoryModel) {
        encodedAllowance(byteCount, memory)
    }

    fun checkSource(size: AvifPixelSize) {
        if (size.width > maxSourceDimension || size.height > maxSourceDimension || size.pixels > maxSourcePixels) {
            throw AvifDecodeException("AVIF source dimensions exceed the decode limit.")
        }
    }

    fun admit(
        encodedBytes: Int,
        source: AvifPixelSize,
        output: AvifPixelSize,
        memory: AvifMemoryModel,
    ): AvifOutputAllocation {
        val allowance = encodedAllowance(encodedBytes, memory)
        checkSource(source)
        val allocation = outputAllocation(output, memory.bitmapBytesPerPixel)
        allowance.apply {
            reserve(source.pixels, memory.sourceBytesPerPixel)
            reserve(output.pixels, memory.outputBytesPerPixel)
        }
        return allocation
    }

    /** Reserve real encoded/output costs before allowing any native source pixels. */
    fun nativePixelLimit(
        encodedBytes: Int,
        output: AvifPixelSize?,
        memory: AvifMemoryModel,
        nativeMaxPixels: Int,
    ): Int {
        require(nativeMaxPixels > 0)
        val allowance = encodedAllowance(encodedBytes, memory)
        if (output != null) {
            outputAllocation(output, memory.bitmapBytesPerPixel)
            allowance.reserve(output.pixels, memory.outputBytesPerPixel)
        }
        val pixels = minOf(maxSourcePixels, nativeMaxPixels.toLong(), allowance.capacity(memory.sourceBytesPerPixel))
        if (pixels <= 0) throw AvifDecodeException("AVIF working-memory estimate cannot admit a source pixel.")
        return pixels.toInt()
    }

    fun outputAllocation(size: AvifPixelSize, bytesPerPixel: Int): AvifOutputAllocation {
        require(bytesPerPixel in 1..MAX_BYTES_PER_PIXEL)
        val byteLimit = minOf(maxOutputBytes, Int.MAX_VALUE.toLong())
        if (size.pixels > byteLimit / bytesPerPixel) {
            throw AvifDecodeException("AVIF output exceeds the bitmap allocation limit.")
        }
        return AvifOutputAllocation(
            rowBytes = (size.width.toLong() * bytesPerPixel).toInt(),
            byteCount = (size.pixels * bytesPerPixel).toInt(),
        )
    }

    private fun encodedAllowance(byteCount: Int, memory: AvifMemoryModel): WorkingAllowance {
        if (byteCount <= 0 || byteCount > maxEncodedBytes) {
            throw AvifDecodeException("AVIF encoded input exceeds the decode limit or is empty.")
        }
        return WorkingAllowance(maxWorkingBytes).apply {
            reserve(memory.fixedBytes)
            reserve(byteCount.toLong(), memory.encodedCopies)
        }
    }
}

internal data class AvifOutputAllocation(
    val rowBytes: Int,
    val byteCount: Int,
)

/**
 * Deliberate admission headroom: 32 bytes/source pixel and 16 MiB fixed workspace. These are
 * estimates, NOT hard native-memory ceilings. Android additionally allows scaled YUV/alpha planes
 * (8 bytes/output pixel); iOS allows a thumbnail and Skia copy alongside the RGBA drawing buffer.
 * Encoded copies include bounded-read staging as well as the heap/direct or NSData representations.
 */
internal data class AvifMemoryModel(
    val bitmapBytesPerPixel: Int,
    val encodedCopies: Int,
    val sourceBytesPerPixel: Int = SOURCE_BYTES_PER_PIXEL,
    val fixedBytes: Long = FIXED_WORKSPACE_BYTES,
) {
    init {
        require(bitmapBytesPerPixel in 1..MAX_BYTES_PER_PIXEL)
        require(encodedCopies > 0)
        require(sourceBytesPerPixel > 0)
        require(fixedBytes >= 0)
    }

    val outputBytesPerPixel: Int get() = bitmapBytesPerPixel + OUTPUT_WORKSPACE_BYTES_PER_PIXEL
}

/** Subtract before multiplying, so an overflowing estimate can never become a small allocation. */
private class WorkingAllowance(private var remaining: Long) {
    fun reserve(count: Long, copies: Int = 1) {
        if (count < 0 || count > remaining / copies) {
            throw AvifDecodeException("AVIF working-memory estimate exceeds the decode limit.")
        }
        remaining -= count * copies
    }

    fun capacity(bytesPerItem: Int): Long = remaining / bytesPerItem
}

private const val MEBIBYTE = 1024 * 1024
private const val MAX_BYTES_PER_PIXEL = 8
private const val DEFAULT_ENCODED_BYTES = 16 * MEBIBYTE
private const val DEFAULT_SOURCE_PIXELS = 16_000_000L
private const val DEFAULT_SOURCE_DIMENSION = 32_768
private const val DEFAULT_OUTPUT_BYTES = 32L * MEBIBYTE
private const val DEFAULT_WORKING_BYTES = 256L * MEBIBYTE
private const val SOURCE_BYTES_PER_PIXEL = 32
private const val FIXED_WORKSPACE_BYTES = 16L * MEBIBYTE
private const val OUTPUT_WORKSPACE_BYTES_PER_PIXEL = 8
