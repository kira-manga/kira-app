package me.manga.kira.platform.cbz

/** A codec decision only: the caller must first validate the exact retained encoded snapshot. */
internal sealed interface CbzTranscodeAdmission {
    data class Admitted(
        val plan: CbzTranscodePlan,
    ) : CbzTranscodeAdmission

    data object PreserveBudget : CbzTranscodeAdmission

    data object PreserveWebpDimensions : CbzTranscodeAdmission
}

internal data class CbzTranscodePlan(
    val width: Int,
    val height: Int,
    val bandHeight: Int,
    val rgbaRowBytes: Int,
    val sourceAllocationAllowanceBytes: Long,
    val maxEncodedBandBytes: Int,
    val estimatedPeakBytes: Long,
)

/**
 * Conservative per-page admission shared by the mobile CBZ writers, not a media-validity test.
 *
 * Reserve three encoded-source copies, 16 bytes/source pixel for overlapping native source/RGBA
 * buffers, 4 MiB fixed setup, and 64 KiB/container for each of two output copies. From what remains,
 * admit ONE band at 48 bytes/pixel: 8 for bitmap overlap, 32 for codec workspace, 8 for two encoded
 * output copies. Native libwebp/ImageIO/BitmapFactory allocators do not expose a hard cap; these
 * allowances are deliberately conservative admission estimates, not measured peak-memory proof.
 * Physical low-memory iPhone foreground/background profiling remains required.
 *
 * Reservations divide before multiplying, so an overflowing estimate can never admit a decode.
 */
internal object CbzTranscodeBudget {
    fun admit(
        width: Int,
        height: Int,
        encodedBytes: Long,
        maxHeight: Int,
        maxMemoryBytes: Long = CbzWriter.DEFAULT_MAX_MEMORY_BYTES,
    ): CbzTranscodeAdmission {
        require(width > 0 && height > 0 && encodedBytes > 0) { "Invalid CBZ source dimensions or size" }
        require(maxHeight > 0 && maxMemoryBytes > 0) { "Invalid CBZ splitting limits" }
        if (width > WEBP_MAX_DIMENSION) return CbzTranscodeAdmission.PreserveWebpDimensions
        val pixels = width.toLong() * height
        val allowance = Allowance(maxMemoryBytes)
        if (!allowance.reserve(encodedBytes, ENCODED_SOURCE_COPIES) ||
            !allowance.reserve(pixels, SOURCE_BYTES_PER_PIXEL) ||
            !allowance.reserve(FIXED_BYTES) ||
            !allowance.reserve(OUTPUT_CONTAINER_BYTES, OUTPUT_COPIES)
        ) {
            return CbzTranscodeAdmission.PreserveBudget
        }
        val bandRows = allowance.remaining / (width.toLong() * BAND_BYTES_PER_PIXEL)
        val bandHeight = minOf(minOf(height, maxHeight, WEBP_MAX_DIMENSION).toLong(), bandRows).toInt()
        if (bandHeight <= 0) return CbzTranscodeAdmission.PreserveBudget
        val bandPixels = width.toLong() * bandHeight
        val outputBytes = bandPixels * RGBA_BYTES_PER_PIXEL + OUTPUT_CONTAINER_BYTES
        if (outputBytes > Int.MAX_VALUE) return CbzTranscodeAdmission.PreserveBudget
        return CbzTranscodeAdmission.Admitted(
            CbzTranscodePlan(
                width,
                height,
                bandHeight,
                width * RGBA_BYTES_PER_PIXEL,
                pixels * SOURCE_BYTES_PER_PIXEL + FIXED_BYTES,
                outputBytes.toInt(),
                maxMemoryBytes - allowance.remaining + bandPixels * BAND_BYTES_PER_PIXEL,
            ),
        )
    }

    private class Allowance(
        var remaining: Long,
    ) {
        fun reserve(
            count: Long,
            copies: Int = 1,
        ): Boolean {
            if (count > remaining / copies) return false
            remaining -= count * copies
            return true
        }
    }

    const val WEBP_MAX_DIMENSION = 16_383
    private const val RGBA_BYTES_PER_PIXEL = 4
    private const val ENCODED_SOURCE_COPIES = 3
    private const val SOURCE_BYTES_PER_PIXEL = 16
    private const val BAND_BYTES_PER_PIXEL = 48
    private const val OUTPUT_COPIES = 2
    private const val OUTPUT_CONTAINER_BYTES = 64L * 1024
    private const val FIXED_BYTES = 4L * 1024 * 1024
}
