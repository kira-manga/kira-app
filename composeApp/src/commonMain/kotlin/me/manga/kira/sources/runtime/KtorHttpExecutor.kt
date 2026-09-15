package me.manga.kira.sources.runtime

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.utils.io.cancel
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.charsets.decode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.io.Buffer
import kotlinx.serialization.SerializationException
import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.SourceHttpMethod
import me.manga.kira.sources.contracts.SourceRequest
import me.manga.kira.sources.contracts.SourceResponse

/**
 * The Ktor-backed [HttpExecutor], bounding decoded response bodies to 4 MiB before text decoding.
 * Borrows an uncached [HttpClient] with the app's logging, timeout, and content-negotiation policy;
 * the caller owns its lifetime. HttpCache is refused because it can buffer before this reader runs.
 *
 * Living here (the composition root) rather than in `:sources:engine` is deliberate: it keeps the
 * engine free of any HTTP-library dependency, so the engine stays unit-testable with a fake executor.
 * It is the live transport for every config-backed generic source (engine="generic" stanza) — exercised on each
 * generic home/featured/search/details/pages call.
 */
class KtorHttpExecutor(
    private val client: HttpClient,
) : HttpExecutor {
    init {
        require(client.pluginOrNull(HttpCache) == null) { "generic sources require an uncached HTTP client" }
    }

    override suspend fun execute(request: SourceRequest): SourceResponse =
        client.prepareRequest(request.url) { applyRequest(request) }.execute { response ->
            SourceResponse(
                status = response.status.value,
                body = response.boundedBody(),
                // Preserve the existing comma-joined port; Set-Cookie must not be parsed from it
                // because cookie Expires dates also contain commas.
                headers = response.headers.entries().associate { (key, values) -> key to values.joinToString(", ") },
            )
        }

    private fun HttpRequestBuilder.applyRequest(request: SourceRequest) {
        request.headers.forEach { (key, value) -> header(key, value) }
        when (request.method) {
            SourceHttpMethod.GET -> method = HttpMethod.Get
            SourceHttpMethod.POST_FORM -> {
                method = HttpMethod.Post
                setBody(FormDataContent(Parameters.build { request.formBody?.forEach { (k, v) -> append(k, v) } }))
            }
            SourceHttpMethod.POST_JSON -> {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(request.jsonBody ?: "{}")
            }
        }
    }

    private suspend fun HttpResponse.boundedBody(): String {
        headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let(::requireBoundedSize)
        // Match Ktor bodyAsText(): the declared charset wins; an absent charset means UTF-8.
        val decoder = (charset() ?: Charsets.UTF_8).newDecoder()
        val channel = bodyAsChannel() // Retain Ktor's content-decoding pipeline, not raw wire bytes.
        try {
            val bytes = ByteArray(READ_CHUNK_BYTES)
            val input = Buffer()
            var size = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = channel.readAvailable(bytes, 0, minOf(bytes.size, MAX_RESPONSE_BYTES - size + 1))
                if (count == -1) break
                size += count
                requireBoundedSize(size.toLong()) // Reject the extra byte without awaiting EOF.
                input.write(bytes, 0, count)
            }
            channel.closedCause?.let { throw it }
            currentCoroutineContext().ensureActive()
            return decoder.decode(input)
        } finally {
            channel.cancel()
        }
    }

    private fun requireBoundedSize(size: Long) {
        if (size > MAX_RESPONSE_BYTES) {
            // The pinned engine maps SerializationException to the app's typed invalid-response error.
            throw SerializationException("generic source response exceeds the configured size limit")
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val READ_CHUNK_BYTES = 8 * 1024
    }
}
