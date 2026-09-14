package me.manga.kira.platform.media

/**
 * Admission before a native validation sample. These are encoded/source/output bounds, NOT a hard
 * native allocator/RSS guarantee. Above-policy pages are not validated, even if a codec might read
 * them on a larger device. Codec-preservation budgets are a separate decision AFTER validation.
 */
data class PageInspectionPolicy(
    val bytePolicy: PageBytePolicy = PageBytePolicy(),
    val maxSourcePixels: Long = 16_000_000,
    val maxSourceDimension: Int = 32_768,
    val sampleMaxDimension: Int = 64,
) {
    init {
        require(maxSourcePixels in 1..Int.MAX_VALUE.toLong())
        require(maxSourceDimension > 0)
        require(sampleMaxDimension in 1..MAX_SAMPLE_DIMENSION)
    }

    /** No aspect-ratio restriction: both independent axes and the checked pixel product are bounded. */
    fun rejectionFor(metadata: PageImageMetadata): PageInspection.Rejected? =
        when {
            metadata.width > maxSourceDimension || metadata.height > maxSourceDimension ->
                PageInspection.Rejected(
                    PageInspectionRejection.SOURCE_AXIS,
                    maxSourceDimension.toLong(),
                    maxOf(metadata.width, metadata.height).toLong(),
                )
            metadata.pixelCount > maxSourcePixels ->
                PageInspection.Rejected(PageInspectionRejection.SOURCE_PIXELS, maxSourcePixels, metadata.pixelCount)
            else -> null
        }

    companion object {
        const val MAX_SAMPLE_DIMENSION: Int = 64
    }
}
