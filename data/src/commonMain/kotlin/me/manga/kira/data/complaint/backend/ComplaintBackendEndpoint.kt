package me.manga.kira.data.complaint.backend

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy

/** Explicit configuration, not discovery. Only fixed credential-free HTTPS installation/history routes exist. */
class ComplaintBackendEndpoint private constructor(
    base: String,
    private val expectedDataScopeId: String?,
) {
    val sessionUrl: Url = Url(base + SESSION_PATH)
    val enrollmentUrl: Url = Url(base + ENROLLMENT_PATH)
    val bootstrapUrl: Url = Url(base + BOOTSTRAP_PATH)
    val historyUrl: Url = Url(base + "/api/v1/complaints")
    val deletionUrl: Url = Url(base + ComplaintDeletionTransportPolicy.PATH)

    /** Launch-scope binding only; a mismatch never authorizes deleting, rebinding or adopting local data. */
    internal fun acceptsDataScope(dataScopeId: String): Boolean =
        expectedDataScopeId == null || expectedDataScopeId == dataScopeId

    override fun toString(): String = "ComplaintBackendEndpoint(redacted)"

    companion object {
        private const val MAX_ENDPOINT_CHARACTERS = 2_048
        private const val MAX_HOST_CHARACTERS = 253
        private const val MAX_PORT = 65_535
        private const val ENROLLMENT_PATH = "/api/v1/installations"
        private const val SESSION_PATH = "$ENROLLMENT_PATH/session"
        private const val BOOTSTRAP_PATH = "$ENROLLMENT_PATH/bootstrap"
        private const val LIVE_SCOPE = "00000000-0000-0000-0000-000000000000"
        private val TEST_SCOPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

        /**
         * Rejects ambiguous paths and malformed optional scope. The application host always supplies
         * its checked launch scope; null preserves low-level unbound consumers, not launch selection.
         */
        fun checked(value: String, expectedDataScopeId: String? = null): ComplaintBackendEndpoint? =
            when {
                expectedDataScopeId != null && expectedDataScopeId != LIVE_SCOPE &&
                    !TEST_SCOPE.matches(expectedDataScopeId) -> null
                value.length !in 1..MAX_ENDPOINT_CHARACTERS || !value.startsWith("https://") -> null
                value.any { it !in '!'..'~' || it in "\\@?#" } -> null
                else ->
                    try {
                        val url = Url(value)
                        if (!allowedOrigin(url) || !allowedPort(value) || !allowedPath(url.encodedPath)) {
                            null
                        } else {
                            ComplaintBackendEndpoint(value.removeSuffix("/"), expectedDataScopeId)
                        }
                    } catch (_: IllegalArgumentException) {
                        null
                    } catch (_: IllegalStateException) {
                        null
                    }
            }

        private fun allowedOrigin(url: Url): Boolean =
            url.protocol == URLProtocol.HTTPS &&
                url.host.length in 1..MAX_HOST_CHARACTERS &&
                url.host.all { it.isAsciiLetterOrDigit() || it in ".-:[]" } &&
                url.port in 1..MAX_PORT &&
                url.user == null &&
                url.password == null &&
                url.parameters.isEmpty() &&
                url.fragment.isEmpty() &&
                !url.trailingQuery

        private fun allowedPath(encodedPath: String): Boolean {
            val path = encodedPath.removeSuffix("/")
            if (path.isEmpty()) return true
            return path.startsWith('/') &&
                path.drop(1).split('/').all { segment ->
                    segment.isNotEmpty() &&
                        segment != "." &&
                        segment != ".." &&
                        segment.all { it.isAsciiLetterOrDigit() || it in "-._~" }
                }
        }

        private fun allowedPort(value: String): Boolean {
            val authority = value.removePrefix("https://").substringBefore('/')
            val separator = authority.lastIndexOf(':')
            return if (separator <= authority.lastIndexOf(']')) {
                true
            } else {
                val port = authority.substring(separator + 1)
                val number = port.toIntOrNull()
                port.all { it in '0'..'9' } && number != null && number in 1..MAX_PORT
            }
        }
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
