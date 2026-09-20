package me.manga.kira.data.complaint.backend

import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class ComplaintEditRequestTest {
    @Test
    fun editedBodyHasItsOwnScalarAndByteBoundsWithoutWeakeningCreation() {
        val maximum = mobileEditRequest(subject = "🙂".repeat(200), body = "🙂".repeat(1000))
        assertEquals(800, assertNotNull(maximum.subject).encodeToByteArray().size)
        assertEquals(4000, maximum.body.encodeToByteArray().size)
        assertEquals("x", ComplaintReportTextRules.editBody("x"))
        assertEquals("x", ComplaintReportTextRules.replyBody("x"))
        assertFailsWith<ComplaintReportTextRejected> {
            ComplaintReportTextRules.normalize("x", ComplaintReportField.BODY)
        }
        assertFailsWith<ComplaintReportTextRejected> { ComplaintReportTextRules.replyBody("x".repeat(501)) }
        assertFailsWith<ComplaintReportTextRejected> {
            ComplaintReportTextRules.normalize("x".repeat(501), ComplaintReportField.BODY)
        }
        assertEquals("ComplaintEditRequest(redacted)", maximum.toString())
    }

    @Test
    fun oneOverControlsSurrogatesAndRawWorkLimitFailBeforeTrimmingOrEncoding() {
        val cases =
            listOf(
                "🙂".repeat(1001) to ComplaintReportRejection.TOO_LONG,
                "x".repeat(16385) to ComplaintReportRejection.TOO_LONG,
                " \n\t" to ComplaintReportRejection.REQUIRED,
                "\rbody" to ComplaintReportRejection.FORBIDDEN_CONTROL,
                "body\u0000" to ComplaintReportRejection.FORBIDDEN_CONTROL,
                "\u007fbody" to ComplaintReportRejection.FORBIDDEN_CONTROL,
                "\u0085body" to ComplaintReportRejection.FORBIDDEN_CONTROL,
                "\ud800" to ComplaintReportRejection.MALFORMED_UNICODE,
                "body\udc00" to ComplaintReportRejection.MALFORMED_UNICODE,
            )
        for ((body, reason) in cases) {
            val failure = assertIs<ComplaintEditRequestResult.Rejected>(normalizeEdit("Subject", body))
            assertEquals(ComplaintReportField.BODY, failure.field)
            assertEquals(reason, failure.reason)
        }
        assertEquals(
            ComplaintReportRejection.TOO_LONG,
            assertIs<ComplaintEditRequestResult.Rejected>(normalizeEdit("🙂".repeat(201), "body")).reason,
        )
        assertEquals("\u200bx\u200b", ComplaintReportTextRules.editBody(" \u200bx\u200b "))
    }

    @Test
    fun oneStrongTargetTagAcceptsOnlyBoundedOuterAsciiPaddingAndPositiveLong() {
        val tag = "\"complaint-$MOBILE_EDIT_ID-v7\""
        assertEquals(tag, mobileEditTarget(tag = " ".repeat(256 - tag.length) + tag).precondition)
        assertEquals(Long.MAX_VALUE, mobileEditTarget(version = Long.MAX_VALUE).expectedVersion)
        for (value in invalidEditPreconditions(tag)) {
            assertNull(ComplaintEditTarget.checked(MOBILE_EDIT_ID, 7, value, ComplaintEditShape.SUBJECT_AND_BODY))
        }
        for (version in listOf(0L, -1L)) {
            assertNull(ComplaintEditTarget.checked(MOBILE_EDIT_ID, version, tag, ComplaintEditShape.SUBJECT_AND_BODY))
        }
        assertNull(ComplaintEditTarget.checked(MOBILE_EDIT_ID.uppercase(), 7, tag, ComplaintEditShape.SUBJECT_AND_BODY))
    }

    @Test
    fun recognizedContentShapesIncludeEveryKnownStatusButNoUnknownKindTypeOrStatus() {
        for (status in ComplaintStatus.entries) {
            val fields = mobileEditFields(status = ComplaintHistoryStatus.Known(status))
            assertNotNull(ComplaintEditTarget.from(mobileEditRow(fields)))
        }
        assertNull(ComplaintEditTarget.from(mobileEditRow(type = ComplaintHistoryType.Unrecognized)))
        val unknownStatus = mobileEditFields(status = ComplaintHistoryStatus.Unrecognized)
        assertNull(ComplaintEditTarget.from(mobileEditRow(unknownStatus)))
        val time = Instant.parse(SESSION_ISSUED_AT)
        assertNull(ComplaintEditTarget.from(UnknownComplaintItem(MOBILE_EDIT_ID, "FUTURE", time, time)))
        val ordinary = mobileEditRow()
        val reply = ComplaintOwnerRow.Reply(ordinary.fields, ordinary.type, "Inherited", MOBILE_REPLY_PARENT)
        val noticeReply = ComplaintOwnerRow.NoticeReply(ordinary.fields, "notice.key", MOBILE_REPLY_PARENT)
        assertEquals(ComplaintEditShape.SUBJECT_AND_BODY, assertNotNull(ComplaintEditTarget.from(reply)).shape)
        assertEquals(ComplaintEditShape.BODY_ONLY, assertNotNull(ComplaintEditTarget.from(noticeReply)).shape)
    }

    @Test
    fun subjectAbsenceIsExplicitBodyOnlyAndKeysScopesAreNotContentIds() {
        val bodyOnly = mobileEditTarget(shape = ComplaintEditShape.BODY_ONLY)
        assertNull(mobileEditRequest(target = bodyOnly, subject = null).subject)
        assertIs<ComplaintEditRequestResult.InvalidCandidate>(normalizeEdit("Not allowed", "body", bodyOnly))
        assertEquals(
            ComplaintReportRejection.REQUIRED,
            assertIs<ComplaintEditRequestResult.Rejected>(normalizeEdit(null, "body")).reason,
        )
        assertIs<ComplaintEditRequestResult.InvalidCandidate>(
            ComplaintEditRequest.normalize(mobileEditTarget(), MOBILE_EDIT_ID, Fixtures.SCOPE, "S", "body"),
        )
        assertIs<ComplaintEditRequestResult.InvalidCandidate>(
            ComplaintEditRequest.normalize(mobileEditTarget(), Fixtures.KEY, MOBILE_EDIT_ID, "S", "body"),
        )
        assertTrue(mobileEditRequest().target.id[14] != '4')
    }

    @Test
    fun dispatchedFactoriesRequireExactOperationTargetVersionKeyScopeAndFingerprint() {
        val request = mobileEditRequest()
        val pending = mobileEditPending(request)
        assertNotNull(ComplaintEditHttpRequest.checked(request, pending))
        assertNotNull(ComplaintEditStatusRequest.checked(pending))
        assertNull(ComplaintCreateHttpRequest.checked(mutationReport(), pending))
        assertNull(ComplaintCreateStatusRequest.checked(pending))
        for (wrong in listOf(mutationPending(), mobileEditPending(dispatched = false))) {
            assertNull(ComplaintEditHttpRequest.checked(request, wrong))
            assertNull(ComplaintEditStatusRequest.checked(wrong))
        }
        val changed =
            listOf(
                mobileEditRequest(key = MUTATION_OTHER_KEY),
                mobileEditRequest(scope = MOBILE_EDIT_SCOPE),
                mobileEditRequest(target = mobileEditTarget(version = 8)),
                mobileEditRequest(target = mobileEditTarget(id = MOBILE_REPLY_PARENT)),
                mobileEditRequest(subject = "Changed"),
                mobileEditRequest(body = "Changed"),
            )
        for (wrong in changed) assertNull(ComplaintEditHttpRequest.checked(wrong, pending))
    }
}

private fun invalidEditPreconditions(tag: String): List<String> =
    listOf(
        "",
        "*",
        "W/$tag",
        "$tag,$tag",
        tag.removeSurrounding("\""),
        "$tag\r\n",
        "\u00a0$tag",
        tag.replace("v7", "v0"),
        tag.replace("v7", "v07"),
        tag.replace("v7", "v-1"),
        tag.replace("v7", "v9223372036854775808"),
        tag.replace(MOBILE_EDIT_ID, Fixtures.ID),
        tag.uppercase(),
        " ".repeat(257 - tag.length) + tag,
        tag.replace("v7", "v8"),
    )

private fun normalizeEdit(
    subject: String?,
    body: String,
    target: ComplaintEditTarget = mobileEditTarget(),
): ComplaintEditRequestResult = ComplaintEditRequest.normalize(target, Fixtures.KEY, Fixtures.SCOPE, subject, body)
