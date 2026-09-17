package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Permit
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

@OptIn(ExperimentalTime::class)
class ComplaintReportActionBindingTest {
    @Test
    fun preparedAndDispatchFailuresOrDishonestReadbackNeverConstructCreate() =
        runTest {
            val cases =
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
            for (case in cases) {
                val fixture = ComplaintReportFixture(this)
                fixture.installWriteFault(case)
                try {
                    val report = mutationReport()
                    val attempted = fixture.repository.submit(report).reportSuccess().attempt
                    assertSame(report, assertIs<ReportAttempt.Unresolved>(attempted).liveReport, case)
                    assertTrue(fixture.requests.isEmpty(), case)
                    assertTrue(Step.PENDING_DELETE_BEFORE !in fixture.storage.faults.trace, case)
                    assertTrue(Step.CREATE_BEFORE !in fixture.storage.faults.trace, case)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun onlyExactSuccessorsAdvanceAndInterActionReleaseNeverRecapturesUnrelatedInventory() =
        runTest {
            val fixture = ComplaintReportFixture(this)
            val work = assertNotNull(fixture.works.begin(Job()))
            try {
                val prepared = fixture.assertExactPreparedSuccessor(work)
                fixture.assertReleaseDoesNotRecapture(prepared)
            } finally {
                fixture.coordinator.finishReport(work)
                work.job.cancel()
                fixture.close()
            }
        }

    @Test
    fun actualPreparedRebaseAndDispatchKeepOriginalTokenClockAndImmutableDispatchedReceiptWindow() =
        runTest {
            val fixture = refreshingReportFixture()
            val work = assertNotNull(fixture.works.begin(Job()))
            try {
                val first = fixture.assertPreparedExpiryRebase(work)
                fixture.assertRefreshKeepsDispatchedBytes(first)
            } finally {
                fixture.coordinator.finishReport(work)
                work.job.cancel()
                fixture.close()
            }
        }

    @Test
    fun statusAOrCopiedOutcomeCannotClearBAndFailedDeletionKeepsIdempotentAppliedState() =
        runTest {
            val fixture =
                ComplaintReportFixture(
                    this,
                    mutationHandler = { respond(mutationRejected(), HttpStatusCode.OK, mutationHeaders()) },
                )
            val a = reportSlot()
            val b = reportSlot(mutationReport(key = historyId(2)))
            fixture.storage.pending.slots += listOf(a, b)
            val work = assertNotNull(fixture.works.begin(Job()))
            try {
                val outcomeA = fixture.boundStatus(work, a)
                val copied = ReportExchange.Status(outcomeA.binding, outcomeA.session, outcomeA.request, outcomeA.result)
                assertRefused(Block.STALE_BINDING, fixture.coordinator.applyReportOutcome(copied, fixture.sessions))
                assertNull(work.application())
                fixture.coordinator.releaseReportAction(outcomeA.binding).success()
                val outcomeB = fixture.boundStatus(work, b)
                assertRefused(Block.STALE_BINDING, fixture.coordinator.applyReportOutcome(outcomeA, fixture.sessions))
                fixture.assertApplicationPrecedesRetriedDeletion(outcomeB, a)
            } finally {
                fixture.coordinator.finishReport(work)
                work.job.cancel()
                fixture.close()
            }
        }
}

private suspend fun ComplaintReportFixture.assertExactPreparedSuccessor(work: ReportWork): ReportActionBinding {
    val ordinary = coordinator.admit().success()
    val history = assertIs<ComplaintHistorySessionResult.Ready>(sessions.historySession()).session
    val binding = coordinator.beginReportAction(work, ReportStart.New(mutationReport())).success()
    val session = readySession(binding)
    val prepared = coordinator.prepareReport(binding, session, sessions).success()
    assertSame(history.entry, session.entry)
    assertRefused(Block.STALE_BINDING, coordinator.checkReportSession(binding))
    assertOlderAuthorityRefused(prepared, history, ordinary)
    assertSame(session.entry, readySession(prepared).entry)
    return prepared
}

private suspend fun ComplaintReportFixture.assertOlderAuthorityRefused(
    prepared: ReportActionBinding,
    history: ComplaintHistorySession,
    ordinary: Permit,
) {
    val copy = ReportActionBinding(prepared.permit, prepared.work, prepared.liveReport, prepared.observation)
    assertRefused(Block.STALE_BINDING, coordinator.checkReportSession(copy))
    assertRefused(Block.STALE_BINDING, storage.restart().checkReportSession(prepared))
    assertRefused(
        Block.STALE_BINDING,
        coordinator.applyReconciliationIfCurrent(history.permit) { error("old history observation") },
    )
    assertRefused(
        Block.RECONCILIATION_REQUIRED,
        coordinator.applyIfCurrent(ordinary) { error("ordinary pending exception") },
    )
}

private suspend fun ComplaintReportFixture.assertReleaseDoesNotRecapture(prepared: ReportActionBinding) {
    val exchange = coordinator.dispatchReport(prepared, readySession(prepared), sessions, http).success()
    assertEquals(ReportActionStage.MAY_HAVE_DISPATCHED, exchange.binding.stage)
    assertRefused(Block.STALE_BINDING, coordinator.checkReportSession(prepared))
    coordinator.releaseReportAction(exchange.binding).success()
    storage.pending.slots += reportSlot(mutationReport(key = historyId(2)))
    assertRefused(
        Block.STALE_BINDING,
        coordinator.beginReportAction(prepared.work, ReportStart.New(mutationReport(key = historyId(3)))),
    )
    assertEquals(1, requests.size)
    assertEquals(1, sessionRequests.size)
    assertEquals(2, storage.pending.slots.size)
}

@OptIn(ExperimentalTime::class)
private fun TestScope.refreshingReportFixture(): ComplaintReportFixture {
    var sessionCalls = 0
    return ComplaintReportFixture(
        this,
        sessionHandler = {
            val issuedAt = Instant.parse(SESSION_ISSUED_AT) + (900 * sessionCalls++).seconds
            val body = sessionResponse().replace(SESSION_ISSUED_AT, issuedAt.toString())
            respond(body, HttpStatusCode.OK, sessionHeaders())
        },
        mutationHandler = {
            respond(
                mutationProblem(HttpStatusCode.Unauthorized, "UNAUTHORIZED"),
                HttpStatusCode.Unauthorized,
                mutationHeaders(HttpStatusCode.Unauthorized),
            )
        },
    )
}

@OptIn(ExperimentalTime::class)
private suspend fun ComplaintReportFixture.assertPreparedExpiryRebase(work: ReportWork): ReportExchange.Create {
    val binding = coordinator.beginReportAction(work, ReportStart.New(mutationReport())).success()
    val initial = readySession(binding)
    val prepared = coordinator.prepareReport(binding, initial, sessions).success()
    clock += 899.seconds
    assertSame(initial.entry, readySession(prepared).entry)
    clock += 1.seconds
    val renewed = readySession(prepared)
    assertNotSame(initial.entry, renewed.entry)
    sessions.invalidateReportSession(initial)
    assertTrue(sessions.reportSessionIsCurrent(renewed))
    val first = coordinator.dispatchReport(prepared, renewed, sessions, http).success()
    val dispatched = assertNotNull(first.binding.pendingRecord)
    assertEquals(renewed.response.issuedAt, dispatched.times.sessionIssuedAt)
    assertEquals(initial.response.issuedAt, dispatched.times.createdAt)
    return first
}

@OptIn(ExperimentalTime::class)
private suspend fun ComplaintReportFixture.assertRefreshKeepsDispatchedBytes(first: ReportExchange.Create) {
    val exactDispatchedSlot = assertNotNull(first.binding.slot)
    val safeUntil = assertNotNull(first.binding.pendingRecord).times.serverReceiptSafeUntil
    coordinator.authorizeReportRefresh(first, sessions).success()
    sessions.invalidateReportSession(first.session)
    val refreshed = readySession(first.binding)
    sessions.invalidateReportSession(first.session)
    assertTrue(sessions.reportSessionIsCurrent(refreshed))
    val retry = coordinator.dispatchReport(first.binding, refreshed, sessions, http).success()
    assertTrue(exactDispatchedSlot.sameAs(assertNotNull(retry.binding.slot)))
    assertEquals(safeUntil, retry.binding.pendingRecord?.times?.serverReceiptSafeUntil)
    assertRefused(Block.RECONCILIATION_REQUIRED, coordinator.authorizeReportRefresh(retry, sessions))
    assertEquals(3, sessionRequests.size)
    assertEquals(2, requests.size)
    assertEquals(sentBodies[0], sentBodies[1])
    assertEquals(2, storage.faults.trace.count { it == Step.PENDING_REPLACED })
    assertTrue(exactDispatchedSlot.sameAs(storage.pending.slots.single()))
}

private suspend fun ComplaintReportFixture.boundStatus(
    work: ReportWork,
    slot: PendingComplaintSlot,
): ReportExchange.Status {
    val binding = coordinator.beginReportAction(work, ReportStart.Retained(slot)).success()
    return coordinator.readReportStatus(binding, readySession(binding), sessions, http).success()
}

private suspend fun ComplaintReportFixture.assertApplicationPrecedesRetriedDeletion(
    outcome: ReportExchange.Status,
    untouched: PendingComplaintSlot,
) {
    var observedApplicationBeforeDeletion = false
    storage.faults.onStep = { step ->
        if (step == Step.PENDING_DELETE_BEFORE) {
            assertIs<ReportActionState.Rejected>(outcome.binding.work.application())
            observedApplicationBeforeDeletion = true
        }
    }
    storage.faults.failAt(Step.PENDING_DELETE_BEFORE)
    assertStorageFailure(InstallationStoreFaults.ioFailure, coordinator.applyReportOutcome(outcome, sessions))
    assertTrue(observedApplicationBeforeDeletion)
    assertEquals(2, storage.pending.slots.size)
    val completed = coordinator.applyReportOutcome(outcome, sessions).success()
    assertIs<ReportActionState.Rejected>(completed.application)
    assertTrue(untouched.sameAs(storage.pending.slots.single()))
}
