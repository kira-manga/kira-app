package me.manga.kira.platform.cbz

/**
 * Outcome for one already-validated, retained source snapshot. Encoded bands were emitted and
 * released sequentially; no byte-array list escapes the codec. A thrown failure is never permission
 * to preserve bytes. The caller may emit the original only for an explicit preservation decision.
 */
internal sealed interface CbzPageEncoding {
    data class Encoded(
        val bandCount: Int,
    ) : CbzPageEncoding

    data class PreserveOriginal(
        val reason: CbzPreservationReason,
    ) : CbzPageEncoding
}

internal enum class CbzPreservationReason { MEMORY_BUDGET, WEBP_DIMENSIONS, UNSUPPORTED_TRANSCODE }
