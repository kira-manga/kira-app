package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportPhase
import me.manga.kira.domain.repository.ComplaintReportRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class ComplaintReplyConsumerRecoveryTest {
    @Test
    fun coldPreparedReplyNeedsExplicitCancellationByItsCurrentSharedRecoveryIssuer() =
        runTest {
            val storage = InstallationCoordinatorFixture(Fixtures.record())
            storage.pending.slots += reportSlot(mobileReplyRequest(), dispatched = false)
            val original = ComplaintReportFixture(this, storage)
            val old = original.consumer(mobileReplyNoInputs())
            val oldHandle =
                old
                    .reconcile()
                    .reportSuccess()
                    .entries()
                    .single()
                    .pending.handle
            old.close()
            original.close()
            val restarted = ComplaintReportFixture(this, storage, storage.restart())
            val current = restarted.consumer(mobileReplyNoInputs())
            try {
                assertIs<AppResult.Failure>(current.cancelPrepared(oldHandle))
                val entry =
                    current
                        .reconcile()
                        .reportSuccess()
                        .entries()
                        .single()
                assertEquals(ComplaintReportPhase.PREPARED, entry.pending.phase)
                val unresolved = assertIs<ComplaintReportAttempt.Unresolved>(entry.attempt)
                assertEquals(ComplaintReportBlock.LIVE_REQUEST_REQUIRED, unresolved.failure.block)
                restarted.assertMobileReplyUntouched()
                current.cancelPrepared(entry.pending.handle).reportSuccess()
                assertTrue(storage.pending.slots.isEmpty())
                assertTrue(restarted.requests.isEmpty() && restarted.sessionRequests.isEmpty())
            } finally {
                current.close()
                restarted.close()
            }
        }

    @Test
    fun replyPendingHandleUsesReportRecoveryPortAndResetRequiresAnExactCurrentPrompt() =
        runTest {
            val fixture = ComplaintReportFixture(this)
            fixture.installWriteFault("replace-before")
            val consumer = fixture.consumer()
            val foreign = fixture.consumer()
            try {
                val live = consumer.preparedConsumerReply()
                val result = assertIs<ComplaintReportAttempt.Unresolved>(consumer.submit(live).reportSuccess().attempt)
                val pending = assertNotNull(result.pending)
                assertEquals(ComplaintReportPhase.PREPARED, pending.phase)
                fixture.storage.faults.clearFaults()
                val recovery: ComplaintReportRepository = consumer
                assertIs<AppResult.Failure>(foreign.cancelPrepared(pending.handle))
                val prompt = recovery.requestRecovery(pending.handle).reportSuccess()
                assertIs<AppResult.Failure>(foreign.confirmRecovery(prompt))
                recovery.cancelRecovery(prompt).reportSuccess()
                assertTrue(
                    fixture.storage.pending.slots
                        .isNotEmpty(),
                )
                assertIs<AppResult.Failure>(recovery.requestRecovery(pending.handle))
                assertMobileReplyConfirmedReset(fixture, recovery)
            } finally {
                consumer.close()
                foreign.close()
                fixture.close()
            }
        }

    @Test
    fun knownReplyApplicationSurvivesFailedCleanupAndLaterStatusUncertainty() =
        runTest {
            val fixture = mobileReplyKnownApplicationFixture()
            fixture.storage.faults.failAt(Step.PENDING_DELETE_BEFORE)
            val consumer = fixture.consumer()
            try {
                val live = consumer.preparedConsumerReply()
                val first = assertIs<ComplaintReportAttempt.Unresolved>(consumer.submit(live).reportSuccess().attempt)
                val applied = assertIs<ComplaintReportApplication.Applied>(first.knownApplication)
                assertEquals(Fixtures.OTHER_ID, applied.id)
                assertEquals(1L, applied.version)
                assertNotNull(first.pending)
                val retry = assertIs<ComplaintReportAttempt.Unresolved>(consumer.retry(live).reportSuccess())
                assertSame(applied, retry.knownApplication)
                assertEquals(1, fixture.storage.pending.slots.size)
                assertEquals(2, fixture.requests.size)
            } finally {
                consumer.close()
                fixture.close()
            }
        }
}

private suspend fun assertMobileReplyConfirmedReset(
    fixture: ComplaintReportFixture,
    recovery: ComplaintReportRepository,
) {
    val current =
        recovery
            .reconcile()
            .reportSuccess()
            .entries()
            .single()
            .pending.handle
    val prompt = recovery.requestRecovery(current).reportSuccess()
    assertTrue(
        fixture.storage.pending.slots
            .isNotEmpty(),
    )
    assertTrue(Step.PENDING_DELETE_BEFORE !in fixture.storage.faults.trace)
    recovery.confirmRecovery(prompt).reportSuccess()
    fixture.storage.assertAbsent()
    assertTrue(fixture.requests.isEmpty())
}

private fun TestScope.mobileReplyKnownApplicationFixture(): ComplaintReportFixture =
    ComplaintReportFixture(this, mutationHandler = { request ->
        if (request.url.encodedPath.endsWith(Policy.STATUS_PATH)) {
            val unavailable = HttpStatusCode.ServiceUnavailable
            respond(historyProblem(unavailable), unavailable, mutationHeaders(unavailable))
        } else {
            respond(mutationAck(), HttpStatusCode.Created, mutationHeaders(HttpStatusCode.Created))
        }
    })
