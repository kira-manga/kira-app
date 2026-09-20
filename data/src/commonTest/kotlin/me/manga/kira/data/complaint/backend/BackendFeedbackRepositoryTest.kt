package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.platform.storage.PendingComplaintSlot
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
class BackendFeedbackRepositoryTest {
    @Test
    fun aPriorManual404CannotAuthorizeNewCreateAfterTheCurrentRecoveryPassFails() =
        runTest {
            val fixture = failingSecondStatusFixture()
            val slot = reportSlot(mutationReport(key = historyId(10)))
            fixture.storage.pending.slots += slot
            try {
                fixture.repository.reconcile().reportSuccess()
                val next = fixture.repository.submit(mutationReport()).reportSuccess()
                assertEquals(
                    Block.RECONCILIATION_REQUIRED,
                    assertIs<ReportAttempt.Unresolved>(next.attempt).failure.block,
                )
                assertEquals(2, fixture.requests.size)
                assertTrue(fixture.requests.all { it.url.encodedPath.endsWith(Policy.STATUS_PATH) })
                assertTrue(
                    slot.sameAs(
                        fixture.storage.pending.slots
                            .single(),
                    ),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun direct4xxRetainsTheSameLiveActionAndRestartedStatusAppliesOnlyMetadata() =
        runTest {
            val statuses = listOf(403, 404, 409, 412, 413, 415, 429)
            for (status in statuses) {
                val storage = InstallationCoordinatorFixture(Fixtures.record())
                val slot = assertDirectRetained(storage, HttpStatusCode.fromValue(status))
                assertRestartedMetadataApplication(storage, slot, rejected = status == 409)
            }
        }

    @Test
    fun directApplicationSurvivesUncertainDeletionWithoutReallocatingOrRecreatingOnRetry() =
        runTest {
            for (step in listOf(Step.PENDING_DELETE_BEFORE, Step.PENDING_DELETED)) {
                val fixture = successfulReportFixture()
                val report = mutationReport()
                fixture.storage.faults.failAt(step)
                try {
                    assertUncertainDeletionRetry(fixture, report, step)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun notFoundCannotRestoreProseAndOnlyExactLiveInputAtTheAuthenticatedSafeBoundaryRetries() =
        runTest {
            for (nanosPastBoundary in listOf(0, 1)) {
                val report = mutationReport()
                val time = mutationPending(report).times.serverReceiptSafeUntil + nanosPastBoundary.nanoseconds
                val fixture =
                    ComplaintReportFixture(
                        this,
                        sessionHandler = {
                            val body = sessionResponse().replace(SESSION_ISSUED_AT, time.toString())
                            respond(body, HttpStatusCode.OK, sessionHeaders())
                        },
                        mutationHandler = { request -> notFoundOrCreated(request) },
                    )
                try {
                    assertNotFoundRetryPolicy(fixture, report, nanosPastBoundary)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun sixteenUnresolvedSlotsAreReconciledBeforeBlockingNewWritesButDoNotBlockHistory() =
        runTest {
            for (count in listOf(15, 16)) {
                val fixture = ComplaintReportFixture(this, mutationHandler = { request -> notFoundOrCreated(request) })
                val slots = List(count) { reportSlot(mutationReport(key = historyId(it), id = historyId(it + 100))) }
                fixture.storage.pending.slots += slots
                try {
                    assertCapacityAndReads(fixture, slots)
                } finally {
                    fixture.close()
                }
            }
        }
}

private suspend fun TestScope.assertDirectRetained(
    storage: InstallationCoordinatorFixture,
    status: HttpStatusCode,
): PendingComplaintSlot {
    val fixture =
        ComplaintReportFixture(
            this,
            storage = storage,
            mutationHandler = { respond(directProblem(status), status, mutationHeaders(status)) },
        )
    try {
        val report = mutationReport()
        val attempted =
            assertIs<ReportAttempt.Unresolved>(
                fixture.repository
                    .submit(report)
                    .reportSuccess()
                    .attempt,
            )
        assertSame(report, attempted.liveReport)
        assertNull(attempted.application)
        val slot = storage.pending.slots.single()
        assertEquals(PendingComplaintState.MAY_HAVE_DISPATCHED, reportRecord(slot).state)
        assertEquals(1, fixture.requests.size)
        assertTrue(Step.PENDING_DELETE_BEFORE !in storage.faults.trace)
        return slot
    } finally {
        fixture.close()
    }
}

private suspend fun TestScope.assertRestartedMetadataApplication(
    storage: InstallationCoordinatorFixture,
    slot: PendingComplaintSlot,
    rejected: Boolean,
) {
    val fixture =
        ComplaintReportFixture(
            this,
            storage = storage,
            coordinator = storage.restart(),
            mutationHandler = {
                respond(if (rejected) mutationRejected() else mutationApplied(), HttpStatusCode.OK, mutationHeaders())
            },
        )
    try {
        val recovery = fixture.repository.reconcile().reportSuccess()
        val item = recovery.entries().single()
        val completed = assertIs<ReportAttempt.Completed>(item.attempt)
        assertNull(completed.liveReport)
        assertMetadataApplication(completed, rejected)
        assertTrue(item.slot.sameAs(slot))
        assertTrue(storage.pending.slots.isEmpty())
        assertTrue(assertNotNull(storage.credentials.payloadRecord).sameAs(Fixtures.record()))
        val request = fixture.requests.single()
        assertTrue(request.url.encodedPath.endsWith(Policy.STATUS_PATH))
    } finally {
        fixture.close()
    }
}

private suspend fun assertNotFoundRetryPolicy(
    fixture: ComplaintReportFixture,
    report: ComplaintReportRequest,
    nanosPastBoundary: Int,
) {
    val slot = reportSlot(report)
    val slots = fixture.storage.pending.slots
    slots += slot
    val recovery = fixture.repository.reconcile().reportSuccess()
    val metadata = recovery.entries().single()
    assertNull(assertIs<ReportAttempt.Unresolved>(metadata.attempt).liveReport)
    assertEquals(1, fixture.requests.size)
    val wrong = fixture.repository.retry(mutationReport(subject = "Changed")).reportSuccess()
    assertEquals(Block.INVALID_CANDIDATE, assertIs<ReportAttempt.Unresolved>(wrong).failure.block)
    assertEquals(1, fixture.requests.size)
    val retry = fixture.repository.retry(report).reportSuccess()
    assertSame(report, retry.liveReport)
    if (nanosPastBoundary == 0) {
        assertIs<ReportAttempt.Completed>(retry)
        assertEquals(3, fixture.requests.size)
        assertTrue(slots.isEmpty())
    } else {
        assertEquals(Block.RECEIPT_WINDOW_EXPIRED, assertIs<ReportAttempt.Unresolved>(retry).failure.block)
        assertEquals(2, fixture.requests.size)
        assertTrue(slot.sameAs(slots.single()))
    }
}

private suspend fun TestScope.assertCapacityAndReads(
    fixture: ComplaintReportFixture,
    slots: List<PendingComplaintSlot>,
) {
    val submission = fixture.repository.submit(mutationReport()).reportSuccess()
    assertEquals(slots.size, submission.recovery.entries().size)
    if (slots.size == 16) {
        assertEquals(
            Block.PENDING_CAPACITY_REACHED,
            assertIs<ReportAttempt.Unresolved>(submission.attempt).failure.block,
        )
        assertEquals(16, fixture.requests.size)
    } else {
        assertIs<ReportAttempt.Completed>(submission.attempt)
        assertEquals(16, fixture.requests.size)
    }
    assertEquals(slots.size, fixture.storage.pending.slots.size)
    assertTrue(
        slots.all { old ->
            fixture.storage.pending.slots
                .any { old.sameAs(it) }
        },
    )
    assertRetainedReportHistoryRead(fixture)
}

private suspend fun TestScope.assertRetainedReportHistoryRead(fixture: ComplaintReportFixture) {
    val history = ComplaintHistoryFixture(this, storage = fixture.storage)
    val mutations =
        fixture.storage.faults.mutations
            .toList()
    try {
        assertIs<ComplaintHistory.Backend>(history.repository.loadUserComplaints().reportSuccess())
        assertEquals(1, history.historyRequests.size)
        assertTrue(history.enrollment.requests.isEmpty())
        assertEquals(mutations, fixture.storage.faults.mutations)
    } finally {
        history.close()
    }
}

private suspend fun assertUncertainDeletionRetry(
    fixture: ComplaintReportFixture,
    report: ComplaintReportRequest,
    step: Step,
) {
    val attempted =
        fixture.repository
            .submit(report)
            .reportSuccess()
            .attempt
    val unresolved = assertIs<ReportAttempt.Unresolved>(attempted)
    assertSame(report, unresolved.liveReport)
    assertIs<ReportActionState.Applied>(unresolved.application)
    val retry = fixture.repository.retry(report).reportSuccess()
    if (step == Step.PENDING_DELETE_BEFORE) {
        assertIs<ReportAttempt.Completed>(retry)
        assertEquals(2, fixture.requests.size)
    } else {
        assertEquals(Block.MISSING, assertIs<ReportAttempt.Unresolved>(retry).failure.block)
        assertEquals(1, fixture.requests.size)
    }
    assertTrue(
        fixture.storage.pending.slots
            .isEmpty(),
    )
    assertEquals(1, fixture.requests.count { it.url.encodedPath.endsWith(Policy.CREATE_PATH) })
}

private fun assertMetadataApplication(
    completed: ReportAttempt.Completed,
    rejected: Boolean,
) {
    if (rejected) {
        assertIs<ReportActionState.Rejected>(completed.application)
    } else {
        assertIs<ReportActionState.Applied>(completed.application)
    }
}
