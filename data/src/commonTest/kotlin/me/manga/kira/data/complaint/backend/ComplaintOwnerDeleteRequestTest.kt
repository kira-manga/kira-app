package me.manga.kira.data.complaint.backend

import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class ComplaintOwnerDeleteRequestTest {
    @Test
    fun recognizedReportReplyAndNoticeReplyUseTheirOriginalTagWithoutEditShapeOrSuccessor() {
        val report = mobileEditRow(mobileEditFields(version = Long.MAX_VALUE))
        val rows =
            listOf(
                report,
                ComplaintOwnerRow.Reply(report.fields, report.type, "Inherited", MOBILE_REPLY_PARENT),
                ComplaintOwnerRow.NoticeReply(report.fields, "notice.key", MOBILE_REPLY_PARENT),
            )
        for (row in rows) {
            val deletion = mobileOwnerDeleteRequest(assertNotNull(ComplaintEditTarget.from(row)))
            assertEquals(PendingComplaintOperation.DELETE_OWNED, deletion.action.operation)
            assertEquals(Long.MAX_VALUE, deletion.action.expectedVersion)
            assertEquals("\"complaint-$MOBILE_EDIT_ID-v${Long.MAX_VALUE}\"", deletion.precondition)
            assertEquals(MOBILE_EDIT_ID, deletion.targetId)
            assertTrue(deletion.targetId[14] != '4')
            assertNull(deletion.action.parentId)
            assertEquals("ComplaintOwnerDeleteRequest(redacted)", deletion.toString())
        }
    }

    @Test
    fun freshV4KeyAndAuthenticatedScopeRemainSeparateFromCanonicalNonV4Targets() {
        val target = mobileEditTarget()
        assertNull(ComplaintOwnerDeleteRequest.checked(target, MOBILE_EDIT_ID, Fixtures.SCOPE))
        assertNull(ComplaintOwnerDeleteRequest.checked(target, Fixtures.KEY, MOBILE_EDIT_ID))
        val invalidKeys =
            listOf(
                "",
                " $MUTATION_OTHER_KEY",
                "A5555555-5555-4555-8555-555555555555",
                "$MUTATION_OTHER_KEY,other",
            )
        for (key in invalidKeys) {
            assertNull(ComplaintOwnerDeleteRequest.checked(target, key, Fixtures.SCOPE))
        }
        val padded = mobileEditTarget(tag = " \t\"complaint-$MOBILE_EDIT_ID-v7\"\t ")
        assertEquals(mobileOwnerDeleteRequest().precondition, mobileOwnerDeleteRequest(padded).precondition)
    }

    @Test
    fun directFactoryRequiresDispatchedExactOperationTargetTagScopeKeyAndFingerprint() {
        val deletion = mobileOwnerDeleteRequest()
        val pending = mobileOwnerDeletePending(deletion)
        assertNotNull(ComplaintOwnerDeleteHttpRequest.checked(deletion, pending))
        assertNotNull(ComplaintOwnerDeleteStatusRequest.checked(pending))
        for (wrong in listOf(mutationPending(), mobileEditPending(), mobileOwnerDeletePending(dispatched = false))) {
            assertNull(ComplaintOwnerDeleteHttpRequest.checked(deletion, wrong))
            assertNull(ComplaintOwnerDeleteStatusRequest.checked(wrong))
        }
        val changes =
            listOf(
                mobileOwnerDeleteRequest(key = MUTATION_OTHER_KEY),
                mobileOwnerDeleteRequest(scope = MOBILE_EDIT_SCOPE),
                mobileOwnerDeleteRequest(mobileEditTarget(id = MOBILE_REPLY_PARENT)),
                mobileOwnerDeleteRequest(mobileEditTarget(version = 8)),
            )
        for (changed in changes) assertNull(ComplaintOwnerDeleteHttpRequest.checked(changed, pending))
        assertNull(ComplaintCreateStatusRequest.checked(pending))
        assertNull(ComplaintEditStatusRequest.checked(pending))
        assertNull(ComplaintCreateHttpRequest.checked(mutationReport(), pending))
        assertNull(ComplaintEditHttpRequest.checked(mobileEditRequest(), pending))
        assertWrongOwnerDeleteFingerprintRejected(deletion, pending)
    }
}

private fun assertWrongOwnerDeleteFingerprintRejected(
    deletion: ComplaintOwnerDeleteRequest,
    pending: PendingComplaintRecord,
) {
    val request =
        assertNotNull(
            PendingComplaintRequest.checked(
                deletion.action,
                deletion.key.canonical,
                mobileEditRequest().pendingFingerprint(),
            ),
        )
    val wrong = assertNotNull(PendingComplaintRecord.prepared(pending.binding, request, pending.times).markedDispatched())
    assertNull(ComplaintOwnerDeleteHttpRequest.checked(deletion, wrong))
}
