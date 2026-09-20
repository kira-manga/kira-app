package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteReceiptRejection
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt as ConsumerAttempt

class ComplaintOwnerDeleteRecoveryTest {
    @Test
    fun directNotFoundPendingAndPreconditionRemainUncertainUntilColdTerminalDeleteStatus() =
        runTest {
            for (code in ComplaintOwnerDeleteRejection.entries) {
                val storage = InstallationCoordinatorFixture(Fixtures.record())
                assertDirectOwnerDeleteRetained(storage, code)
                assertColdOwnerDeleteRejection(storage, code)
            }
        }

    @Test
    fun coldPreparedDeleteCannotBeReconstructedAndOnlyCurrentRecoveryIssuerCanCancelIt() =
        runTest {
            val storage = InstallationCoordinatorFixture(Fixtures.record())
            storage.pending.slots += mobileOwnerDeleteSlot(dispatched = false)
            val old = oldPreparedOwnerDeleteHandle(storage)
            val f = ComplaintReportFixture(this, storage, storage.restart())
            val consumer = f.consumer(mobileEditInputs { error("cold metadata must not allocate a key") })
            try {
                assertIs<AppResult.Failure>(consumer.cancelPrepared(old))
                val entry = consumer.reconcile().reportSuccess().entries().single()
                assertEquals(ComplaintReportPhase.PREPARED, entry.pending.phase)
                assertEquals(
                    ComplaintReportBlock.LIVE_REQUEST_REQUIRED,
                    assertIs<ConsumerAttempt.Unresolved>(entry.attempt).failure.block,
                )
                f.assertMobileEditUntouched()
                consumer.cancelPrepared(entry.pending.handle).reportSuccess()
                assertTrue(storage.pending.slots.isEmpty())
                assertTrue(f.requests.isEmpty() && f.sessionRequests.isEmpty())
            } finally {
                consumer.close()
                f.close()
            }
        }

    @Test
    fun knownContentFree204SurvivesUncertainCleanupAndLaterStatusFailureWithoutAnotherDelete() =
        runTest {
            for (step in listOf(Step.PENDING_DELETE_BEFORE, Step.PENDING_DELETED)) assertOwnerDeleteKnownOutcome(step)
        }
}

private suspend fun TestScope.assertDirectOwnerDeleteRetained(
    storage: InstallationCoordinatorFixture,
    code: ComplaintOwnerDeleteRejection,
) {
    val status = HttpStatusCode.fromValue(code.status)
    val f =
        ComplaintReportFixture(
            this,
            storage,
            mutationHandler = { respond(mutationProblem(status, code.name), status, mutationHeaders(status)) },
        )
    try {
        val deletion = mobileOwnerDeleteRequest()
        val attempt = assertIs<ReportAttempt.Unresolved>(f.repository.submit(deletion).reportSuccess().attempt)
        assertSame(deletion, attempt.liveReport)
        assertNull(attempt.application)
        assertEquals(PendingComplaintState.MAY_HAVE_DISPATCHED, reportRecord(storage.pending.slots.single()).state)
        assertEquals(1, f.requests.size)
        assertTrue(Step.PENDING_DELETE_BEFORE !in storage.faults.trace)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertColdOwnerDeleteRejection(
    storage: InstallationCoordinatorFixture,
    code: ComplaintOwnerDeleteRejection,
) {
    val f =
        ComplaintReportFixture(
            this,
            storage,
            storage.restart(),
            mutationHandler = { respond(mobileOwnerDeleteRejected(code), HttpStatusCode.OK, mutationHeaders()) },
        )
    val consumer = f.consumer(mobileEditInputs { error("cold receipt lookup cannot recreate a live delete") })
    try {
        val completed = assertIs<ConsumerAttempt.Completed>(consumer.reconcile().reportSuccess().entries().single().attempt)
        val deletion = assertIs<ComplaintReportApplication.OwnerDelete>(completed.application)
        val rejection = assertIs<ComplaintOwnerDeleteApplication.Rejected>(deletion.application)
        assertEquals(ComplaintOwnerDeleteReceiptRejection.valueOf(code.name), rejection.code)
        assertTrue(storage.pending.slots.isEmpty())
        f.assertOnlyEditStatusRequests()
        assertEquals(1, f.requests.size)
        assertTrue(f.sentBodies.single().contains("\"operation\":\"OWNER_DELETE\""))
    } finally {
        consumer.close()
        f.close()
    }
}

private suspend fun TestScope.oldPreparedOwnerDeleteHandle(storage: InstallationCoordinatorFixture): ComplaintPendingReport {
    val f = ComplaintReportFixture(this, storage)
    val consumer = f.consumer(mobileEditInputs { error("cold preparation cannot allocate a key") })
    return try {
        consumer.reconcile().reportSuccess().entries().single().pending.handle
    } finally {
        consumer.close()
        f.close()
    }
}

private suspend fun TestScope.assertOwnerDeleteKnownOutcome(step: Step) {
    val f = ownerDeleteStatusFailureFixture()
    f.storage.faults.failAt(step)
    val consumer = f.consumer(mobileEditInputs())
    try {
        val live = consumer.preparedConsumerOwnerDelete()
        val first = assertIs<ConsumerAttempt.Unresolved>(consumer.submit(live).reportSuccess().attempt)
        val known = assertIs<ComplaintReportApplication.OwnerDelete>(first.knownApplication)
        assertSame(ComplaintOwnerDeleteApplication.Applied, known.application)
        assertNotNull(first.pending)
        val retry = assertIs<ConsumerAttempt.Unresolved>(consumer.retry(live).reportSuccess())
        assertSame(known, retry.knownApplication)
        assertEquals(1, f.requests.count { it.method == HttpMethod.Delete })
        assertEquals(if (step == Step.PENDING_DELETE_BEFORE) 2 else 1, f.requests.size)
    } finally {
        consumer.close()
        f.close()
    }
}

private fun TestScope.ownerDeleteStatusFailureFixture(): ComplaintReportFixture =
    ComplaintReportFixture(
        this,
        mutationHandler = { request ->
            if (request.url.encodedPath.endsWith(Policy.STATUS_PATH)) {
                val status = HttpStatusCode.ServiceUnavailable
                respond(historyProblem(status), status, mutationHeaders(status))
            } else {
                respond("", HttpStatusCode.NoContent, mobileOwnerDeleteHeaders())
            }
        },
    )
