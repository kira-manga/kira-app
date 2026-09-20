package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintLiveReply
import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintReplyDraft
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportDraft
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.domain.model.feedback.ComplaintReportRejection as Rejection

class BackendComplaintReplyRepositoryTest {
    @Test
    fun prepareCapturesCurrentInputsOnceOutsideCoordinatorWithoutWritesOrNetwork() =
        runTest {
            val fixture = ComplaintReportFixture(this)
            val captures = mutableListOf<String>()
            val consumer =
                fixture.consumer(
                    mobileReplyProbeInputs { stage ->
                        assertMobileReplySupplierUnlocked(fixture)
                        captures += stage
                    },
                )
            try {
                val draft = ComplaintReplyDraft(MOBILE_REPLY_PARENT, "  x\r\ny  ")
                val live = assertIs<ReplyLiveHandle>(consumer.preparedConsumerReply(draft))
                assertEquals("x\ny", live.request.body)
                assertEquals(MOBILE_REPLY_PARENT, live.request.parentId)
                assertEquals(Fixtures.SCOPE, live.request.identity.dataScopeId)
                assertNull(live.request.metadata.appVersion)
                assertEquals("current OS", live.request.metadata.osVersion)
                assertEquals(listOf("identifiers", "metadata"), captures)
                assertFalse(live.toString().contains(MOBILE_REPLY_PARENT))
                assertFalse(draft.toString().contains("x\r\ny"))
                fixture.assertMobileReplyUntouched()
            } finally {
                consumer.close()
                fixture.close()
            }
        }

    @Test
    fun missingInstallationNeverAllocatesReplyInputsOrEnrolls() =
        runTest {
            val fixture = ComplaintReportFixture(this, storage = InstallationCoordinatorFixture())
            val consumer = fixture.consumer(mobileReplyNoInputs())
            try {
                val result = consumer.prepare(ComplaintReplyDraft(MOBILE_REPLY_PARENT, "x")).reportSuccess()
                assertEquals(
                    ComplaintReportBlock.MISSING,
                    assertIs<ComplaintReplyPreparation.Blocked>(result).failure.block,
                )
                fixture.assertMobileReplyUntouched()
            } finally {
                consumer.close()
                fixture.close()
            }
        }

    @Test
    fun parentAndNewIdentityRolesAreCheckedWithoutDispatchingOrPersisting() =
        runTest {
            for (parent in listOf("not-a-parent", "$MOBILE_REPLY_PARENT/replies", Fixtures.OTHER_ID)) {
                assertMobileReplyPreparationBlocked(parent, Fixtures.OTHER_ID, Fixtures.KEY)
            }
            for (ids in listOf("not-an-id" to Fixtures.KEY, Fixtures.OTHER_ID to Fixtures.OTHER_ID)) {
                assertMobileReplyPreparationBlocked(MOBILE_REPLY_PARENT, ids.first, ids.second)
            }
        }

    @Test
    fun oneScalarReplyDoesNotWeakenReportMinimumAndEmptyReplyIsTypedInvalid() =
        runTest {
            val fixture = ComplaintReportFixture(this)
            val consumer = fixture.consumer()
            try {
                assertIs<ComplaintReplyPreparation.Ready>(
                    consumer.prepare(ComplaintReplyDraft(MOBILE_REPLY_PARENT, "x")).reportSuccess(),
                )
                val invalid = consumer.prepare(ComplaintReplyDraft(MOBILE_REPLY_PARENT, " \t ")).reportSuccess()
                assertEquals(Rejection.REQUIRED, assertIs<ComplaintReplyPreparation.Invalid>(invalid).reason)
                val report = consumer.prepare(ComplaintReportDraft(subject = "Subject", body = "x")).reportSuccess()
                assertEquals(Rejection.TOO_SHORT, assertIs<ComplaintReportPreparation.Invalid>(report).reason)
                fixture.assertMobileReplyUntouched()
            } finally {
                consumer.close()
                fixture.close()
            }
        }

    @Test
    fun explicitRetryRetainsExactCapturedParentBodyMetadataAndBothIdentifiers() =
        runTest {
            val fixture = mobileReplyAmbiguousFixture()
            val captures = mutableListOf<String>()
            var version = "captured"
            val consumer = fixture.consumer(mobileReplyProbeInputs({ version }) { captures += it })
            try {
                val live = assertIs<ReplyLiveHandle>(consumer.preparedConsumerReply())
                val request = live.request
                version = "changed"
                assertIs<ComplaintReportAttempt.Unresolved>(consumer.submit(live).reportSuccess().attempt)
                assertIs<ComplaintReportAttempt.Completed>(consumer.retry(live).reportSuccess())
                assertSame(request, live.request)
                assertEquals("captured", request.metadata.appVersion)
                assertEquals(listOf("identifiers", "metadata"), captures)
                assertEquals(fixture.sentBodies.first(), fixture.sentBodies.last())
                assertEquals(
                    listOf(Fixtures.KEY, null, Fixtures.KEY),
                    fixture.requests.map { it.headers[Policy.IDEMPOTENCY_HEADER] },
                )
                assertTrue(fixture.sentBodies[1].contains("\"operation\":\"OWNER_REPLY\""))
                assertTrue(!fixture.sentBodies[1].contains("\"body\""))
            } finally {
                consumer.close()
                fixture.close()
            }
        }

    @Test
    fun foreignAndForgedHandlesCannotConsumeTheOriginalOrCrossReportReplyTypes() =
        runTest {
            val fixture = ComplaintReportFixture(this)
            val owner = fixture.consumer()
            val foreign = fixture.consumer()
            try {
                val live = owner.preparedConsumerReply()
                val report = owner.preparedConsumerReport()
                assertFalse(live is ComplaintLiveReport)
                assertFalse(report is ComplaintLiveReply)
                assertIs<AppResult.Failure>(foreign.submit(live))
                assertIs<AppResult.Failure>(foreign.retry(live))
                assertMobileReplyForgeriesRejected(owner)
                fixture.assertMobileReplyUntouched()
                assertIs<ComplaintReportAttempt.Completed>(owner.submit(live).reportSuccess().attempt)
                assertIs<AppResult.Failure>(owner.submit(live))
                assertIs<AppResult.Failure>(owner.retry(live))
                assertEquals(1, fixture.requests.size)
            } finally {
                owner.close()
                foreign.close()
                fixture.close()
            }
        }
}
