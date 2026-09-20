package me.manga.kira.data.repository.progress

/** Keep the full unmodified Settings string, including the original page spelling. */
internal data class CapturedLegacyProgress(val key: String, val payload: String) {
    fun decode(expectedChapterUrl: String): LegacyProgressPosition? {
        val separator = payload.lastIndexOf(LEGACY_PROGRESS_SEPARATOR)
        if (separator <= 0 || separator == payload.lastIndex) return null
        val url = payload.substring(0, separator)
        if (url != expectedChapterUrl || key != legacyProgressKey(url)) return null
        val page = payload.substring(separator + 1).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return LegacyProgressPosition(url, page)
    }
}

internal data class LegacyProgressPosition(val chapterUrl: String, val pageIndex: Int)

/** Exact codec used by the still-active old repository; a hash match is never owner evidence. */
internal fun legacyProgressKey(chapterUrl: String): String =
    LEGACY_PROGRESS_PREFIX + chapterUrl.hashCode().toUInt().toString(LEGACY_PROGRESS_RADIX)

private const val LEGACY_PROGRESS_PREFIX = "reader.last_page."
private const val LEGACY_PROGRESS_RADIX = 36
private const val LEGACY_PROGRESS_SEPARATOR = '|'
