package me.manga.kira.data.complaint.backend

import io.ktor.http.Url

/** Syntax only; the registered history work and exact current session still authorize every dispatch. */
internal class ComplaintDetailRequest private constructor(
    val id: String,
) {
    fun url(endpoint: ComplaintBackendEndpoint): Url = Url("${endpoint.historyUrl}/$id")

    override fun toString(): String = "ComplaintDetailRequest(redacted)"

    companion object {
        private val CANONICAL_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** Seeded immutable notice IDs are canonical, but need not be version4. Never normalize aliases. */
        fun checked(id: String): ComplaintDetailRequest? =
            id.takeIf(CANONICAL_ID::matches)?.let(::ComplaintDetailRequest)
    }
}
