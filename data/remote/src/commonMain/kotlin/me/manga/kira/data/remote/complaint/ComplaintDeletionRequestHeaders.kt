package me.manga.kira.data.remote.complaint

import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

/** Body authentication only; no cookie, Bearer, compression, transfer framing or caller User-Agent. */
internal object ComplaintDeletionRequestHeaders {
    private val ALLOWED =
        setOf(
            "accept",
            "accept-encoding",
            "cache-control",
            "content-type",
            "content-length",
            Policy.IDEMPOTENCY_HEADER.lowercase(),
            "user-agent",
        )
    private val KEY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    fun accepts(
        headers: List<Pair<String, String>>,
        bodyBytes: Long,
    ): Boolean =
        bodyBytes in 1L..Policy.MAX_REQUEST_BYTES.toLong() &&
            headers.size <= ALLOWED.size &&
            headers.all { (name, _) -> name.all { it in '!'..'~' } && name.lowercase() in ALLOWED } &&
            headers.values("Accept") == listOf("application/json, application/problem+json") &&
            headers.values("Accept-Encoding") == listOf("identity") &&
            headers.values("Cache-Control") == listOf("no-store, no-transform") &&
            headers.values("Content-Type") == listOf("application/json") &&
            validOptional(headers.values("User-Agent"), "ktor-client") &&
            validOptional(headers.values("Content-Length"), bodyBytes.toString()) &&
            headers.values(Policy.IDEMPOTENCY_HEADER).let { it.size == 1 && KEY.matches(it.single()) }

    private fun validOptional(
        values: List<String>,
        expected: String,
    ): Boolean = values.isEmpty() || values == listOf(expected)

    private fun List<Pair<String, String>>.values(name: String): List<String> =
        filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
}
