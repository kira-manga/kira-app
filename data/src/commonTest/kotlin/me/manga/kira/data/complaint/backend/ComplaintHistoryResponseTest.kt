package me.manga.kira.data.complaint.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComplaintHistoryResponseTest {
    @Test
    fun knownClosedUnionKeepsOrdinaryCustomReplyDistinctFromNoticeThread() {
        val page = decodedHistory(
            items = listOf(
                historyItem(103), historyItem(102, kind = "REPLY", type = "CUSTOM"),
                historyItem(101, kind = "REPLY", type = "CUSTOM", noticeKey = "complaints.notice.synthetic"),
            ),
            notices = listOf(historyNotice()),
        )
        assertIs<ComplaintOwnerRow.Report>(page.items[0])
        assertEquals("Synthetic subject", assertIs<ComplaintOwnerRow.Reply>(page.items[1]).subject)
        assertEquals("complaints.notice.synthetic", assertIs<ComplaintOwnerRow.NoticeReply>(page.items[2]).noticeKey)
        assertEquals(setOf(ComplaintHistoryMismatch.NOTICE_KEY), page.mismatches)
        assertTrue(page.items.all { it.isContractRecognized })
    }

    @Test
    fun literalUnknownStatusIsRecognizedButFutureStatusAndTypeAreNotActionable() {
        val page = decodedHistory(
            items = listOf(
                historyItem(103, status = "UNKNOWN"),
                historyItem(102, status = "FUTURE_STATUS"),
                historyItem(101, type = "FUTURE_TYPE"),
            ),
        )
        val literal = assertIs<ComplaintOwnerRow.Report>(page.items[0])
        assertEquals(ComplaintStatus.UNKNOWN, assertIs<ComplaintHistoryStatus.Known>(literal.fields.status).value)
        assertTrue(literal.isContractRecognized)
        val futureStatus = assertIs<ComplaintOwnerRow.Report>(page.items[1])
        assertSame(ComplaintHistoryStatus.Unrecognized, futureStatus.fields.status)
        assertFalse(futureStatus.isContractRecognized)
        val futureType = assertIs<ComplaintOwnerRow.Report>(page.items[2])
        assertSame(ComplaintHistoryType.Unrecognized, futureType.type)
        assertFalse(futureType.isContractRecognized)
        assertEquals(setOf(ComplaintHistoryMismatch.STATUS, ComplaintHistoryMismatch.TYPE), page.mismatches)
        assertFalse(futureStatus.toString().contains("FUTURE_STATUS"))
        assertFalse(futureType.toString().contains("FUTURE_TYPE"))
    }

    @Test
    fun unknownKindRetainsOnlyItsBoundedEnvelopeWithoutProseTagOrParent() {
        val page = decodedHistory(items = listOf(historyItem(kind = "FUTURE_KIND")))
        val item = assertIs<UnknownComplaintItem>(page.items.single())
        assertEquals(historyId(100), item.id)
        assertEquals("FUTURE_KIND", item.kindToken)
        assertFalse(item.isContractRecognized)
        assertEquals(setOf(ComplaintHistoryMismatch.KIND), page.mismatches)
        assertFalse(page.items.single() is ComplaintOwnerRow.Content)
        assertEquals("UnknownComplaintItem(UNRECOGNIZED)", item.toString())
        val minimal = JsonObject(historyItem(kind = "FUTURE_KIND").filterKeys { it in COMMON_FIELDS })
        assertIs<UnknownComplaintItem>(decodedHistory(items = listOf(minimal)).items.single())
        malformed(historyResponse(items = listOf(JsonObject(minimal + ("installationId" to JsonPrimitive("hidden"))))))
        malformed(historyResponse(items = listOf(JsonObject(minimal + ("kind" to JsonPrimitive("x".repeat(65)))))))
    }

    @Test
    fun knownKindMixedMissingAndForeignFieldMatricesRemainHardFailures() {
        val report = historyItem()
        val reply = historyItem(kind = "REPLY")
        val noticeReply = historyItem(kind = "REPLY", type = "CUSTOM", noticeKey = "complaints.notice.synthetic")
        listOf(
            JsonObject(report - "subject"),
            JsonObject(report + ("subject" to JsonNull)),
            JsonObject(report + ("noticeKey" to JsonPrimitive("complaints.notice.synthetic"))),
            JsonObject(report + ("replyToId" to JsonPrimitive(historyId(1)))),
            JsonObject(reply + ("replyToId" to JsonNull)),
            JsonObject(noticeReply + ("subject" to JsonPrimitive("server subject"))),
            JsonObject(noticeReply + ("type" to JsonPrimitive("TECHNICAL"))),
            JsonObject(noticeReply + ("type" to JsonPrimitive("FUTURE_TYPE"))),
            JsonObject(report + ("userId" to JsonPrimitive("owner"))),
            historyNotice(),
        ).forEach { malformed(historyResponse(items = listOf(it))) }
        malformed(historyResponse(notices = listOf(JsonObject(historyNotice() + ("body" to JsonPrimitive("prose"))))))
        malformed(historyResponse(notices = listOf(JsonObject(historyNotice() + ("status" to JsonPrimitive("UNKNOWN"))))))
        malformed(historyResponse().dropLast(1) + ",\"future\":null}")
        malformed("""{"notices":[],"items":[],"items":[],"nextCursor":null}""")
        malformed(historyResponse(items = listOf(report)).replace("\"kind\":\"REPORT\"", "\"kind\":\"REPORT\",\"kind\":\"REPORT\""))
    }

    @Test
    fun canonicalIdTimestampActionTagAndKnownClosureSemanticsAreRequired() {
        val row = historyItem()
        listOf(
            "id" to JsonPrimitive("44444444-4444-4444-8444-0000000000AF"),
            "actionTag" to JsonPrimitive("\"complaint-${historyId(100)}-v2\""),
            "version" to JsonPrimitive(0),
            "createdAt" to JsonPrimitive("2026-09-16T08:09:10+00:00"),
            "updatedAt" to JsonPrimitive("2026-09-15T08:09:10Z"),
            "platform" to JsonPrimitive("DESKTOP"),
            "closureReason" to JsonPrimitive("Only CLOSED may carry this known field"),
        ).forEach { malformed(historyResponse(items = listOf(JsonObject(row + it)))) }
        malformed(historyResponse(items = listOf(JsonObject(historyItem(status = "CLOSED") + ("closureReason" to JsonNull)))))
        assertIs<ComplaintOwnerRow.Report>(decodedHistory(items = listOf(historyItem(status = "CLOSED"))).items.single())
    }

    @Test
    fun proseUsesIndependentCodePointUtf8NormalizationAndEscapedRowBounds() {
        val row = historyItem()
        val maximum = JsonObject(row + mapOf("subject" to JsonPrimitive("😀".repeat(200)), "body" to JsonPrimitive("😀".repeat(1_000))))
        assertIs<ComplaintOwnerRow.Report>(decodedHistory(items = listOf(maximum)).items.single())
        listOf(
            "subject" to JsonPrimitive("😀".repeat(201)),
            "body" to JsonPrimitive("😀".repeat(1_001)),
            "body" to JsonPrimitive(""),
            "body" to JsonPrimitive(" padded "),
            "body" to JsonPrimitive("line\r\nbreak"),
            "body" to JsonPrimitive("bad\u0000value"),
            "body" to JsonPrimitive("bad\ud800value"),
        ).forEach { malformed(historyResponse(items = listOf(JsonObject(row + it)))) }
        val allowed = "line\twith\nUnicode café 😀"
        val accepted = decodedHistory(items = listOf(JsonObject(row + ("body" to JsonPrimitive(allowed)))))
        assertEquals(allowed, assertIs<ComplaintOwnerRow.Report>(accepted.items.single()).fields.body)
        for (code in 0x80..0x9f) {
            val control = code.toChar()
            val body = "bad${control}value"
            val literal = historyResponse(items = listOf(row)).replace("Synthetic body", body)
            val escaped = literal.replace(control.toString(), "\\u" + code.toString(16).padStart(4, '0'))
            malformed(literal)
            malformed(escaped)
            // Exercise both independent guards; neither may rely on the other having run first.
            assertFailsWith<InvalidComplaintHistory> { ComplaintHistoryJson(literal).read() }
            assertFailsWith<InvalidComplaintHistory> { ComplaintHistoryJson(escaped).read() }
            assertFailsWith<InvalidComplaintHistory> { boundedText(body, 1, 100, 400) }
        }
        val text = row.toString()
        fun paddedRow(bytes: Int) = text.dropLast(1) + " ".repeat(bytes - text.encodeToByteArray().size) + "}"
        fun document(item: String) = """{"notices":[],"items":[$item],"nextCursor":null}"""
        assertIs<AppResult.Success<*>>(ComplaintHistoryResponse.decode(document(paddedRow(32 * 1_024))))
        malformed(document(paddedRow(32 * 1_024 + 1)))
    }

    @Test
    fun globalEnvelopeCollectionsDepthTokensAndCursorAreBoundedBeforeFallback() {
        val empty = historyResponse()
        assertIs<AppResult.Success<*>>(ComplaintHistoryResponse.decode(empty + " ".repeat(2 * 1_024 * 1_024 - empty.length)))
        malformed(empty + " ".repeat(2 * 1_024 * 1_024 + 1 - empty.length))
        malformed(historyResponse(items = List(51) { historyItem(it) }))
        malformed(historyResponse(notices = List(17) { historyNotice(it, "notice.$it") }))
        malformed(historyResponse(notices = listOf(historyNotice(1), historyNotice(2))))
        malformed(historyResponse(items = listOf(historyItem(1)), notices = listOf(historyNotice(1))))
        malformed("""{"notices":[],"items":[{"kind":"FUTURE","body":{"nested":{"deeper":[]}}}],"nextCursor":null}""")
        listOf("", "v1..a", "v1.a.", "v2.a.b", "v1.a.b=", "v1.a.%2B", "v1." + "a".repeat(2_044) + ".b").forEach {
            malformed(historyResponse(cursor = it))
        }
        assertTrue(validHistoryCursor("v1.A._")) // Opaque; no invented Base64 tail-bit/decoded-size rule.
    }

    @Test
    fun problemUsesActualNonEmptyApiErrorIncludingCodeLessDisabledAndSecurityBodies() {
        assertTrue(ComplaintHistoryProblem.installationNotFound(historyInstallationNotFoundProblem()))
        assertFalse(ComplaintHistoryProblem.installationNotFound(historyProblem(HttpStatusCode.NotFound)))
        assertFalse(ComplaintHistoryProblem.installationNotFound(historyInstallationNotFoundProblem().replace("INSTALLATION_NOT_FOUND", "NOT_FOUND")))
        assertFalse(ComplaintHistoryProblem.installationNotFound(historyInstallationNotFoundProblem().replace("Not Found", "Unavailable")))
        assertTrue(ComplaintHistoryProblem.valid(historyProblem(HttpStatusCode.NotFound), 404))
        assertTrue(ComplaintHistoryProblem.valid(historyProblem(HttpStatusCode.Unauthorized), 401))
        assertTrue(ComplaintHistoryProblem.valid(
            """{"type":"about:blank","title":"Bad Request","status":400,"errors":[{"code":"INVALID_CURSOR","message":"Invalid request."}]}""", 400,
        ))
        listOf(
            """{"type":"about:blank","title":"Not Found","status":401}""",
            """{"type":"about:blank","title":"Not Found","status":404,"code":"NOT_FOUND"}""",
            """{"type":"about:blank","title":"Not Found","status":404,"instance":"path"}""",
            """{"type":"about:blank","title":"Not Found","status":404,"detail":null}""",
            """{"type":"about:blank","title":"Not Found","status":404,"errors":[]}""",
            """{"type":"about:blank","title":"Not Found","status":404,"status":404}""",
            """{"type":"about:blank","title":"Not Found","status":404,"errors":[{"code":"FUTURE","message":"text"}]}""",
        ).forEach { assertFalse(ComplaintHistoryProblem.valid(it, 404)) }
    }

    private fun malformed(text: String) {
        val error = assertIs<AppResult.Failure>(ComplaintHistoryResponse.decode(text)).error
        assertIs<AppError.Network.Serialization>(error)
        assertNull(error.cause)
    }

    private companion object {
        val COMMON_FIELDS = setOf("id", "kind", "createdAt", "updatedAt")
    }
}
