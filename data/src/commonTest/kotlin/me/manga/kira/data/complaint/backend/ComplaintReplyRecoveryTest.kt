package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportReceiptRejection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.ExperimentalTime
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

@OptIn(ExperimentalTime::class)
class ComplaintReplyRecoveryTest {
    @Test
    fun directParent404AndDeletion409RetainEvidenceUntilRestartedExactStatusRejects() =
        runTest {
            for (code in ComplaintReplyRejection.entries) assertReplyDirectThenRecovered(code)
        }

    @Test
    fun cold404NeverRecreatesAndOnlyExactLiveReplyWithinServerWindowCanRetry() =
        runTest {
            for (pastBoundary in listOf(0, 1)) assertReplyReceiptWindow(pastBoundary)
        }

    @Test
    fun mixedReportReplyRecoveryUsesOneIssuerAndNoRecoveredInputsOrCreationBytes() =
        runTest {
            val fixture =
                ComplaintReportFixture(this, mutationHandler = { request ->
                    val body =
                        assertIs<JsonObject>(Json.parseToJsonElement(request.body.toByteArray().decodeToString()))
                    val code: ComplaintCreationRejection =
                        if (body.historyString("operation") == "OWNER_REPLY") {
                            ComplaintReplyRejection.COMPLAINT_PARENT_NOT_FOUND
                        } else {
                            ComplaintCreateRejection.COMPLAINT_CAPACITY_REACHED
                        }
                    respond(mutationRejected(code), HttpStatusCode.OK, mutationHeaders())
                })
            fixture.storage.pending.slots += reportSlot(mutationReport(key = historyId(20), id = historyId(21)))
            fixture.storage.pending.slots += reportSlot(mobileReplyRequest())
            val consumer = fixture.consumer(mobileReplyNoInputs())
            try {
                val recovered = consumer.reconcile().reportSuccess()
                assertNull(recovered.stopped)
                val codes =
                    recovered.entries().map {
                        assertIs<ComplaintReportApplication.Rejected>(
                            assertIs<ComplaintReportAttempt.Completed>(it.attempt).application,
                        ).code
                    }
                assertEquals(
                    setOf(
                        ComplaintReportReceiptRejection.COMPLAINT_CAPACITY_REACHED,
                        ComplaintReportReceiptRejection.COMPLAINT_PARENT_NOT_FOUND,
                    ),
                    codes.toSet(),
                )
                assertEquals(2, fixture.requests.size)
                assertTrue(fixture.requests.all { it.url.encodedPath.endsWith(Policy.STATUS_PATH) })
                assertTrue(fixture.requests.all { it.headers[Policy.IDEMPOTENCY_HEADER] == null })
                assertTrue(fixture.sentBodies.none { it.contains("Report body") || it.contains("\"body\"") })
                assertTrue(fixture.storage.pending.slots.isEmpty())
            } finally {
                consumer.close()
                fixture.close()
            }
        }

    @Test
    fun parentNotFoundStatusIsNotOperationNotFoundAndCannotGrantLiveResubmission() =
        runTest {
            val reply = mobileReplyRequest()
            val fixture =
                ComplaintReportFixture(this, mutationHandler = {
                    respond(
                        mutationProblem(HttpStatusCode.NotFound, "COMPLAINT_PARENT_NOT_FOUND"),
                        HttpStatusCode.NotFound,
                        mutationHeaders(HttpStatusCode.NotFound),
                    )
                })
            val slot = reportSlot(reply)
            fixture.storage.pending.slots += slot
            try {
                val result = assertIs<ReportAttempt.Unresolved>(fixture.repository.retry(reply).reportSuccess())
                assertNull(result.application)
                assertSame(reply, result.liveReport)
                assertEquals(1, fixture.requests.size)
                assertTrue(fixture.requests.single().url.encodedPath.endsWith(Policy.STATUS_PATH))
                assertTrue(slot.sameAs(fixture.storage.pending.slots.single()))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun replyStatusCannotApplyToTheNextReportOrDeleteEitherUnmatchedSlot() =
        runTest {
            val fixture =
                ComplaintReportFixture(this, mutationHandler = {
                    respond(mutationRejected(), HttpStatusCode.OK, mutationHeaders())
                })
            val reply = reportSlot(mobileReplyRequest())
            val report = reportSlot(mutationReport(key = historyId(32)))
            fixture.storage.pending.slots += listOf(reply, report)
            val work = assertNotNull(fixture.works.begin(Job()))
            try {
                val first = fixture.coordinator.beginReportAction(work, ReportStart.Retained(reply)).success()
                val result =
                    fixture.coordinator
                        .readReportStatus(first, fixture.readySession(first), fixture.sessions, fixture.http)
                        .success()
                fixture.coordinator.releaseReportAction(first).success()
                fixture.coordinator.beginReportAction(work, ReportStart.Retained(report)).success()
                assertRefused(Block.STALE_BINDING, fixture.coordinator.applyReportOutcome(result, fixture.sessions))
                assertNull(work.application())
                assertEquals(2, fixture.storage.pending.slots.size)
                assertTrue(Step.PENDING_DELETE_BEFORE !in fixture.storage.faults.trace)
            } finally {
                fixture.coordinator.finishReport(work)
                work.job.cancel()
                fixture.close()
            }
        }
}

private suspend fun TestScope.assertReplyDirectThenRecovered(code: ComplaintReplyRejection) {
    val storage = InstallationCoordinatorFixture(Fixtures.record())
    val status = HttpStatusCode.fromValue(code.status)
    val original =
        ComplaintReportFixture(this, storage, mutationHandler = {
            respond(mutationProblem(status, code.wireCode), status, mutationHeaders(status))
        })
    try {
        val reply = mobileReplyRequest(body = "private vanished reply")
        val result = original.repository.submit(reply).reportSuccess()
        assertSame(reply, assertIs<ReportAttempt.Unresolved>(result.attempt).liveReport)
        assertEquals(1, original.requests.size)
        assertEquals(PendingComplaintState.MAY_HAVE_DISPATCHED, reportRecord(storage.pending.slots.single()).state)
        assertTrue(Step.PENDING_DELETE_BEFORE !in storage.faults.trace)
    } finally {
        original.close()
    }
    val restarted =
        ComplaintReportFixture(this, storage, storage.restart(), mutationHandler = {
            respond(mutationRejected(code), HttpStatusCode.OK, mutationHeaders())
        })
    try {
        val outcome = restarted.repository.reconcile().reportSuccess().entries().single().attempt
        val completed = assertIs<ReportAttempt.Completed>(outcome)
        assertNull(completed.liveReport)
        assertEquals(code, assertIs<ReportActionState.Rejected>(completed.application).code)
        assertTrue(storage.pending.slots.isEmpty())
        assertTrue(restarted.requests.single().url.encodedPath.endsWith(Policy.STATUS_PATH))
        assertTrue(!restarted.sentBodies.single().contains("private vanished reply"))
    } finally {
        restarted.close()
    }
}

@OptIn(ExperimentalTime::class)
private suspend fun TestScope.assertReplyReceiptWindow(pastBoundary: Int) {
    val reply = mobileReplyRequest()
    val slot = reportSlot(reply)
    val time = reportRecord(slot).times.serverReceiptSafeUntil + pastBoundary.nanoseconds
    val fixture =
        ComplaintReportFixture(
            this,
            sessionHandler = {
                respond(
                    sessionResponse().replace(SESSION_ISSUED_AT, time.toString()),
                    HttpStatusCode.OK,
                    sessionHeaders(),
                )
            },
            mutationHandler = { request -> notFoundOrCreated(request) },
        )
    fixture.storage.pending.slots += slot
    try {
        val cold = fixture.repository.reconcile().reportSuccess().entries().single().attempt
        assertNull(assertIs<ReportAttempt.Unresolved>(cold).liveReport)
        assertEquals(1, fixture.requests.size)
        val changed = mobileReplyRequest(parentId = "44444444-4444-5444-8444-444444444444")
        val mismatch = assertIs<ReportAttempt.Unresolved>(fixture.repository.retry(changed).reportSuccess())
        assertEquals(Block.INVALID_CANDIDATE, mismatch.failure.block)
        assertEquals(1, fixture.requests.size)
        val retry = fixture.repository.retry(reply).reportSuccess()
        if (pastBoundary == 0) {
            assertIs<ReportAttempt.Completed>(retry)
            assertEquals(3, fixture.requests.size)
            assertTrue(fixture.storage.pending.slots.isEmpty())
        } else {
            assertEquals(Block.RECEIPT_WINDOW_EXPIRED, assertIs<ReportAttempt.Unresolved>(retry).failure.block)
            assertEquals(2, fixture.requests.size)
            assertTrue(slot.sameAs(fixture.storage.pending.slots.single()))
        }
    } finally {
        fixture.close()
    }
}
