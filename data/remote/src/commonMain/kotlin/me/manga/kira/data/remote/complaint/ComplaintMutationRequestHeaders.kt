package me.manga.kira.data.remote.complaint

import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Closed application-header set, checked before a native stack can add its own hop headers. */
internal object ComplaintMutationRequestHeaders {
    private val ALLOWED =
        setOf(
            "authorization",
            "accept",
            "accept-encoding",
            "cache-control",
            "content-type",
            "content-length",
            Policy.IDEMPOTENCY_HEADER.lowercase(),
        )
    private val KEY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    fun accepts(
        route: ComplaintMutationRoute,
        headers: List<Pair<String, String>>,
        bodyBytes: Long,
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
            validLength(headers.values("Content-Length"), bodyBytes) &&
            validKey(route, headers.values(Policy.IDEMPOTENCY_HEADER))

    private fun validLength(
        values: List<String>,
        bodyBytes: Long,
    ): Boolean = values.isEmpty() || values == listOf(bodyBytes.toString())

    private fun validKey(
        route: ComplaintMutationRoute,
        values: List<String>,
    ): Boolean =
        when (route) {
            ComplaintMutationRoute.CREATE -> values.size == 1 && KEY.matches(values.single())
            ComplaintMutationRoute.STATUS -> values.isEmpty()
        }

    private fun List<Pair<String, String>>.values(name: String): List<String> =
        filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
}
