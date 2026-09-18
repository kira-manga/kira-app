package me.manga.kira.data.complaint.backend

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComplaintDetailResponseTest {
    @Test
    fun reportReplyAndNoticeThreadRemainDistinctSingleRootProjections() {
        val report = assertIs<ComplaintOwnerRow.Report>(owned(historyItem()))
        assertEquals("Synthetic subject", report.subject)
        assertEquals(detailActionTag(), report.fields.actionTag)
        val reply =
            JsonObject(historyItem(kind = "REPLY", type = "CUSTOM") + ("replyToId" to JsonPrimitive(DETAIL_NOTICE_ID)))
        assertEquals(DETAIL_NOTICE_ID, assertIs<ComplaintOwnerRow.Reply>(owned(reply)).replyToId)
        val noticeReply =
            historyItem(kind = "REPLY", type = "CUSTOM", noticeKey = "complaints.notice.future")
        val thread = assertIs<ComplaintOwnerRow.NoticeReply>(owned(noticeReply))
        assertEquals("complaints.notice.future", thread.noticeKey)
        assertTrue(thread.isContractRecognized)
        malformed(historyResponse(items = listOf(historyItem())))
        malformed("[${historyItem()}]")
    }

    @Test
    fun noticeAcceptsCanonicalNonV4IdButNeverProseDiagnosticsOrAnyTag() {
        val row = detailNotice()
        val result = assertIs<ComplaintDetail.Notice>(decodedDetail(row))
        assertEquals(DETAIL_NOTICE_ID, result.item.id)
        assertEquals("complaints.notice.synthetic", result.item.noticeKey)
        assertEquals("ComplaintDetail.Notice(redacted)", result.toString())
        listOf("subject", "body", "actionTag", "appVersion", "replyToId").forEach { field ->
            malformed(JsonObject(row + (field to JsonNull)).toString(), DETAIL_NOTICE_ID, null)
        }
        malformed(row.toString(), DETAIL_NOTICE_ID, detailActionTag(DETAIL_NOTICE_ID))
        malformed(JsonObject(row + ("status" to JsonPrimitive("FUTURE_STATUS"))).toString(), DETAIL_NOTICE_ID, null)
        malformed(JsonObject(row - "noticeKey").toString(), DETAIL_NOTICE_ID, null)
    }

    @Test
    fun exactRequestedIdBodyVersionAndSingleStrongHttpTagMustAgree() {
        val row = historyItem()
        val text = row.toString()
        malformed(text, historyId(101))
        listOf(
            null,
            "",
            "W/${detailActionTag()}",
            "${detailActionTag()}, ${detailActionTag()}",
            detailActionTag(historyId(101)),
            detailActionTag(version = 2),
        ).forEach { malformed(text, tag = it) }
        listOf(0L, -1L).forEach { malformed(JsonObject(row + ("version" to JsonPrimitive(it))).toString()) }
        malformed(text.replace("\"version\":1", "\"version\":1.0"))
        malformed(text.replace("\"version\":1", "\"version\":\"1\""))
        malformed(text.replace("\"version\":1", "\"version\":9223372036854775808"))
        val maximum =
            JsonObject(
                row +
                    mapOf(
                        "version" to JsonPrimitive(Long.MAX_VALUE),
                        "actionTag" to JsonPrimitive(detailActionTag(version = Long.MAX_VALUE)),
                    ),
            )
        assertEquals(Long.MAX_VALUE, assertIs<ComplaintOwnerRow.Report>(owned(maximum)).fields.version)
    }

    @Test
    fun unknownKindDiscardsContentTagAndParentWhileFutureTypeAndStatusAreNonActionable() {
        val row = historyItem(kind = "FUTURE_KIND")
        val unknownRow = owned(row)
        val unknown = assertIs<UnknownComplaintItem>(unknownRow)
        assertFalse(unknown.isContractRecognized)
        assertEquals("UnknownComplaintItem(UNRECOGNIZED)", unknown.toString())
        val minimum = JsonObject(row.filterKeys { it in setOf("id", "kind", "createdAt", "updatedAt") })
        assertIs<UnknownComplaintItem>(owned(minimum))
        val status = assertIs<ComplaintOwnerRow.Report>(owned(historyItem(status = "FUTURE_STATUS")))
        assertSame(ComplaintHistoryStatus.Unrecognized, status.fields.status)
        assertFalse(status.isContractRecognized)
        val type = assertIs<ComplaintOwnerRow.Report>(owned(historyItem(type = "FUTURE_TYPE")))
        assertSame(ComplaintHistoryType.Unrecognized, type.type)
        assertFalse(type.isContractRecognized)
        val literal = assertIs<ComplaintOwnerRow.Report>(owned(historyItem(status = "UNKNOWN")))
        assertEquals(ComplaintStatus.UNKNOWN, assertIs<ComplaintHistoryStatus.Known>(literal.fields.status).value)
        assertTrue(literal.isContractRecognized)
        malformed(historyItem(type = "FUTURE_TYPE").toString(), tag = null)
    }

    @Test
    fun mixedMissingExtraDuplicateAndMalformedScalarsRemainClosedFailures() {
        val row = historyItem()
        val noticeReply = historyItem(kind = "REPLY", type = "CUSTOM", noticeKey = "complaints.notice.future")
        listOf(
            JsonObject(row - "body"),
            JsonObject(row + ("subject" to JsonNull)),
            JsonObject(row + ("replyToId" to JsonPrimitive(DETAIL_NOTICE_ID))),
            JsonObject(row + ("installationId" to JsonPrimitive("hidden"))),
            JsonObject(row + ("noticeKey" to JsonPrimitive("notice.future"))),
            JsonObject(historyItem(kind = "REPLY") + ("replyToId" to JsonNull)),
            JsonObject(noticeReply + ("subject" to JsonPrimitive("server prose"))),
            JsonObject(noticeReply + ("type" to JsonPrimitive("FUTURE_TYPE"))),
            JsonObject(row + ("version" to JsonPrimitive(true))),
            JsonObject(row + ("kind" to JsonNull)),
        ).forEach { malformed(it.toString()) }
        malformed(row.toString().replace("\"kind\":\"REPORT\"", "\"kind\":\"REPORT\",\"kind\":\"REPORT\""))
        malformed(row.toString().replace("\"id\":", "\"\\u0069d\":\"${historyId(100)}\",\"id\":"))
    }

    @Test
    fun canonicalContentIdDatesDiagnosticsAndClosureRulesAreStillTheListRules() {
        val row = historyItem()
        listOf(
            "id" to JsonPrimitive(historyId(175).uppercase()),
            "createdAt" to JsonPrimitive("2026-09-16T08:09:10+00:00"),
            "updatedAt" to JsonPrimitive("2026-09-15T08:09:10Z"),
            "platform" to JsonPrimitive("DESKTOP"),
            "closureReason" to JsonPrimitive("Only a closed row may carry a known reason"),
            "body" to JsonPrimitive(" padded "),
            "body" to JsonPrimitive("bad\u0085value"),
            "body" to JsonPrimitive("bad\ud800value"),
        ).forEach { malformed(JsonObject(row + it).toString()) }
        val nonV4 =
            JsonObject(
                row +
                    mapOf(
                        "id" to JsonPrimitive(DETAIL_NOTICE_ID),
                        "actionTag" to JsonPrimitive(detailActionTag(DETAIL_NOTICE_ID)),
                    ),
            )
        malformed(nonV4.toString(), DETAIL_NOTICE_ID, detailActionTag(DETAIL_NOTICE_ID))
        assertIs<ComplaintOwnerRow.Report>(owned(historyItem(status = "CLOSED")))
        malformed(JsonObject(historyItem(status = "CLOSED") + ("closureReason" to JsonNull)).toString())
    }

    @Test
    fun escapedRootHasItsOwnThirtyTwoKibibyteLimitBeforeDecoding() {
        val row = JsonObject(historyItem() + ("body" to JsonPrimitive("😀".repeat(1_000))))
        val escaped = row.toString().replace("😀", "\\ud83d\\ude00")
        val exact = paddedDetail(32 * 1_024, escaped).decodeToString()
        val result = ComplaintDetailResponse.decode(exact, detailRequest(), detailActionTag())
        assertIs<AppResult.Success<*>>(result)
        malformed(exact + " ")
        malformed(JsonObject(historyItem() + ("body" to JsonPrimitive("😀".repeat(1_001)))).toString())
    }

    @Test
    fun checkedRequestRejectsUrlAliasesButAllowsCanonicalNoticeIdsAndRedactsItself() {
        assertEquals(DETAIL_NOTICE_ID, detailRequest(DETAIL_NOTICE_ID).id)
        listOf(
            "",
            historyId(175).uppercase(),
            " ${historyId(100)}",
            "${historyId(100)}/",
            "${historyId(100)}?limit=1",
            "${historyId(100)}#fragment",
            "../${historyId(100)}",
            "%34" + historyId(100).drop(1),
        ).forEach { assertNull(ComplaintDetailRequest.checked(it)) }
        assertEquals("ComplaintDetailRequest(redacted)", detailRequest().toString())
        assertEquals("ComplaintDetail.Owned(redacted)", decodedDetail().toString())
    }

    private fun owned(row: JsonObject): ComplaintOwnerRow = assertIs<ComplaintDetail.Owned>(decodedDetail(row)).item

    private fun malformed(
        text: String,
        id: String = historyId(100),
        tag: String? = detailActionTag(),
    ) {
        val failure = assertIs<AppResult.Failure>(ComplaintDetailResponse.decode(text, detailRequest(id), tag))
        assertIs<AppError.Network.Serialization>(failure.error)
        assertNull(failure.error.cause)
    }
}
