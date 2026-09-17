package me.manga.kira.data.remote.complaint

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/** Fixed history origin/base/path plus one closed query; never an arbitrary authenticated target. */
internal class ComplaintHistoryTarget private constructor(
    private val url: Url,
) {
    private val host = url.host.removeSurrounding("[", "]").lowercase()

    fun matches(value: String): Boolean = page(value) != null

    fun samePage(
        requestUrl: String,
        responseUrl: String,
    ): Boolean {
        val requested = page(requestUrl) ?: return false
        return requested == page(responseUrl)
    }

    private fun page(value: String): ComplaintHistoryQuery? {
        if (value.length > MAX_TARGET_CHARACTERS + 1 + ComplaintHistoryQuery.maxCharacters || !rawSafe(value)) {
            return null
        }
        val query = ComplaintHistoryQuery.checked(value.substringAfter('?', missingDelimiterValue = "")) ?: return null
        val candidate = parse(value) ?: return null
        return query.takeIf {
            validOrigin(candidate) &&
                host == candidate.host.removeSurrounding("[", "]").lowercase() &&
                url.port == candidate.port &&
                url.encodedPath == candidate.encodedPath
        }
    }

    override fun toString(): String = "ComplaintHistoryTarget(redacted)"

    companion object {
        private const val MAX_BASE_CHARACTERS = 2_048
        private const val MAX_HOST_CHARACTERS = 253
        private const val MAX_PORT = 65_535
        private const val HISTORY_PATH = "/api/v1/complaints"
        private val MAX_TARGET_CHARACTERS = MAX_BASE_CHARACTERS + HISTORY_PATH.length

        fun checked(url: Url): ComplaintHistoryTarget? =
            if (url.toString().length <= MAX_TARGET_CHARACTERS &&
                rawSafe(url.toString()) &&
                validOrigin(url) &&
                url.parameters.isEmpty() &&
                !url.trailingQuery &&
                validPath(url.encodedPath)
            ) {
                ComplaintHistoryTarget(url)
            } else {
                null
            }

        private fun parse(value: String): Url? =
            try {
                Url(value)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }

        private fun validOrigin(url: Url): Boolean =
            url.protocol == URLProtocol.HTTPS &&
                url.host.length in 1..MAX_HOST_CHARACTERS &&
                url.host.all { it.isAsciiLetterOrDigit() || it in ".-:[]" } &&
                url.port in 1..MAX_PORT &&
                url.user == null &&
                url.password == null &&
                url.fragment.isEmpty()

        private fun validPath(path: String): Boolean =
            path.endsWith(HISTORY_PATH) &&
                path.startsWith('/') &&
                path.drop(1).split('/').all { segment ->
                    segment.isNotEmpty() &&
                        segment != "." &&
                        segment != ".." &&
                        segment.all { it.isAsciiLetterOrDigit() || it in "-._~" }
                }

        private fun rawSafe(value: String): Boolean = value.all { it in '!'..'~' && it !in "\\@#" }
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
