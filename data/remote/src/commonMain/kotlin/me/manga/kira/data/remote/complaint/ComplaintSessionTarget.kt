package me.manga.kira.data.remote.complaint

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/** Native policy only; the common session client still owns configuration and request validation. */
internal class ComplaintSessionTarget private constructor(
    private val url: Url,
) {
    private val host = url.host.removeSurrounding("[", "]").lowercase()

    fun matches(value: String): Boolean {
        if (value.length > MAX_URL_CHARACTERS) return false
        val candidate =
            try {
                checked(Url(value))
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        return candidate != null &&
            host == candidate.host &&
            url.port == candidate.url.port &&
            url.encodedPath == candidate.url.encodedPath
    }

    override fun toString(): String = "ComplaintSessionTarget(redacted)"

    companion object {
        private const val MAX_URL_CHARACTERS = 2_048
        private const val MAX_HOST_CHARACTERS = 253
        private const val MAX_PORT = 65_535
        private const val SESSION_PATH = "/api/v1/installations/session"

        fun checked(url: Url): ComplaintSessionTarget? =
            if (validOrigin(url) && validPath(url.encodedPath)) ComplaintSessionTarget(url) else null

        private fun validOrigin(url: Url): Boolean =
            url.toString().length <= MAX_URL_CHARACTERS &&
                url.protocol == URLProtocol.HTTPS &&
                url.host.length in 1..MAX_HOST_CHARACTERS &&
                url.host.all { it.isAsciiLetterOrDigit() || it in ".-:[]" } &&
                url.port in 1..MAX_PORT &&
                url.user == null &&
                url.password == null &&
                url.parameters.isEmpty() &&
                url.fragment.isEmpty() &&
                !url.trailingQuery

        private fun validPath(path: String): Boolean =
            path.endsWith(SESSION_PATH) &&
                path.startsWith('/') &&
                path.drop(1).split('/').all { segment ->
                    segment.isNotEmpty() &&
                        segment != "." &&
                        segment != ".." &&
                        segment.all { it.isAsciiLetterOrDigit() || it in "-._~" }
                }
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
