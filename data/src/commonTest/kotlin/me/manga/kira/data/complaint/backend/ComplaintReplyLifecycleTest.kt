package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class ComplaintReplyLifecycleTest {
    @Test
    fun firstReplyByteFollowsBothDurableProofsAndNeverStoresProse() =
        runTest {
            val storage = InstallationCoordinatorFixture(Fixtures.record())
            val reply = mobileReplyRequest(body = "private reply body")
            val fixture =
                ComplaintReportFixture(this, storage, mutationHandler = { request ->
                    val slot = storage.pending.slots.single()
                    val record = reportRecord(slot)
                    assertEquals(PendingComplaintState.MAY_HAVE_DISPATCHED, record.state)
                    assertEquals(PendingComplaintOperation.CREATE_REPLY, record.request.action.operation)
                    assertEquals(
                        listOf(reply.parentId, reply.identity.clientId.value),
                        record.request.action.orderedTargetIds(),
                    )
                    assertEquals(ComplaintReplyFingerprint.of(reply).encoded, record.request.fingerprint.encoded)
                    assertTrue(!slot.bytes().decodeToString().contains(reply.body))
                    assertTrue(Step.PENDING_CREATED in storage.faults.trace)
                    assertTrue(Step.PENDING_REPLACED in storage.faults.trace)
                    assertTrue(request.url.encodedPath.endsWith("/${reply.parentId}${Policy.REPLIES_SUFFIX}"))
                    respond(mutationAck(), HttpStatusCode.Created, mutationHeaders(HttpStatusCode.Created))
                })
            try {
                val result = fixture.repository.submit(reply).reportSuccess()
                assertSame(reply, assertIs<ReportAttempt.Completed>(result.attempt).liveReport)
                assertTrue(storage.pending.slots.isEmpty())
                assertEquals(1, fixture.requests.size)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun everyPreparedAndMayWriteOrReadbackFailureStopsReplyDispatch() =
        runTest {
            for (case in REPLY_WRITE_FAULTS) {
                val fixture = ComplaintReportFixture(this)
                fixture.installWriteFault(case)
                try {
                    val reply = mobileReplyRequest()
                    val attempt =
                        fixture.repository
                            .submit(reply)
                            .reportSuccess()
                            .attempt
                    assertSame(reply, assertIs<ReportAttempt.Unresolved>(attempt).liveReport, case)
                    assertTrue(fixture.requests.isEmpty(), case)
                    assertTrue(Step.PENDING_DELETE_BEFORE !in fixture.storage.faults.trace, case)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun one401RefreshKeepsTheExactReplyAndCannotLoopAfterAnother401() =
        runTest {
            for (secondUnauthorized in listOf(false, true)) {
                var calls = 0
                val fixture =
                    ComplaintReportFixture(this, mutationHandler = {
                        if (++calls == 1 || secondUnauthorized) {
                            respond(
                                mutationProblem(HttpStatusCode.Unauthorized, "UNAUTHORIZED"),
                                HttpStatusCode.Unauthorized,
                                mutationHeaders(HttpStatusCode.Unauthorized),
                            )
                        } else {
                            respond(mutationAck(), HttpStatusCode.Created, mutationHeaders(HttpStatusCode.Created))
                        }
                    })
                try {
                    val result =
                        fixture.repository
                            .submit(mobileReplyRequest())
                            .reportSuccess()
                            .attempt
                    if (secondUnauthorized) {
                        assertIs<ReportAttempt.Unresolved>(result)
                    } else {
                        assertIs<ReportAttempt.Completed>(result)
                    }
                    assertEquals(2, fixture.requests.size)
                    assertEquals(2, fixture.sessionRequests.size)
                    assertEquals(fixture.sentBodies.first(), fixture.sentBodies.last())
                    assertEquals(
                        listOf(Fixtures.KEY, Fixtures.KEY),
                        fixture.requests.map { it.headers[Policy.IDEMPOTENCY_HEADER] },
                    )
                    assertEquals(if (secondUnauthorized) 1 else 0, fixture.storage.pending.slots.size)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun reportAndReplyShareOneOccupiedLaneAndCancelledReplyRetainsMayEvidence() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                ComplaintReportFixture(this, mutationHandler = {
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                        respond(mutationAck(), HttpStatusCode.Created, mutationHeaders(HttpStatusCode.Created))
                    }
                })
            try {
                val first = async { fixture.repository.submit(mobileReplyRequest()) }
                entered.await()
                assertIs<AppResult.Failure>(fixture.repository.submit(mutationReport(key = historyId(30))))
                assertIs<AppResult.Failure>(fixture.repository.submit(mobileReplyRequest(key = historyId(31))))
                assertTrue(first.isActive)
                fixture.repository.cancelCurrent()
                release.complete(Unit)
                assertFailsWith<CancellationException> { first.await() }
                assertEquals(1, fixture.requests.size)
                assertEquals(
                    PendingComplaintState.MAY_HAVE_DISPATCHED,
                    reportRecord(
                        fixture.storage.pending.slots
                            .single(),
                    ).state,
                )
                assertTrue(Step.PENDING_DELETE_BEFORE !in fixture.storage.faults.trace)
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }

    @Test
    fun resetDeletionConsentCancellationAndCloseDuringSessionCannotPublishReply() =
        runTest {
            for (change in listOf("consent", "reset", "deletion", "cancel", "close")) {
                assertReplySessionFence(change)
            }
        }

    @Test
    fun lateReplyReceiptCannotApplyUnderChangedCredentialGeneration() =
        runTest {
            val storage = InstallationCoordinatorFixture(Fixtures.record())
            val fixture =
                ComplaintReportFixture(this, storage, mutationHandler = {
                    storage.credentials.payloadRecord = Fixtures.record(generation = 2)
                    respond(mutationAck(), HttpStatusCode.Created, mutationHeaders(HttpStatusCode.Created))
                })
            try {
                val result =
                    assertIs<ReportAttempt.Unresolved>(
                        fixture.repository
                            .submit(mobileReplyRequest())
                            .reportSuccess()
                            .attempt,
                    )
                assertEquals(Block.STALE_BINDING, result.failure.block)
                assertNull(result.application)
                assertEquals(1, storage.pending.slots.size)
                assertTrue(Step.PENDING_DELETE_BEFORE !in storage.faults.trace)
            } finally {
                fixture.close()
            }
        }
}

private suspend fun TestScope.assertReplySessionFence(change: String) {
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
        val first = async { fixture.repository.submit(mobileReplyRequest()) }
        entered.await()
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
        release.complete(Unit)
        assertFailsWith<CancellationException> { first.await() }
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

private val REPLY_WRITE_FAULTS =
    listOf(
        "create-before",
        "create-after",
        "create-readback",
        "create-lie",
        "replace-before",
        "replace-after",
        "replace-readback",
        "replace-lie",
        "unrelated-slot",
    )
