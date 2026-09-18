package me.manga.kira.data.complaint.backend

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class ComplaintOwnerDeleteResponseTest {
    private val direct = mobileOwnerDeleteHttpRequest()
    private val status = mobileOwnerDeleteStatusRequest()

    @Test
    fun onlyExactContentFree204CanApplyAndMaximumPreconditionHasNoSuccessorComputation() {
        val document = ComplaintMutationDocument(204, "", null, null)
        assertSame(direct, assertIs<ComplaintOwnerDeleteHttpResult.Applied>(decodeDirect(document)).request)
        val maximum = mobileOwnerDeleteRequest(mobileEditTarget(version = Long.MAX_VALUE))
        val request = mobileOwnerDeleteHttpRequest(maximum)
        val deleted = ComplaintOwnerDeleteResponse.ownerDelete(document, request)
        assertSame(request, assertIs<ComplaintOwnerDeleteHttpResult.Applied>(deleted).request)
        for (text in listOf(" ", "{}", "null", "\u0000", MOBILE_OWNER_DELETE_APPLIED)) {
            assertDirectFailure(decodeDirect(ComplaintMutationDocument(204, text, null, null)))
        }
        for (code in listOf(200, 201, 202, 206, 301, 304)) {
            assertDirectFailure(decodeDirect(ComplaintMutationDocument(code, "", null, null)))
        }
        assertDirectFailure(decodeDirect(ComplaintMutationDocument(204, "", "/wrong", null)))
        assertDirectFailure(decodeDirect(ComplaintMutationDocument(204, "", null, "\"complaint-$MOBILE_EDIT_ID-v8\"")))
        assertOtherMutationDecodersReject(document)
    }

    @Test
    fun appliedStatusHasExactlyTwoFieldsWithoutBodyIdVersionTagLocationOrInventedAcknowledgement() {
        val applied = assertIs<ComplaintOwnerDeleteStatusHttpResult.Applied>(decodeStatus(MOBILE_OWNER_DELETE_APPLIED))
        assertSame(status, applied.request)
        for (text in invalidOwnerDeleteAppliedStatuses()) assertStatusFailure(decodeStatus(text))
        for (field in listOf("body", "id", "version", "etag", "location", "extra")) {
            assertStatusFailure(decodeStatus(MOBILE_OWNER_DELETE_APPLIED.dropLast(1) + ",\"$field\":null}"))
        }
        val located = ComplaintMutationDocument(200, MOBILE_OWNER_DELETE_APPLIED, "/x", null)
        assertStatusFailure(ComplaintOwnerDeleteResponse.status(located, status))
        val tagged = ComplaintMutationDocument(200, MOBILE_OWNER_DELETE_APPLIED, null, "tag")
        assertStatusFailure(ComplaintOwnerDeleteResponse.status(tagged, status))
    }

    @Test
    fun rejectionStatusHasOnlyTheThreeFrozenCodeStatusPairsAndNoNullableOrUnknownFields() {
        for (code in ComplaintOwnerDeleteRejection.entries) {
            val text = mobileOwnerDeleteRejected(code)
            val rejected = assertIs<ComplaintOwnerDeleteStatusHttpResult.Rejected>(decodeStatus(text))
            assertSame(status, rejected.request)
            assertEquals(code, rejected.code)
            assertStatusFailure(decodeStatus(text.replace(":${code.status},", ":400,")))
            assertStatusFailure(decodeStatus(text.dropLast(1) + ",\"body\":null}"))
        }
        for (text in invalidOwnerDeleteRejections()) assertStatusFailure(decodeStatus(text))
    }

    @Test
    fun directDeleteProblemsRemainBoundObservationsAndOnlyExactMissingStatusCarriesMissingCode() {
        for (code in ComplaintOwnerDeleteProblem.entries) {
            val problem = mutationProblem(HttpStatusCode.fromValue(code.status), code.name)
            val document = ComplaintMutationDocument(code.status, problem, null, null)
            val result = assertIs<ComplaintOwnerDeleteHttpResult.HttpFailure>(decodeDirect(document))
            assertSame(direct, result.request)
            assertEquals(code.status, result.status)
        }
        val missing = mutationProblem(HttpStatusCode.NotFound, "OPERATION_NOT_FOUND")
        val document = ComplaintMutationDocument(404, missing, null, null)
        val result = ComplaintOwnerDeleteResponse.status(document, status)
        val observed = assertIs<ComplaintOwnerDeleteStatusHttpResult.HttpFailure>(result)
        assertEquals(ComplaintOwnerDeleteProblem.OPERATION_NOT_FOUND, observed.problem)
        assertStatusFailure(ComplaintOwnerDeleteResponse.status(ComplaintMutationDocument(409, missing, null, null), status))
        val editProblem = mutationProblem(HttpStatusCode.Conflict, "COMPLAINT_NO_CHANGE")
        val wrong = ComplaintMutationDocument(409, editProblem, null, null)
        assertDirectFailure(decodeDirect(wrong))
    }

    private fun decodeDirect(document: ComplaintMutationDocument): ComplaintOwnerDeleteHttpResult =
        ComplaintOwnerDeleteResponse.ownerDelete(document, direct)

    private fun decodeStatus(text: String): ComplaintOwnerDeleteStatusHttpResult =
        ComplaintOwnerDeleteResponse.status(ComplaintMutationDocument(200, text, null, null), status)

    private fun assertDirectFailure(result: ComplaintOwnerDeleteHttpResult) {
        assertEquals(ComplaintMutationFailure.RESPONSE, assertIs<ComplaintOwnerDeleteHttpResult.Failed>(result).reason)
        assertSame(direct, result.request)
    }

    private fun assertStatusFailure(result: ComplaintOwnerDeleteStatusHttpResult) {
        assertEquals(ComplaintMutationFailure.RESPONSE, assertIs<ComplaintOwnerDeleteStatusHttpResult.Failed>(result).reason)
        assertSame(status, result.request)
    }
}

private fun assertOtherMutationDecodersReject(document: ComplaintMutationDocument) {
    val creation = mutationReport()
    val createRequest = assertNotNull(ComplaintCreateHttpRequest.checked(creation, mutationPending(creation)))
    assertIs<ComplaintCreateHttpResult.Failed>(ComplaintMutationResponse.create(document, createRequest))
    val edit = mobileEditRequest()
    val editRequest = assertNotNull(ComplaintEditHttpRequest.checked(edit, mobileEditPending(edit)))
    assertIs<ComplaintEditHttpResult.Failed>(ComplaintEditResponse.edit(document, editRequest))
}

private fun invalidOwnerDeleteAppliedStatuses(): List<String> =
    listOf(
        mutationApplied(),
        mobileEditApplied(),
        MOBILE_OWNER_DELETE_APPLIED.replace(":204", ":202"),
        MOBILE_OWNER_DELETE_APPLIED.replace(":204", ":\"204\""),
        MOBILE_OWNER_DELETE_APPLIED.replace(":204", ":204.0"),
        MOBILE_OWNER_DELETE_APPLIED.replace(":204", ":2.04e2"),
        MOBILE_OWNER_DELETE_APPLIED.replace(":204", ":0204"),
        MOBILE_OWNER_DELETE_APPLIED.replace(":204", ":null"),
        MOBILE_OWNER_DELETE_APPLIED.replace("APPLIED", "applied"),
        MOBILE_OWNER_DELETE_APPLIED.replace("APPLIED", "FUTURE"),
        MOBILE_OWNER_DELETE_APPLIED.dropLast(1) + ",\"outcome\":\"APPLIED\"}",
        MOBILE_OWNER_DELETE_APPLIED.dropLast(1) + ",\"originalStatus\":204}",
        """{"originalStatus":204}""",
        """{"outcome":"APPLIED"}""",
        """{"outcome":null,"originalStatus":204}""",
        "[$MOBILE_OWNER_DELETE_APPLIED]",
        MOBILE_OWNER_DELETE_APPLIED + "{}",
    )

private fun invalidOwnerDeleteRejections(): List<String> =
    listOf(
        mutationRejected(),
        mobileEditRejected(),
        mobileOwnerDeleteRejected().replace("PRECONDITION_FAILED", "FUTURE"),
        mobileOwnerDeleteRejected().replace("REJECTED", "rejected"),
        mobileOwnerDeleteRejected().replace(":412", ":\"412\""),
        mobileOwnerDeleteRejected().replace(":412", ":412.0"),
        mobileOwnerDeleteRejected().replace("\"PRECONDITION_FAILED\"", "null"),
        mobileOwnerDeleteRejected().dropLast(1) + ",\"problemCode\":\"PRECONDITION_FAILED\"}",
        """{"outcome":"REJECTED","originalStatus":428,"problemCode":"PRECONDITION_REQUIRED"}""",
    )
