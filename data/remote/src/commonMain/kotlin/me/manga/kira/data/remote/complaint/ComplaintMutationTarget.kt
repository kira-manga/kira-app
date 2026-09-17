package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** The checked history base grammar is reused, never its GET/query authority. */
internal class ComplaintMutationTarget private constructor(
    private val createUrl: Url,
) {
    private val host = createUrl.host.removeSurrounding("[", "]").lowercase()
    private val statusPath = createUrl.encodedPath.removeSuffix(Policy.CREATE_PATH) + Policy.STATUS_PATH

    @Suppress("ReturnCount")
    fun route(value: String): ComplaintMutationRoute? {
        if (value.length > MAX_TARGET_CHARACTERS || value.any { it !in '!'..'~' || it in "\\@?#" }) {
            return null
        }
        val candidate = parse(value) ?: return null
        if (!sameOrigin(candidate) || candidate.parameters.isNotEmpty() || candidate.trailingQuery) return null
        return when (candidate.encodedPath) {
            createUrl.encodedPath -> ComplaintMutationRoute.CREATE
            statusPath -> ComplaintMutationRoute.STATUS
            else -> null
        }
    }

    fun sameRoute(
        requestUrl: String,
        responseUrl: String,
    ): Boolean = route(requestUrl)?.let { it == route(responseUrl) } == true

    private fun sameOrigin(candidate: Url): Boolean =
        candidate.protocol == createUrl.protocol &&
            candidate.host.removeSurrounding("[", "]").lowercase() == host &&
            candidate.port == createUrl.port &&
            candidate.user == null &&
            candidate.password == null &&
            candidate.fragment.isEmpty()

    override fun toString(): String = "ComplaintMutationTarget(redacted)"

    companion object {
        private const val MAX_BASE_CHARACTERS = 2_048
        private val MAX_TARGET_CHARACTERS = MAX_BASE_CHARACTERS + Policy.STATUS_PATH.length

        fun checked(createUrl: Url): ComplaintMutationTarget? =
            ComplaintHistoryTarget.checked(createUrl)?.let { ComplaintMutationTarget(createUrl) }

        private fun parse(value: String): Url? =
            try {
                Url(value)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
    }
}

internal enum class ComplaintMutationRoute { CREATE, STATUS }
