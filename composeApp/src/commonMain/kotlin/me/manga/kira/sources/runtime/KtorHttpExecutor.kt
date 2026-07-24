package me.manga.kira.sources.runtime

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.SourceHttpMethod
import me.manga.kira.sources.contracts.SourceRequest
import me.manga.kira.sources.contracts.SourceResponse

/**
 * The single Ktor-backed implementation of the engine's transport [HttpExecutor] port. It reuses the
 * app's configured [HttpClient] (with the shared logging, timeout, and content-negotiation policy),
 * translating the engine's transport-agnostic
 * [SourceRequest] into a Ktor call and the response into a [SourceResponse].
 *
 * Living here (the composition root) rather than in `:sources:engine` is deliberate: it keeps the
 * engine free of any HTTP-library dependency, so the engine stays unit-testable with a fake executor.
 * It is the live transport for every config-backed generic source (engine="generic" stanza) — exercised on each
 * generic home/featured/search/details/pages call.
 */
class KtorHttpExecutor(
    private val client: HttpClient,
) : HttpExecutor {

    override suspend fun execute(request: SourceRequest): SourceResponse {
        val response: HttpResponse = when (request.method) {
            SourceHttpMethod.GET -> client.get(request.url) { applyHeaders(request.headers) }
            SourceHttpMethod.POST_FORM -> client.submitForm(
                url = request.url,
                formParameters = Parameters.build { request.formBody?.forEach { (k, v) -> append(k, v) } },
            ) { applyHeaders(request.headers) }
            SourceHttpMethod.POST_JSON -> client.post(request.url) {
                applyHeaders(request.headers)
                contentType(ContentType.Application.Json)
                setBody(request.jsonBody ?: "{}")
            }
        }
        return SourceResponse(
            status = response.status.value,
            body = response.bodyAsText(),
            // Multi-value headers are comma-joined into one string. This is lossy/ambiguous for
            // Set-Cookie (cookie Expires dates legally contain commas), so it must not be used to
            // parse response cookies — no engine code consumes SourceResponse.headers today, but a
            // future reader of multi-value headers should switch the port to Map<String, List<String>>.
            headers = response.headers.entries().associate { (key, values) -> key to values.joinToString(", ") },
        )
    }

    private fun HttpRequestBuilder.applyHeaders(headers: Map<String, String>) {
        headers.forEach { (key, value) -> header(key, value) }
    }
}
