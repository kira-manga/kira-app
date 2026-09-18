package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** The checked history base grammar is reused, never its GET/query authority. */
internal class ComplaintMutationTarget private constructor(
    private val createUrl: Url,
) {
    private val host = createUrl.host.removeSurrounding("[", "]").lowercase()
    private val statusPath = createUrl.encodedPath.removeSuffix(Policy.CREATE_PATH) + Policy.STATUS_PATH
    private val replyPrefix = "${createUrl.encodedPath}/"

    @Suppress("ReturnCount")
    fun route(value: String): ComplaintMutationRoute? {
        if (value.length > MAX_TARGET_CHARACTERS || value.any { it !in '!'..'~' || it in "\\@?#" }) {
            return null
        }
        val candidate = parse(value) ?: return null
        if (!sameOrigin(candidate) || !candidate.parameters.isEmpty() || candidate.trailingQuery) return null
        return when (candidate.encodedPath) {
            createUrl.encodedPath -> ComplaintMutationRoute.CREATE
            statusPath -> ComplaintMutationRoute.STATUS
            else ->
                when {
                    isReplyPath(candidate.encodedPath) -> ComplaintMutationRoute.REPLY
                    isEditPath(candidate.encodedPath) -> ComplaintMutationRoute.EDIT
                    else -> null
                }
        }
    }

    fun editTargetId(value: String): String? =
        if (route(value) == ComplaintMutationRoute.EDIT) {
            parse(value)?.encodedPath?.removePrefix(replyPrefix)?.removeSuffix(Policy.CONTENT_SUFFIX)
        } else {
            null
        }

    fun sameRoute(
        requestUrl: String,
        responseUrl: String,
    ): Boolean =
        route(requestUrl)?.let {
            it == route(responseUrl) && parse(requestUrl)?.encodedPath == parse(responseUrl)?.encodedPath
        } == true

    private fun isReplyPath(path: String): Boolean =
        path.startsWith(replyPrefix) &&
            path.endsWith(Policy.REPLIES_SUFFIX) &&
            CANONICAL_PARENT.matches(path.removePrefix(replyPrefix).removeSuffix(Policy.REPLIES_SUFFIX))

    private fun isEditPath(path: String): Boolean =
        path.startsWith(replyPrefix) &&
            path.endsWith(Policy.CONTENT_SUFFIX) &&
            CANONICAL_PARENT.matches(path.removePrefix(replyPrefix).removeSuffix(Policy.CONTENT_SUFFIX))

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
        private const val UUID_CHARACTERS = 36
        private val MAX_TARGET_CHARACTERS =
            MAX_BASE_CHARACTERS +
                maxOf(
                    Policy.STATUS_PATH.length,
                    Policy.CREATE_PATH.length + 1 + UUID_CHARACTERS + Policy.REPLIES_SUFFIX.length,
                    Policy.CREATE_PATH.length + 1 + UUID_CHARACTERS + Policy.CONTENT_SUFFIX.length,
                )
        private val CANONICAL_PARENT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

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

internal enum class ComplaintMutationRoute(
    val method: String,
) {
    CREATE("POST"),
    REPLY("POST"),
    EDIT("PATCH"),
    STATUS("POST"),
}
