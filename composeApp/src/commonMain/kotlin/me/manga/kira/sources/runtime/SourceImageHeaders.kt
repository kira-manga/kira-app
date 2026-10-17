package me.manga.kira.sources.runtime

import coil3.Extras
import coil3.intercept.Interceptor
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.ImageResult
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor

/** Honor the same capture opt-out as source requests, including images with no explicit headers. */
internal suspend fun sourceImageHeaders(
    descriptor: RuntimeSourceDescriptor?,
    captured: suspend () -> Map<String, String>,
): Map<String, String> {
    if (descriptor == null) return emptyMap()
    if (!descriptor.usesCapturedHeaders) return descriptor.headers
    val stored =
        try {
            captured()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    return descriptor.headers + stored
}

private val sourceHeadersResolved = Extras.Key(false)

/** An explicit empty map is authoritative too; another source can share this image host. */
internal fun ImageRequest.Builder.resolvedSourceHeaders(headers: Map<String, String>): ImageRequest.Builder =
    apply {
        extras[sourceHeadersResolved] = true
        httpHeaders(NetworkHeaders.Builder().apply {
            headers.forEach { (name, value) -> add(name, value) }
        }.build())
    }

/** Only plain, unresolved image requests may infer their headers from a host. */
internal class CoilSourceHeaderInterceptor(
    private val headersForHost: suspend (String) -> Map<String, String>,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.extras[sourceHeadersResolved] == true || request.httpHeaders.asMap().isNotEmpty()) return chain.proceed()
        val data = request.data as? String ?: request.data.toString()
        val host = runCatching { Url(data).host.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return chain.proceed()
        val headers = headersForHost(host)
        if (headers.isEmpty()) return chain.proceed()
        return chain.withRequest(request.newBuilder().resolvedSourceHeaders(headers).build()).proceed()
    }
}
