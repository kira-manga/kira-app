package me.manga.kira.data.complaint.backend

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** A bounded transport document, not a parsed receipt or pending-deletion authority. */
internal class ComplaintMutationDocument(
    val status: Int,
    val text: String,
    val location: String?,
    val etag: String?,
) {
    override fun toString(): String = "ComplaintMutationDocument(redacted)"
}

/** Only the fixed two POSTs; status shares the checked deployment origin/prefix, never a caller URL. */
internal enum class ComplaintMutationRoute(
    val success: HttpStatusCode,
) {
    CREATE(HttpStatusCode.Created),
    STATUS(HttpStatusCode.OK),
    ;

    fun url(endpoint: ComplaintBackendEndpoint): Url =
        when (this) {
            CREATE -> endpoint.historyUrl
            STATUS -> Url(endpoint.historyUrl.toString().removeSuffix(Policy.CREATE_PATH) + Policy.STATUS_PATH)
        }
}

/** Limit+one and failed-EOF checks precede strict UTF-8/JSON; native queue policy is a separate gate. */
internal object ComplaintMutationBody {
    suspend fun read(
        response: HttpResponse,
        expectedUrl: Url,
        route: ComplaintMutationRoute,
    ): ComplaintMutationDocument {
        val channel = response.bodyAsChannel()
        return try {
            if (response.call.request.url != expectedUrl || response.call.request.method != HttpMethod.Post) {
                invalidHistory()
            }
            val success = response.status == route.success
            if (!success && response.status.value !in MIN_ERROR_STATUS..MAX_ERROR_STATUS) invalidHistory()
            val maximum =
                if (success && route == ComplaintMutationRoute.CREATE) {
                    Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES
                } else {
                    Policy.MAX_STATUS_OR_PROBLEM_BYTES
                }
            val declared = headers(response, route, success, maximum)
            ComplaintMutationDocument(
                response.status.value,
                utf8(channel, maximum, declared),
                response.headers[HttpHeaders.Location],
                response.headers[HttpHeaders.ETag],
            )
        } finally {
            channel.cancel()
        }
    }

    private fun headers(
        response: HttpResponse,
        route: ComplaintMutationRoute,
        success: Boolean,
        maximum: Int,
    ): Int? {
        val headers = response.headers
        if (headers.single(ComplaintBoundedResponse.CONTRACT_HEADER) != "1") invalidHistory()
        val cache = headers.single(HttpHeaders.CacheControl)?.lowercase()?.split(',')?.map { it.trim(' ', '\t') }
        if (cache == null || cache.size != CACHE.size || cache.toSet() != CACHE) invalidHistory()
        val encoding = headers.single(HttpHeaders.ContentEncoding)
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) invalidHistory()
        val media = if (success) JSON_MEDIA else PROBLEM_MEDIA
        if (!media.matches(headers.single(HttpHeaders.ContentType) ?: invalidHistory())) invalidHistory()
        responseHeaders(response, route, success)
        return length(headers, maximum)
    }

    private fun responseHeaders(
        response: HttpResponse,
        route: ComplaintMutationRoute,
        success: Boolean,
    ) {
        val headers = response.headers
        val location = headers.single(HttpHeaders.Location)
        val etag = headers.single(HttpHeaders.ETag)
        if (success && route == ComplaintMutationRoute.CREATE) {
            if (location == null || etag == null) invalidHistory()
        } else if (location != null || etag != null) {
            invalidHistory()
        }
        val challenge = headers.single(HttpHeaders.WWWAuthenticate)
        if (response.status == HttpStatusCode.Unauthorized) {
            if (challenge != "Bearer realm=\"kira-complaints\"") invalidHistory()
        } else if (challenge != null) {
            invalidHistory()
        }
        // Never used to retry; still reject duplicate/oversized selected security headers.
        headers.single(HttpHeaders.RetryAfter)
    }

    private fun length(
        headers: Headers,
        maximum: Int,
    ): Int? {
        val value = headers.single(HttpHeaders.ContentLength)
        val transfer = headers.single(HttpHeaders.TransferEncoding)
        if (transfer != null && (value != null || !transfer.equals("chunked", ignoreCase = true))) invalidHistory()
        if (value == null) return null
        if (value.isEmpty() || value.any { it !in '0'..'9' }) invalidHistory()
        return value.toIntOrNull()?.takeIf { it in 0..maximum } ?: invalidHistory()
    }

    private fun Headers.single(name: String): String? {
        val values = getAll(name) ?: return null
        if (values.size != 1) invalidHistory()
        return values.single().also { value ->
            if (value.length > MAX_HEADER || value.any { it !in ' '..'~' && it != '\t' }) invalidHistory()
        }
    }

    private suspend fun utf8(
        channel: ByteReadChannel,
        maximum: Int,
        declared: Int?,
    ): String {
        val buffer = ByteArray(maximum + 1)
        return try {
            var count = 0
            while (count < buffer.size) {
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) yield() else count += read
            }
            channel.closedCause?.let { throw it }
            if (count > maximum || declared != null && count != declared) invalidHistory()
            buffer.decodeToString(endIndex = count, throwOnInvalidSequence = true)
        } finally {
            buffer.fill(0)
        }
    }

    private const val MIN_ERROR_STATUS = 400
    private const val MAX_ERROR_STATUS = 599
    private const val MAX_HEADER = 128
    private val CACHE = setOf("no-store", "no-transform")
    private val JSON_MEDIA = Regex("application/json(?:;[ \\t]*charset=(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
    private val PROBLEM_MEDIA =
        Regex("application/problem\\+json(?:;[ \\t]*charset=(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
}
