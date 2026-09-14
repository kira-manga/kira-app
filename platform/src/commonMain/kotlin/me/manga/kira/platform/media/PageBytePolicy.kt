package me.manga.kira.platform.media

import okio.IOException

/** One encoded page's transfer/storage ceiling, independent of a codec's decoded-memory budget. */
data class PageBytePolicy(
    val maxEncodedBytes: Long = DEFAULT_MAX_ENCODED_BYTES,
) {
    init {
        require(maxEncodedBytes in 1..Int.MAX_VALUE.toLong())
    }

    /** A declared length is only an early rejection hint; an unknown/negative length is not proof. */
    fun checkDeclaredLength(length: Long?) {
        if (length != null && length > maxEncodedBytes) throw PageByteLimitExceeded(maxEncodedBytes, length)
    }

    /** Checks the next actual chunk before a sink sees it; subtraction avoids counter overflow. */
    fun checkedTotal(previousBytes: Long, chunkBytes: Int): Long {
        require(previousBytes in 0..maxEncodedBytes && chunkBytes >= 0)
        val total = previousBytes + chunkBytes.toLong()
        if (chunkBytes.toLong() > maxEncodedBytes - previousBytes) {
            throw PageByteLimitExceeded(maxEncodedBytes, total)
        }
        return total
    }

    /** The completed file is checked again, including files recovered after process death. */
    fun checkFileSize(size: Long?) {
        if (size == null || size <= 0) throw IOException("Empty or unreadable downloaded page")
        if (size > maxEncodedBytes) throw PageByteLimitExceeded(maxEncodedBytes, size)
    }

    companion object {
        const val DEFAULT_MAX_ENCODED_BYTES: Long = 32L * 1024 * 1024
    }
}

/** This is a failed transfer, never a validated image eligible for codec-budget preservation. */
class PageByteLimitExceeded(
    val limit: Long,
    val observedBytes: Long,
) : IOException("Page exceeds the $limit-byte download limit")
