package me.manga.kira.data.complaint.backend

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.text.CharacterCodingException
import io.ktor.http.HttpStatusCode as Status
import me.manga.kira.data.complaint.backend.ComplaintSessionFailure as Failure

/** Downstream stream/decoder bound only; this does not qualify buffering or hidden policy inside a native engine. */
internal object ComplaintBoundedInstallationBody {
    private const val MAX_BYTES = ComplaintBoundedResponse.MAX_BYTES
    private const val CONTRACT_HEADER = ComplaintBoundedResponse.CONTRACT_HEADER
    private const val ENROLLMENT_LOCATION = "/api/v1/installations/me"
    private const val MAX_SELECTED_HEADER_CHARACTERS = 128
    private val CACHE_DIRECTIVES = setOf("no-store", "no-transform")
    private val JSON_MEDIA = Regex("application/json(?:;[ \\t]*charset=(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
    private val PROBLEM_MEDIA =
        Regex("application/problem\\+json(?:;[ \\t]*charset=(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)

    suspend fun read(
        response: HttpResponse,
        endpoint: ComplaintBackendEndpoint,
        route: ComplaintInstallationResponseRoute,
    ): BoundedInstallationBody {
        val channel = response.bodyAsChannel()
        return try {
            if (response.call.request.url != route.url(endpoint) || response.call.request.method != route.method) {
                reject(Failure.ORIGIN)
            }
            val length = headers(response, route)
            BoundedInstallationBody.Verified(utf8(channel, length))
        } catch (rejected: BoundedInstallationRejection) {
            BoundedInstallationBody.Rejected(rejected.reason)
        } catch (_: CharacterCodingException) {
            BoundedInstallationBody.Rejected(Failure.UTF8)
        } finally {
            channel.cancel()
        }
    }

    private fun headers(
        response: HttpResponse,
        route: ComplaintInstallationResponseRoute,
    ): Int? {
        val headers = response.headers
        if (headers.single(CONTRACT_HEADER) != "1") reject(Failure.CONTRACT)
        val cache =
            headers
                .single(HttpHeaders.CacheControl)
                ?.lowercase()
                ?.split(',')
                ?.map { it.trim() }
        if (cache == null || cache.toSet() != CACHE_DIRECTIVES || cache.size != CACHE_DIRECTIVES.size) {
            reject(Failure.HEADERS)
        }
        val encoding = headers.single(HttpHeaders.ContentEncoding)
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) reject(Failure.ENCODING)
        val media = headers.single(HttpHeaders.ContentType) ?: reject(Failure.MEDIA)
        val pattern = if (route.isSuccess(response.status)) JSON_MEDIA else PROBLEM_MEDIA
        if (!pattern.matches(media)) reject(Failure.MEDIA)
        requireStatusHeaders(response, route)
        return contentLength(headers)
    }

    private fun requireStatusHeaders(
        response: HttpResponse,
        route: ComplaintInstallationResponseRoute,
    ) {
        val headers = response.headers
        if (response.status == Status.Unauthorized &&
            headers.single(HttpHeaders.WWWAuthenticate) != "Bearer realm=\"kira-complaints\""
        ) {
            reject(Failure.HEADERS)
        }
        if (!route.isSuccess(response.status)) return
        if (headers.contains(HttpHeaders.ETag)) reject(Failure.HEADERS)
        if (route == ComplaintInstallationResponseRoute.ENROLLMENT) {
            val location = headers.single(HttpHeaders.Location)
            if ((response.status == Status.Created || location != null) && location != ENROLLMENT_LOCATION) {
                reject(Failure.HEADERS)
            }
        } else if (headers.contains(HttpHeaders.Location)) {
            reject(Failure.HEADERS)
        }
    }

    private fun contentLength(headers: Headers): Int? {
        val value = headers.single(HttpHeaders.ContentLength)
        val transfer = headers.single(HttpHeaders.TransferEncoding)
        if (transfer != null && (value != null || !transfer.equals("chunked", ignoreCase = true))) {
            reject(Failure.HEADERS)
        }
        if (value == null) return null
        if (value.isEmpty() || value.any { it !in '0'..'9' }) reject(Failure.LENGTH)
        val length = value.toLongOrNull() ?: reject(Failure.LENGTH)
        if (length > MAX_BYTES) reject(Failure.TOO_LARGE)
        return length.toInt()
    }

    private fun Headers.single(name: String): String? {
        val values = getAll(name) ?: return null
        if (values.size != 1) reject(Failure.HEADERS)
        return values.single().also {
            if (it.length > MAX_SELECTED_HEADER_CHARACTERS) reject(Failure.HEADERS)
        }
    }

    private suspend fun utf8(
        channel: ByteReadChannel,
        declaredLength: Int?,
    ): String {
        val buffer = ByteArray(MAX_BYTES + 1)
        return try {
            val size = readCapped(channel, buffer)
            if (size > MAX_BYTES) reject(Failure.TOO_LARGE)
            if (declaredLength != null && size != declaredLength) reject(Failure.LENGTH)
            buffer.decodeToString(endIndex = size, throwOnInvalidSequence = true)
        } finally {
            buffer.fill(0)
        }
    }

    private suspend fun readCapped(
        channel: ByteReadChannel,
        buffer: ByteArray,
    ): Int {
        var count = 0
        while (count < buffer.size) {
            currentCoroutineContext().ensureActive()
            val read = channel.readAvailable(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read == 0) yield() else count += read
        }
        return count
    }
}

private class BoundedInstallationRejection(
    val reason: Failure,
) : Exception()

private fun reject(reason: Failure): Nothing = throw BoundedInstallationRejection(reason)

/** Closed route/method/status vocabulary, never an arbitrary-URL policy. */
internal enum class ComplaintInstallationResponseRoute(
    val method: HttpMethod,
) {
    SESSION(HttpMethod.Post),
    BOOTSTRAP(HttpMethod.Get),
    ENROLLMENT(HttpMethod.Post),
    ;

    fun url(endpoint: ComplaintBackendEndpoint): Url =
        when (this) {
            SESSION -> endpoint.sessionUrl
            BOOTSTRAP -> endpoint.bootstrapUrl
            ENROLLMENT -> endpoint.enrollmentUrl
        }

    fun isSuccess(status: Status): Boolean = status == Status.OK || (this == ENROLLMENT && status == Status.Created)
}

/** Bounded/decoded text is not bootstrap authority, a session or permission to persist/dispatch. */
internal sealed interface BoundedInstallationBody {
    class Verified(
        val text: String,
    ) : BoundedInstallationBody {
        override fun toString(): String = "BoundedInstallationBody.Verified(redacted)"
    }

    class Rejected(
        val reason: Failure,
    ) : BoundedInstallationBody
}
