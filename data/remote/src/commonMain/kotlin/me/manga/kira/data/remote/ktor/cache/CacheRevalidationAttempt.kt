package me.manga.kira.data.remote.ktor.cache

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// Two Ktor storage lookups, each bounded by the owner's four-variant default. Fail closed beyond it.
private const val MAX_VALIDATOR_CANDIDATES = 8
private val callerOnlyConditions = listOf(HttpHeaders.IfMatch, HttpHeaders.IfUnmodifiedSince, HttpHeaders.IfRange)
private val allConditions = callerOnlyConditions + listOf(HttpHeaders.IfNoneMatch, HttpHeaders.IfModifiedSince)

/** One send's fixed-size validator digests; never pins cache metadata or bodies outside the owner. */
internal class CacheRevalidationAttempt(
    val owner: ManagedHttpCache,
    private val url: Url,
    private val eligible: Boolean,
    val bypassStorage: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CacheRevalidationAttempt>

    private val candidates = mutableSetOf<CacheValidators>()
    private var overflow = false
    private var responseReceived = false
    private var failedResponse: HttpResponse? = null
    private var generatedValidators: CacheValidators? = null

    fun observeValidators(
        candidateUrl: Url,
        etag: String?,
        lastModified: String?,
    ) {
        if (!eligible || overflow || responseReceived) return
        if (candidateUrl != url || (etag == null && lastModified == null)) return
        candidates += CacheValidators(etag?.encodeUtf8()?.sha256(), lastModified?.encodeUtf8()?.sha256())
        if (candidates.size > MAX_VALIDATOR_CANDIDATES) {
            candidates.clear()
            overflow = true
        }
    }

    fun observeResponse(response: HttpResponse) {
        responseReceived = true
        if (response.status != HttpStatusCode.NotModified) return
        failedResponse = response
        if (!eligible || response.call.request.url != url || response.call.request.method != HttpMethod.Get) return
        val headers = response.call.request.headers
        if (callerOnlyConditions.none(headers::contains)) {
            generatedValidators = candidates.firstOrNull { it.matches(headers::getAll) }
        }
    }

    fun retryValidators(request: HttpRequestBuilder): CacheValidators? {
        if (!eligible || Url(request.url) != url || !request.isEmptyGet()) return null
        return if (callerOnlyConditions.any(request.headers::contains)) {
            null
        } else {
            generatedValidators?.takeIf { it.matches(request.headers::getAll) }
        }
    }

    @OptIn(InternalAPI::class)
    fun disposeFailedResponse(cause: Throwable) {
        val response = failedResponse ?: return
        val cancellation = CancellationException("Discarding failed cache revalidation response", cause)
        try {
            // Discard this call as cancellation, not a new producer failure of the shared request Job.
            response.rawContent.cancel(cancellation)
        } finally {
            response.call.cancel(cancellation)
            failedResponse = null
        }
    }

    fun releaseResponse() {
        failedResponse = null
    }
}

internal data class CacheValidators(
    private val etag: ByteString?,
    private val lastModified: ByteString?,
) {
    fun matches(values: (String) -> List<String>?): Boolean =
        matchesField(etag, values(HttpHeaders.IfNoneMatch)) &&
            matchesField(lastModified, values(HttpHeaders.IfModifiedSince))

    fun removeFrom(headers: HeadersBuilder) {
        check(matches(headers::getAll)) { "Cache-generated validators changed before recovery" }
        if (etag != null) headers.remove(HttpHeaders.IfNoneMatch)
        if (lastModified != null) headers.remove(HttpHeaders.IfModifiedSince)
    }

    private fun matchesField(
        expected: ByteString?,
        values: List<String>?,
    ): Boolean = if (expected == null) values == null else values?.singleOrNull()?.encodeUtf8()?.sha256() == expected
}

internal fun HttpRequestBuilder.isCacheRecoveryEligible(): Boolean = isEmptyGet() && allConditions.none(headers::contains)

private fun HttpRequestBuilder.isEmptyGet(): Boolean = method == HttpMethod.Get && body is OutgoingContent.NoContent
