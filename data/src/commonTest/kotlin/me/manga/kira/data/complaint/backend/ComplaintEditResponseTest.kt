package me.manga.kira.data.complaint.backend

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class ComplaintEditResponseTest {
    private val edit = mobileEditRequest()
    private val pending = mobileEditPending(edit)
    private val direct = assertNotNull(ComplaintEditHttpRequest.checked(edit, pending))
    private val status = assertNotNull(ComplaintEditStatusRequest.checked(pending))

    @Test
    fun direct200RequiresOnlyExactIdSuccessorAndStrongTagWithoutLocation() {
        val applied = assertIs<ComplaintEditHttpResult.Applied>(decodeDirect(mobileEditAck()))
        assertSame(direct, applied.request)
        assertEquals(MOBILE_EDIT_ID, applied.acknowledgement.id)
        assertEquals(8L, applied.acknowledgement.version)
        for (text in invalidEditAcknowledgements()) assertDirectFailure(decodeDirect(text))
        val tag = "\"complaint-$MOBILE_EDIT_ID-v8\""
        val badHeaders =
            listOf(
                ComplaintMutationDocument(201, mobileEditAck(), null, tag),
                ComplaintMutationDocument(200, mobileEditAck(), "/api/v1/complaints/$MOBILE_EDIT_ID", tag),
                ComplaintMutationDocument(200, mobileEditAck(), null, null),
                ComplaintMutationDocument(200, mobileEditAck(), null, "W/$tag"),
                ComplaintMutationDocument(200, mobileEditAck(), null, "$tag,$tag"),
                ComplaintMutationDocument(200, mobileEditAck(), null, " $tag"),
            )
        for (document in badHeaders) assertDirectFailure(ComplaintEditResponse.edit(document, direct))
    }

    @Test
    fun appliedStatusIsEdit200NotCreation201AndNeverUsesLocationOrHttpEtag() {
        val applied = assertIs<ComplaintEditStatusHttpResult.Applied>(decodeStatus(mobileEditApplied()))
        assertSame(status, applied.request)
        assertEquals(8L, applied.acknowledgement.version)
        for (text in invalidAppliedEditStatuses()) assertStatusFailure(decodeStatus(text))
        assertStatusFailure(
            ComplaintEditResponse.status(
                ComplaintMutationDocument(200, mobileEditApplied(), null, "\"complaint-$MOBILE_EDIT_ID-v8\""),
                status,
            ),
        )
        assertStatusFailure(
            ComplaintEditResponse.status(ComplaintMutationDocument(200, mobileEditApplied(), "/wrong", null), status),
        )
    }

    @Test
    fun closedEditRejectionCellsKeepExactNoEchoRequestAndRejectCreationCellsOrStatusDrift() {
        for (code in ComplaintEditRejection.entries) {
            val result = assertIs<ComplaintEditStatusHttpResult.Rejected>(decodeStatus(mobileEditRejected(code)))
            assertSame(status, result.request)
            assertEquals(code, result.code)
            assertStatusFailure(decodeStatus(mobileEditRejected(code).replace(":${code.status},", ":400,")))
        }
        val invalid =
            listOf(
                mutationRejected(),
                mutationRejected(ComplaintReplyRejection.COMPLAINT_PARENT_NOT_FOUND),
                mobileEditRejected().replace("COMPLAINT_NO_CHANGE", "FUTURE"),
                mobileEditRejected().replace("REJECTED", "rejected"),
                mobileEditRejected().dropLast(1) + ",\"body\":{}}",
                mobileEditRejected().replace("\"originalStatus\":409", "\"originalStatus\":\"409\""),
            )
        for (text in invalid) assertStatusFailure(decodeStatus(text))
    }

    @Test
    fun directNoChangeNotFoundPreconditionAndDisabledResponsesAreOnlyBoundFailures() {
        for (code in ComplaintEditProblem.entries) {
            val http = HttpStatusCode.fromValue(code.status)
            val document = ComplaintMutationDocument(code.status, mutationProblem(http, code.name), null, null)
            val result = assertIs<ComplaintEditHttpResult.HttpFailure>(ComplaintEditResponse.edit(document, direct))
            assertSame(direct, result.request)
            assertEquals(code.status, result.status)
        }
        val generic = ComplaintMutationDocument(404, mutationProblem(HttpStatusCode.NotFound, "NOT_FOUND"), null, null)
        assertIs<ComplaintEditStatusHttpResult.HttpFailure>(ComplaintEditResponse.status(generic, status))
        val missing =
            ComplaintMutationDocument(404, mutationProblem(HttpStatusCode.NotFound, "OPERATION_NOT_FOUND"), null, null)
        assertEquals(
            ComplaintEditProblem.OPERATION_NOT_FOUND,
            assertIs<ComplaintEditStatusHttpResult.HttpFailure>(ComplaintEditResponse.status(missing, status)).problem,
        )
        assertStatusFailure(
            ComplaintEditResponse.status(ComplaintMutationDocument(409, missing.text, null, null), status),
        )
    }

    @Test
    fun successorOverflowAndCreationVersionSemanticsStaySeparate() {
        val maximum = mobileEditRequest(target = mobileEditTarget(version = Long.MAX_VALUE))
        val request = assertNotNull(ComplaintEditHttpRequest.checked(maximum, mobileEditPending(maximum)))
        val document =
            ComplaintMutationDocument(
                200,
                mobileEditAck(Long.MAX_VALUE),
                null,
                "\"complaint-$MOBILE_EDIT_ID-v${Long.MAX_VALUE}\"",
            )
        assertIs<ComplaintEditHttpResult.Failed>(ComplaintEditResponse.edit(document, request))
        val report = mutationReport()
        val create = assertNotNull(ComplaintCreateHttpRequest.checked(report, mutationPending(report)))
        assertIs<ComplaintCreateHttpResult.Failed>(ComplaintMutationResponse.create(document, create))
        assertCreationVersionsRemainSeparate(create)
    }

    private fun decodeDirect(text: String): ComplaintEditHttpResult =
        ComplaintEditResponse.edit(
            ComplaintMutationDocument(200, text, null, "\"complaint-$MOBILE_EDIT_ID-v8\""),
            direct,
        )

    private fun decodeStatus(text: String): ComplaintEditStatusHttpResult =
        ComplaintEditResponse.status(ComplaintMutationDocument(200, text, null, null), status)

    private fun assertDirectFailure(result: ComplaintEditHttpResult) {
        assertEquals(ComplaintMutationFailure.RESPONSE, assertIs<ComplaintEditHttpResult.Failed>(result).reason)
        assertSame(direct, result.request)
    }

    private fun assertStatusFailure(result: ComplaintEditStatusHttpResult) {
        assertEquals(ComplaintMutationFailure.RESPONSE, assertIs<ComplaintEditStatusHttpResult.Failed>(result).reason)
        assertSame(status, result.request)
    }
}

private fun assertCreationVersionsRemainSeparate(create: ComplaintCreateHttpRequest) {
    val reply = mobileReplyRequest()
    val replyRequest = assertNotNull(ComplaintCreateHttpRequest.checked(reply, mutationPending(reply)))
    val id = reply.identity.clientId.canonical
    val document = ComplaintMutationDocument(201, mutationAck(2), "/api/v1/complaints/$id", "\"complaint-$id-v2\"")
    assertIs<ComplaintCreateHttpResult.Failed>(ComplaintMutationResponse.create(document, replyRequest))
    assertIs<ComplaintCreateHttpResult.Applied>(ComplaintMutationResponse.create(document, create))
}

private fun invalidAppliedEditStatuses(): List<String> =
    listOf(
        mutationApplied(id = MOBILE_EDIT_ID, version = 8),
        mobileEditApplied().replace("\"originalStatus\":200", "\"originalStatus\":201"),
        mobileEditApplied().replace("\"body\":", "\"location\":\"/api/v1/complaints/$MOBILE_EDIT_ID\",\"body\":"),
        mobileEditApplied(version = 7),
        mobileEditApplied(version = 9),
        mobileEditApplied(id = MOBILE_REPLY_PARENT),
        mobileEditApplied().replace("v8", "v7"),
        mobileEditApplied().replace("APPLIED", "FUTURE"),
    )

private fun invalidEditAcknowledgements(): List<String> =
    listOf(
        """{"id":"$MOBILE_EDIT_ID"}""",
        """{"id":"$MOBILE_EDIT_ID","version":8,"body":"not a snapshot"}""",
        """{"id":"$MOBILE_EDIT_ID","version":8,"version":8}""",
        """{"id":"$MOBILE_EDIT_ID","version":"8"}""",
        """{"id":"$MOBILE_EDIT_ID","version":8.0}""",
        """{"id":"$MOBILE_EDIT_ID","version":8e0}""",
        """{"id":"$MOBILE_EDIT_ID","version":08}""",
        """{"id":"$MOBILE_EDIT_ID","version":9223372036854775808}""",
        mobileEditAck(0),
        mobileEditAck(-1),
        mobileEditAck(7),
        mobileEditAck(9),
        mobileEditAck(id = MOBILE_EDIT_ID.uppercase()),
        mobileEditAck(id = MOBILE_REPLY_PARENT),
        mobileEditAck() + "{}",
        "[${mobileEditAck()}]",
    )
