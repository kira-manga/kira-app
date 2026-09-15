package me.manga.kira.platform.cbz

/** Encoding quality and the two limits that determine admission/splitting for one validated page. */
internal data class CbzEncodingOptions(
    val quality: Int,
    val maxHeight: Int,
    val maxMemoryBytes: Long,
) {
    fun admit(page: ValidatedCbzPage): CbzTranscodeAdmission =
        CbzTranscodeBudget.admit(
            page.metadata.width,
            page.metadata.height,
            page.bytes.size.toLong(),
            maxHeight,
            maxMemoryBytes,
        )
}
