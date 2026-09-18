package me.manga.kira.data.complaint.backend

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.utils.io.cancel
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import kotlin.text.CharacterCodingException

/** Same strict read framing/UTF-8 cleanup as list, but never its larger page budget or page decoder. */
internal object ComplaintDetailBody {
    suspend fun read(
        response: HttpResponse,
        expectedUrl: Url,
        request: ComplaintDetailRequest,
    ): AppResult<ComplaintDetailRead> {
        val channel = response.bodyAsChannel()
        return try {
            if (response.call.request.url != expectedUrl || response.call.request.method != HttpMethod.Get) {
                invalidHistory()
            }
            val success = response.status == HttpStatusCode.OK
            if (!success && response.status.value !in MIN_ERROR_STATUS..MAX_ERROR_STATUS) invalidHistory()
            val maximum = if (success) ComplaintDetailResponse.MAX_BYTES else MAX_PROBLEM_BYTES
            val declared = ComplaintHistoryBody.headers(response, success, maximum, allowEtag = success)
            val tag = ComplaintHistoryBody.detailTag(response.headers)
            val text = ComplaintHistoryBody.utf8(channel, maximum, declared)
            if (success) ComplaintDetailResponse.decode(text, request, tag) else problem(text, response.status.value)
        } catch (_: InvalidComplaintHistory) {
            malformedHistory()
        } catch (_: CharacterCodingException) {
            malformedHistory()
        } finally {
            channel.cancel()
        }
    }

    private fun problem(
        text: String,
        status: Int,
    ): AppResult<ComplaintDetailRead> =
        when {
            !ComplaintHistoryProblem.valid(text, status) -> malformedHistory()
            status == HttpStatusCode.NotFound.value && ComplaintHistoryProblem.detailNotFound(text) ->
                AppResult.Success(ComplaintDetailRead(ComplaintDetail.Unavailable))
            else -> AppResult.Failure(AppError.Network.Http(status))
        }

    private const val MIN_ERROR_STATUS = 400
    private const val MAX_ERROR_STATUS = 599
    private const val MAX_PROBLEM_BYTES = 16 * 1_024
}
