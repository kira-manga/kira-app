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
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

/** Strict downstream bound. Native factories separately bound bytes before Ktor queues them. */
internal object InstallationDeletionBody {
    suspend fun read(response: HttpResponse, expected: Url): InstallationDeletionDocument {
        val channel = response.bodyAsChannel()
        return try {
            if (response.call.request.url != expected || response.call.request.method != HttpMethod.Post) invalidHistory()
            val empty = response.status == HttpStatusCode.Accepted || response.status == HttpStatusCode.NoContent
            if (!empty && response.status.value !in MIN_ERROR_STATUS..MAX_ERROR_STATUS) invalidHistory()
            val maximum = if (empty) 0 else Policy.MAX_PROBLEM_BYTES
            val declared = headers(response, empty, maximum)
            InstallationDeletionDocument(
                response.status.value,
                utf8(channel, maximum, declared),
                retryAfter(response),
            )
        } finally {
            channel.cancel()
        }
    }

    private fun headers(response: HttpResponse, empty: Boolean, maximum: Int): Int? {
        val headers = response.headers
        if (headers.single(ComplaintBoundedResponse.CONTRACT_HEADER) != "1") invalidHistory()
        val cache = headers.single(HttpHeaders.CacheControl)?.lowercase()?.split(',')?.map { it.trim(' ', '\t') }
        if (cache == null || cache.size != CACHE.size || cache.toSet() != CACHE) invalidHistory()
        val encoding = headers.single(HttpHeaders.ContentEncoding)
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) invalidHistory()
        val media = headers.single(HttpHeaders.ContentType)
        if (empty) {
            if (media != null && !JSON.matches(media)) invalidHistory()
        } else if (media == null || !PROBLEM.matches(media)) {
            invalidHistory()
        }
        if (headers.single(HttpHeaders.Location) != null || headers.single(HttpHeaders.ETag) != null ||
            headers.single(HttpHeaders.WWWAuthenticate) != null
        ) {
            invalidHistory()
        }
        return length(headers, maximum)
    }

    private fun retryAfter(response: HttpResponse): Int? {
        val value = response.headers.single(HttpHeaders.RetryAfter)
        if (response.status != HttpStatusCode.Accepted) return null
        if (value.isNullOrEmpty() || value.any { it !in '0'..'9' }) invalidHistory()
        return value.toIntOrNull()?.takeIf { it in Policy.MIN_RETRY_SECONDS..Policy.MAX_RETRY_SECONDS }
            ?: invalidHistory()
    }

    private fun length(headers: Headers, maximum: Int): Int? {
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

    private suspend fun utf8(channel: ByteReadChannel, maximum: Int, declared: Int?): String {
        val bytes = ByteArray(maximum + 1)
        return try {
            var count = 0
            while (count < bytes.size) {
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) yield() else count += read
            }
            channel.closedCause?.let { throw it }
            if (count > maximum || declared != null && count != declared) invalidHistory()
            bytes.decodeToString(endIndex = count, throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }

    private const val MIN_ERROR_STATUS = 400
    private const val MAX_ERROR_STATUS = 599
    private const val MAX_HEADER = 128
    private val CACHE = setOf("no-store", "no-transform")
    private val JSON = Regex("application/json(?:;[ \\t]*charset=(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
    private val PROBLEM =
        Regex("application/problem\\+json(?:;[ \\t]*charset=(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
}

internal class InstallationDeletionDocument(
    val status: Int,
    val text: String,
    val retryAfterSeconds: Int?,
) {
    override fun toString(): String = "InstallationDeletionDocument(redacted)"
}
