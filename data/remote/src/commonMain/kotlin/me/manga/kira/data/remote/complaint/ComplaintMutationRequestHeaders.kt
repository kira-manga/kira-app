package me.manga.kira.data.remote.complaint

import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Closed application headers plus the fixed User-Agent added by Ktor 3.5.1 before native checks. */
internal object ComplaintMutationRequestHeaders {
    private val ALLOWED =
        setOf(
            "authorization",
            "accept",
            "accept-encoding",
            "cache-control",
            "content-type",
            "content-length",
            "if-match",
            Policy.IDEMPOTENCY_HEADER.lowercase(),
            "user-agent",
        )
    private val KEY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val TARGET = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private const val MAX_PRECONDITION_CHARACTERS = 256

    fun accepts(
        route: ComplaintMutationRoute,
        headers: List<Pair<String, String>>,
        bodyBytes: Long,
        editTargetId: String? = null,
    ): Boolean =
        bodyBytes in 1L..Policy.MAX_REQUEST_BYTES.toLong() &&
            headers.size <= ALLOWED.size &&
            headers.all { (name, _) -> name.all { it in '!'..'~' } && name.lowercase() in ALLOWED } &&
            ComplaintHistoryRequestHeaders.accepts(
                headers.values("Authorization"),
                headers.values("Accept-Encoding"),
            ) &&
            headers.values("Accept") == listOf("application/json, application/problem+json") &&
            headers.values("Cache-Control") == listOf("no-store, no-transform") &&
            headers.values("Content-Type") == listOf("application/json") &&
            validUserAgent(headers.values("User-Agent")) &&
            validLength(headers.values("Content-Length"), bodyBytes) &&
            validKey(route, headers.values(Policy.IDEMPOTENCY_HEADER)) &&
            validPrecondition(route, headers.values("If-Match"), editTargetId)

    private fun validUserAgent(values: List<String>): Boolean = values.isEmpty() || values == listOf("ktor-client")

    private fun validLength(
        values: List<String>,
        bodyBytes: Long,
    ): Boolean = values.isEmpty() || values == listOf(bodyBytes.toString())

    private fun validKey(
        route: ComplaintMutationRoute,
        values: List<String>,
    ): Boolean =
        when (route) {
            ComplaintMutationRoute.CREATE, ComplaintMutationRoute.REPLY, ComplaintMutationRoute.EDIT ->
                values.size == 1 && KEY.matches(values.single())
            ComplaintMutationRoute.STATUS -> values.isEmpty()
        }

    private fun validPrecondition(
        route: ComplaintMutationRoute,
        values: List<String>,
        editTargetId: String?,
    ): Boolean {
        if (route != ComplaintMutationRoute.EDIT) return values.isEmpty()
        val target = editTargetId?.takeIf(TARGET::matches) ?: return false
        val value = values.singleOrNull()?.takeIf { it.length <= MAX_PRECONDITION_CHARACTERS } ?: return false
        val match = Regex("\"complaint-$target-v([1-9][0-9]{0,18})\"").matchEntire(value) ?: return false
        return match.groupValues[1].toLongOrNull() != null
    }

    private fun List<Pair<String, String>>.values(name: String): List<String> =
        filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
}
