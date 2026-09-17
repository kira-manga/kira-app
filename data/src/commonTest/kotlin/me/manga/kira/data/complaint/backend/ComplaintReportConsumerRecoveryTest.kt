package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class ComplaintReportConsumerRecoveryTest {
    @Test
    fun restartedConsumerHasOnlyMetadataAndPreparedCancellationRemainsExplicit() =
        runTest {
            val storage = InstallationCoordinatorFixture(Fixtures.record())
            storage.pending.slots += reportSlot(dispatched = false)
            val original = ComplaintReportFixture(this, storage)
            val oldConsumer = original.consumer()
            val old = oldConsumer.reconcile().reportSuccess().entries().single().pending.handle
            oldConsumer.close()
            original.close()
            val restarted = ComplaintReportFixture(this, storage, storage.restart())
            val consumer = restarted.consumer(ComplaintReportInputs({ error("no recovered IDs") }, { error("no recovered metadata") }))
            try {
                assertIs<AppResult.Failure>(consumer.cancelPrepared(old))
                val observation = consumer.reconcile().reportSuccess().entries().single()
                assertEquals(ComplaintReportPhase.PREPARED, observation.pending.phase)
                val unresolved = assertIs<ComplaintReportAttempt.Unresolved>(observation.attempt)
                assertEquals(ComplaintReportBlock.LIVE_REQUEST_REQUIRED, unresolved.failure.block)
                assertEquals(1, storage.pending.slots.size)
                assertTrue(storage.faults.mutations.isEmpty())
                consumer.cancelPrepared(observation.pending.handle).reportSuccess()
                assertTrue(storage.pending.slots.isEmpty())
                assertTrue(restarted.sessionRequests.isEmpty() && restarted.requests.isEmpty())
            } finally {
                consumer.close()
                restarted.close()
            }
        }

    @Test
    fun foreignHandlesAndStalePromptsCannotResetOrDismissAnotherPrompt() =
        runTest {
            val fixture = ComplaintReportFixture(this)
            fixture.storage.pending.slots += reportSlot(dispatched = false)
            val consumer = fixture.consumer()
            val foreign = fixture.consumer()
            try {
                val pending = consumer.reconcile().reportSuccess().entries().single().pending.handle
                assertIs<AppResult.Failure>(foreign.cancelPrepared(pending))
                val first = consumer.requestRecovery(pending).reportSuccess()
                assertIs<AppResult.Failure>(foreign.cancelRecovery(first))
                assertIs<AppResult.Failure>(foreign.confirmRecovery(first))
                consumer.cancelRecovery(first).reportSuccess()
                assertIs<AppResult.Failure>(consumer.requestRecovery(pending))
                val current = consumer.reconcile().reportSuccess().entries().single().pending.handle
                val second = consumer.requestRecovery(current).reportSuccess()
                assertIs<AppResult.Failure>(consumer.cancelRecovery(first))
                assertIs<AppResult.Failure>(consumer.confirmRecovery(first))
                assertTrue(fixture.storage.faults.mutations.isEmpty())
                consumer.confirmRecovery(second).reportSuccess()
                fixture.storage.assertAbsent()
                assertTrue(fixture.sessionRequests.isEmpty() && fixture.requests.isEmpty())
            } finally {
                consumer.close()
                foreign.close()
                fixture.close()
            }
        }

    @Test
    fun cancellationBeforePromptDeliveryReleasesOnlyUndeliveredConsent() =
        runTest {
            val fixture = ComplaintReportFixture(this)
            val slot = reportSlot(dispatched = false)
            fixture.storage.pending.slots += slot
            val consumer = fixture.consumer()
            try {
                val pending = consumer.reconcile().reportSuccess().entries().single().pending.handle
                var reads = 0
                fixture.storage.faults.onStep = { step ->
                    // Begin binding, independent admission, then final reset-intent validation.
                    if (step == Step.CREDENTIAL_READ && ++reads == 3) currentCoroutineContext().job.cancel()
                }
                val delivery = async { consumer.requestRecovery(pending) }
                assertFailsWith<CancellationException> { delivery.await() }
                fixture.storage.faults.clearFaults()
                assertEquals(3, reads)
                fixture.coordinator.beginReconciliation().success()
                assertTrue(slot.sameAs(fixture.storage.pending.slots.single()))
                assertTrue(fixture.storage.faults.mutations.isEmpty())
                assertTrue(fixture.sessionRequests.isEmpty() && fixture.requests.isEmpty())
            } finally {
                consumer.close()
                fixture.close()
            }
        }

    @Test
    fun exactConsentDismissalNeitherRequiresNorCancelsAnotherCallersWorkLane() =
        runTest {
            val fixture = ComplaintReportFixture(this)
            fixture.storage.pending.slots += reportSlot(dispatched = false)
            val consumer = fixture.consumer()
            try {
                val pending = consumer.reconcile().reportSuccess().entries().single().pending.handle
                val prompt = consumer.requestRecovery(pending).reportSuccess()
                val other = assertNotNull(fixture.works.begin(Job()))
                try {
                    consumer.cancelRecovery(prompt).reportSuccess()
                    assertTrue(other.job.isActive)
                    fixture.coordinator.beginReconciliation().success()
                    assertTrue(fixture.storage.faults.mutations.isEmpty())
                } finally {
                    fixture.coordinator.finishReport(other)
                    other.job.cancel()
                }
            } finally {
                consumer.close()
                fixture.close()
            }
        }
}
