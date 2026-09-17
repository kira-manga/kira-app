package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Permit
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class ComplaintReportLifecycleTest {
    @Test
    fun consentCancelResetDeletionAndCloseDuringRefreshCannotPublishOrConstructCreate() =
        runTest {
            for (change in listOf("consent", "reset", "deletion", "cancel", "close")) {
                assertRefreshFence(change)
            }
        }

    @Test
    fun closeAtBothDurableTransitionsRetainsEvidenceAndDoesNotConstructCreateOrRunDestructiveFinally() =
        runTest {
            for (step in listOf(Step.PENDING_CREATED, Step.PENDING_REPLACED)) {
                val fixture = ComplaintReportFixture(this)
                fixture.storage.faults.onStep = { observed -> if (observed == step) fixture.works.close() }
                try {
                    assertFailsWith<CancellationException> { fixture.repository.submit(mutationReport()) }
                    val state =
                        reportRecord(
                            fixture.storage.pending.slots
                                .single(),
                        ).state
                    val expected =
                        if (step == Step.PENDING_CREATED) {
                            PendingComplaintState.PREPARED
                        } else {
                            PendingComplaintState.MAY_HAVE_DISPATCHED
                        }
                    assertEquals(expected, state)
                    assertTrue(fixture.requests.isEmpty())
                    assertTrue(Step.PENDING_DELETE_BEFORE !in fixture.storage.faults.trace)
                    assertIs<AppResult.Failure>(fixture.repository.submit(mutationReport(key = historyId(3))))
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun finalApplicationHasAnIndependentCloseFenceEvenWhenTheApplyingCallerIsStillActive() =
        runTest {
            val fixture =
                ComplaintReportFixture(
                    this,
                    mutationHandler = { respond(mutationApplied(), HttpStatusCode.OK, mutationHeaders()) },
                )
            val slot = reportSlot()
            val slots = fixture.storage.pending.slots
            slots += slot
            val work = assertNotNull(fixture.works.begin(Job()))
            try {
                val binding = fixture.coordinator.beginReportAction(work, ReportStart.Retained(slot)).success()
                val session = fixture.readySession(binding)
                val outcome =
                    fixture.coordinator.readReportStatus(binding, session, fixture.sessions, fixture.http).success()
                fixture.storage.faults.onStep = { step -> if (step == Step.PENDING_READ) fixture.works.close() }
                assertRefused(Block.STALE_BINDING, fixture.coordinator.applyReportOutcome(outcome, fixture.sessions))
                assertTrue(currentCoroutineContext().isActive)
                assertNull(work.application())
                assertTrue(slot.sameAs(slots.single()))
                assertTrue(Step.PENDING_DELETE_BEFORE !in fixture.storage.faults.trace)
            } finally {
                fixture.coordinator.finishReport(work)
                work.job.cancel()
                fixture.close()
            }
        }

    @Test
    fun explicitUnsentCancelAndWarnedResetAreDistinctAndCancelingThePromptPreservesDispatchedEvidence() =
        runTest {
            val fixture = ComplaintReportFixture(this)
            val slots = fixture.storage.pending.slots
            try {
                val unsent = reportSlot(dispatched = false)
                slots += unsent
                fixture.repository.cancelPrepared(unsent).reportSuccess()
                assertTrue(slots.isEmpty())
                val dispatched = reportSlot()
                slots += dispatched
                assertIs<AppResult.Failure>(fixture.repository.cancelPrepared(dispatched))
                val canceledPrompt = fixture.repository.requestRecovery(dispatched).reportSuccess()
                assertRefused(Block.CONSENT_PENDING, fixture.coordinator.admit())
                fixture.repository.cancelRecovery(canceledPrompt).reportSuccess()
                assertTrue(dispatched.sameAs(slots.single()))
                assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(Fixtures.record()))
                val confirmed = fixture.repository.requestRecovery(dispatched).reportSuccess()
                fixture.repository.confirmRecovery(confirmed).reportSuccess()
                fixture.storage.assertAbsent()
                assertTrue(fixture.sessionRequests.isEmpty() && fixture.requests.isEmpty())
            } finally {
                fixture.close()
            }
        }
}

private suspend fun TestScope.assertRefreshFence(change: String) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val fixture =
        ComplaintReportFixture(this, sessionHandler = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
            }
        })
    val ordinary = fixture.coordinator.admit().success()
    try {
        val request = async { fixture.repository.submit(mutationReport()) }
        entered.await()
        changeReportLifetime(fixture, ordinary, change)
        release.complete(Unit)
        assertFailsWith<CancellationException> { request.await() }
        assertTrue(fixture.requests.isEmpty(), change)
        assertTrue(
            fixture.storage.pending.slots
                .isEmpty(),
            change,
        )
        assertTrue(Step.PENDING_CREATE_BEFORE !in fixture.storage.faults.trace, change)
    } finally {
        release.complete(Unit)
        fixture.close()
    }
}

private suspend fun changeReportLifetime(
    fixture: ComplaintReportFixture,
    ordinary: Permit,
    change: String,
) {
    when (change) {
        "consent", "reset" -> {
            val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(ordinary)).success()
            if (change == "reset") {
                fixture.coordinator.confirmRecovery(prompt).success()
            } else {
                fixture.coordinator.cancelRecovery(prompt).success()
            }
        }
        "deletion" -> fixture.coordinator.beginDeletion(ordinary, Fixtures.KEY).success()
        "cancel" -> fixture.repository.cancelCurrent()
        "close" -> fixture.works.close()
    }
}
