package me.manga.kira.data.remote.complaint

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/** Closed bootstrap/enrollment pair; deliberately does not widen the separate session target. */
internal class ComplaintEnrollmentTarget private constructor(
    private val enrollmentUrl: Url,
) {
    private val host = enrollmentUrl.host.removeSurrounding("[", "]").lowercase()
    private val bootstrapPath = enrollmentUrl.encodedPath + BOOTSTRAP_SUFFIX

    fun matchesEnrollment(value: String): Boolean = matches(value, enrollmentUrl.encodedPath)

    fun matchesBootstrap(value: String): Boolean = matches(value, bootstrapPath)

    private fun matches(
        value: String,
        path: String,
    ): Boolean {
        if (value.length > MAX_URL_CHARACTERS) return false
        val candidate =
            try {
                Url(value)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        return candidate != null &&
            validOrigin(candidate) &&
            host == candidate.host.removeSurrounding("[", "]").lowercase() &&
            enrollmentUrl.port == candidate.port &&
            path == candidate.encodedPath
    }

    override fun toString(): String = "ComplaintEnrollmentTarget(redacted)"

    companion object {
        private const val MAX_URL_CHARACTERS = 2_048
        private const val MAX_HOST_CHARACTERS = 253
        private const val MAX_PORT = 65_535
        private const val ENROLLMENT_PATH = "/api/v1/installations"
        private const val BOOTSTRAP_SUFFIX = "/bootstrap"

        fun checked(url: Url): ComplaintEnrollmentTarget? =
            if (validOrigin(url) &&
                validEnrollmentPath(url.encodedPath) &&
                url.toString().length <= MAX_URL_CHARACTERS - BOOTSTRAP_SUFFIX.length
            ) {
                ComplaintEnrollmentTarget(url)
            } else {
                null
            }

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

        private fun validEnrollmentPath(path: String): Boolean =
            path.endsWith(ENROLLMENT_PATH) &&
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
