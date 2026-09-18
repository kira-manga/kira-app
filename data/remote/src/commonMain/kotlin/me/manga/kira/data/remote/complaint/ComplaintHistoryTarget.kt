package me.manga.kira.data.remote.complaint

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/** Fixed read origin/base: either the bounded list query or exactly one canonical queryless resource. */
internal class ComplaintHistoryTarget private constructor(
    private val url: Url,
) {
    private val host = url.host.removeSurrounding("[", "]").lowercase()

    fun matches(value: String): Boolean = read(value) != null

    fun route(value: String): ComplaintHistoryRoute? = read(value)?.route

    fun samePage(
        requestUrl: String,
        responseUrl: String,
    ): Boolean {
        val requested = read(requestUrl) ?: return false
        return requested == read(responseUrl)
    }

    // Explicit fail-closed guards keep malformed authenticated targets out of later parsing.
    @Suppress("ReturnCount")
    private fun read(value: String): ComplaintHistoryReadTarget? {
        if (value.length > MAX_TARGET_CHARACTERS + 1 + ComplaintHistoryQuery.maxCharacters || !rawSafe(value)) {
            return null
        }
        val candidate = parse(value) ?: return null
        if (!validOrigin(candidate) || host != candidate.host.removeSurrounding("[", "]").lowercase() ||
            url.port != candidate.port
        ) {
            return null
        }
        if (candidate.encodedPath == url.encodedPath) {
            val query =
                ComplaintHistoryQuery.checked(value.substringAfter('?', missingDelimiterValue = "")) ?: return null
            return ComplaintHistoryReadTarget.Page(query)
        }
        if (!candidate.encodedPath.startsWith(url.encodedPath + "/") ||
            candidate.parameters.isNotEmpty() || candidate.trailingQuery || '?' in value
        ) {
            return null
        }
        val id = candidate.encodedPath.removePrefix(url.encodedPath + "/")
        return id.takeIf(CANONICAL_ID::matches)?.let { ComplaintHistoryReadTarget.Detail(it) }
    }

    override fun toString(): String = "ComplaintHistoryTarget(redacted)"

    companion object {
        private const val MAX_BASE_CHARACTERS = 2_048
        private const val MAX_HOST_CHARACTERS = 253
        private const val MAX_PORT = 65_535
        private const val HISTORY_PATH = "/api/v1/complaints"
        private val CANONICAL_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val MAX_TARGET_CHARACTERS = MAX_BASE_CHARACTERS + HISTORY_PATH.length

        fun checked(url: Url): ComplaintHistoryTarget? {
            val permitted =
                url.toString().length <= MAX_TARGET_CHARACTERS &&
                    rawSafe(url.toString()) &&
                    validOrigin(url) &&
                    url.parameters.isEmpty() &&
                    !url.trailingQuery &&
                    validPath(url.encodedPath)
            return if (permitted) ComplaintHistoryTarget(url) else null
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

internal enum class ComplaintHistoryRoute { LIST, DETAIL }

private sealed interface ComplaintHistoryReadTarget {
    val route: ComplaintHistoryRoute

    data class Page(val query: ComplaintHistoryQuery) : ComplaintHistoryReadTarget {
        override val route: ComplaintHistoryRoute get() = ComplaintHistoryRoute.LIST

        override fun toString(): String = "ComplaintHistoryReadTarget.Page(redacted)"
    }

    data class Detail(val id: String) : ComplaintHistoryReadTarget {
        override val route: ComplaintHistoryRoute get() = ComplaintHistoryRoute.DETAIL

        override fun toString(): String = "ComplaintHistoryReadTarget.Detail(redacted)"
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
