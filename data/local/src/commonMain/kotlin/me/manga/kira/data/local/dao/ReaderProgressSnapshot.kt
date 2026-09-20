package me.manga.kira.data.local.dao

/**
 * Local anchor/epoch token, not a domain Reader session or proof of accepted source ownership.
 * Callers must resolve locators and revalidate retained saved IDs in the enclosing writer.
 */
data class ReaderProgressSnapshot(
    val workId: Long,
    val chapterId: Long,
    val workGeneration: Long,
    val chapterGeneration: Long,
    val pageIndex: Int?,
) {
    init {
        require(workId > 0 && chapterId > 0)
        require(workGeneration >= 0 && chapterGeneration >= 0)
        require(pageIndex == null || pageIndex >= 0)
    }
}

internal fun nextReaderGeneration(current: Long): Long {
    check(current >= 0 && current < Long.MAX_VALUE) { "Reader generation exhausted" }
    return current + 1
}
