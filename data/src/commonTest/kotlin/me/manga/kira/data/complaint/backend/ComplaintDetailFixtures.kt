package me.manga.kira.data.complaint.backend

import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Canonical but deliberately not v4: immutable notices and reply parents have no v4 restriction. */
internal const val DETAIL_NOTICE_ID = "55555555-5555-1555-8555-555555555555"

internal fun detailRequest(id: String = historyId(100)): ComplaintDetailRequest =
    assertNotNull(ComplaintDetailRequest.checked(id))

internal fun detailActionTag(
    id: String = historyId(100),
    version: Long = 1,
): String = "\"complaint-$id-v$version\""

internal fun detailHeaders(
    id: String = historyId(100),
    version: Long = 1,
    change: HeadersBuilder.() -> Unit = {},
): Headers =
    sessionHeaders {
        append(HttpHeaders.ETag, detailActionTag(id, version))
        change()
    }

internal fun detailNotice(): JsonObject = JsonObject(historyNotice() + ("id" to JsonPrimitive(DETAIL_NOTICE_ID)))

/** Actual NON_EMPTY ApiError shape with the single exact resource code, not session-not-found. */
internal fun detailNotFoundProblem(): String =
    """{"type":"about:blank","title":"Not Found","status":404,""" +
        """"errors":[{"code":"NOT_FOUND","message":"Not found."}]}"""

internal fun decodedDetail(
    row: JsonObject = historyItem(),
    id: String = row.historyString("id"),
    tag: String? = (row["actionTag"] as? JsonPrimitive)?.content,
): ComplaintDetail {
    val read =
        assertIs<AppResult.Success<ComplaintDetailRead>>(
            ComplaintDetailResponse.decode(row.toString(), detailRequest(id), tag),
        ).value
    return read.detail
}

internal fun paddedDetail(
    bytes: Int,
    text: String = historyItem().toString(),
): ByteArray = (text + " ".repeat(bytes - text.encodeToByteArray().size)).encodeToByteArray()
