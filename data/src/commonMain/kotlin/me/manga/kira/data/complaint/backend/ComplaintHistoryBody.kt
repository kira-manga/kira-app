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
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import kotlin.text.CharacterCodingException

/** Limit+one before UTF-8/JSON. Native queue limits are the separately qualified engine's responsibility. */
internal object ComplaintHistoryBody {
    suspend fun read(
        response: HttpResponse,
        expectedUrl: Url,
    ): AppResult<ComplaintHistoryPage> {
        val channel = response.bodyAsChannel()
        return try {
            if (response.call.request.url != expectedUrl || response.call.request.method != HttpMethod.Get) {
                invalidHistory()
            }
            val success = response.status == HttpStatusCode.OK
            if (!success && response.status.value !in MIN_ERROR_STATUS..MAX_ERROR_STATUS) invalidHistory()
            val maximum = if (success) MAX_LIST_BYTES else MAX_PROBLEM_BYTES
            val declared = headers(response, success, maximum)
            val text = utf8(channel, maximum, declared)
            if (success) {
                ComplaintHistoryResponse.decode(text)
            } else if (ComplaintHistoryProblem.valid(text, response.status.value)) {
                AppResult.Failure(AppError.Network.Http(response.status.value))
            } else {
                malformedHistory()
            }
        } catch (_: InvalidComplaintHistory) {
            malformedHistory()
        } catch (_: CharacterCodingException) {
            malformedHistory()
        } finally {
            channel.cancel()
        }
    }

    private fun headers(
        response: HttpResponse,
        success: Boolean,
        maximum: Int,
    ): Int? {
        val headers = response.headers
        if (headers.single(ComplaintBoundedResponse.CONTRACT_HEADER) != "1") invalidHistory()
        val cache =
            headers
                .single(HttpHeaders.CacheControl)
                ?.lowercase()
                ?.split(',')
                ?.map { it.trim() }
        if (cache == null || cache.size != CACHE.size || cache.toSet() != CACHE) invalidHistory()
        val encoding = headers.single(HttpHeaders.ContentEncoding)
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) invalidHistory()
        val pattern = if (success) JSON_MEDIA else PROBLEM_MEDIA
        if (!pattern.matches(headers.single(HttpHeaders.ContentType) ?: invalidHistory())) invalidHistory()
        if (headers.contains(HttpHeaders.Location) || headers.contains(HttpHeaders.ETag)) invalidHistory()
        if (response.status == HttpStatusCode.Unauthorized &&
            headers.single(HttpHeaders.WWWAuthenticate) != "Bearer realm=\"kira-complaints\""
        ) {
            invalidHistory()
        }
        return declaredLength(headers, maximum)
    }

    private fun declaredLength(
        headers: Headers,
        maximum: Int,
    ): Int? {
        val length = headers.single(HttpHeaders.ContentLength)
        val transfer = headers.single(HttpHeaders.TransferEncoding)
        if (transfer != null && (length != null || !transfer.equals("chunked", ignoreCase = true))) invalidHistory()
        if (length == null) return null
        if (length.isEmpty() || length.any { it !in '0'..'9' }) invalidHistory()
        return length.toIntOrNull()?.takeIf { it in 0..maximum } ?: invalidHistory()
    }

    private fun Headers.single(name: String): String? {
        val values = getAll(name) ?: return null
        if (values.size != 1 || values.single().length > MAX_HEADER) invalidHistory()
        return values.single()
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
            // Ktor may return -1 for a failed-closed channel without throwing its terminal cause.
            channel.closedCause?.let { throw it }
            if (count > maximum || declared != null && count != declared) invalidHistory()
            buffer.decodeToString(endIndex = count, throwOnInvalidSequence = true)
        } finally {
            buffer.fill(0)
        }
    }

    private const val MIN_ERROR_STATUS = 400
    private const val MAX_ERROR_STATUS = 599
    private const val MAX_LIST_BYTES = 2 * 1_024 * 1_024
    private const val MAX_PROBLEM_BYTES = 16 * 1_024
    private const val MAX_HEADER = 128
    private val CACHE = setOf("no-store", "no-transform")
    private val JSON_MEDIA = Regex("application/json(?:;[ \\t]*charset=(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
    private val PROBLEM_MEDIA =
        Regex(
            "application/problem\\+json(?:;[ \\t]*charset=(?:utf-8|\"utf-8\"))?",
            RegexOption.IGNORE_CASE,
        )
}
