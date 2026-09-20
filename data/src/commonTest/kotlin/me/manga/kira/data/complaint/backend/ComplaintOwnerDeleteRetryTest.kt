package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.platform.storage.PendingComplaintSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.ExperimentalTime
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

@OptIn(ExperimentalTime::class)
class ComplaintOwnerDeleteRetryTest {
    @Test
    fun explicitSameHandleRetryKeepsTheCapturedKeyTargetTagAndBodylessFingerprint() =
        runTest {
            val f = ambiguousOwnerDeleteFixture()
            var keys = 0
            var nextKey = Fixtures.KEY
            val consumer =
                f.consumer(
                    mobileEditInputs {
                        keys++
                        nextKey
                    },
                )
            try {
                val live = assertIs<OwnerDeleteLiveHandle>(consumer.preparedConsumerOwnerDelete())
                val request = live.request
                assertIs<ComplaintReportAttempt.Unresolved>(consumer.submit(live).reportSuccess().attempt)
                nextKey = MUTATION_OTHER_KEY
                assertIs<ComplaintReportAttempt.Completed>(consumer.retry(live).reportSuccess())
                assertSame(request, live.request)
                assertEquals(1, keys)
                f.assertOriginalOwnerDeleteRetryWire(request)
            } finally {
                consumer.close()
                f.close()
            }
        }

    @Test
    fun coldStatus404NeverSendsButTheOriginalLiveTupleMayRetryWithinTheInclusiveAuthenticatedWindow() =
        runTest {
            for (pastBoundary in listOf(-1, 0, 1)) assertOwnerDeleteReceiptWindow(pastBoundary)
        }

    @Test
    fun genericOrForeign404AndPriorMissingProofCannotAuthorizeDeleteAfterCurrentStatusFails() =
        runTest {
            for (code in listOf("COMPLAINT_NOT_FOUND", "NOT_FOUND")) assertOwnerDeleteNonOperation404(code)
            assertPriorOwnerDeleteMissingProofNotReused()
        }
}

private fun TestScope.ambiguousOwnerDeleteFixture(): ComplaintReportFixture {
    var deletes = 0
    return ComplaintReportFixture(
        this,
        mutationHandler = { request ->
            if (request.method == HttpMethod.Delete && ++deletes == 1) {
                val status = HttpStatusCode.ServiceUnavailable
                respond(historyProblem(status), status, mutationHeaders(status))
            } else {
                mobileOwnerDeleteNotFoundOrApplied(request)
            }
        },
    )
}

private fun ComplaintReportFixture.assertOriginalOwnerDeleteRetryWire(request: ComplaintOwnerDeleteRequest) {
    assertEquals(listOf(HttpMethod.Delete, HttpMethod.Post, HttpMethod.Delete), requests.map { it.method })
    assertEquals(listOf(Fixtures.KEY, null, Fixtures.KEY), requests.map { it.headers[Policy.IDEMPOTENCY_HEADER] })
    assertEquals(listOf(request.precondition, null, request.precondition), requests.map { it.headers[HttpHeaders.IfMatch] })
    assertEquals(requests.first().url, requests.last().url)
    assertEquals("", sentBodies.first())
    assertEquals("", sentBodies.last())
    assertTrue(sentBodies[1].contains("\"operation\":\"OWNER_DELETE\""))
    assertTrue(sentBodies[1].contains(request.pendingFingerprint().encoded))
    assertTrue(!sentBodies[1].contains("\"body\""))
}

@OptIn(ExperimentalTime::class)
private suspend fun TestScope.assertOwnerDeleteReceiptWindow(pastBoundary: Int) {
    val deletion = mobileOwnerDeleteRequest()
    val slot = mobileOwnerDeleteSlot(deletion)
    val time = reportRecord(slot).times.serverReceiptSafeUntil + pastBoundary.nanoseconds
    val f =
        ComplaintReportFixture(
            this,
            sessionHandler = {
                respond(sessionResponse().replace(SESSION_ISSUED_AT, time.toString()), HttpStatusCode.OK, sessionHeaders())
            },
            mutationHandler = { mobileOwnerDeleteNotFoundOrApplied(it) },
        )
    f.storage.pending.slots += slot
    try {
        val cold = assertIs<ReportAttempt.Unresolved>(f.repository.reconcile().reportSuccess().entries().single().attempt)
        assertNull(cold.liveReport)
        f.assertOnlyEditStatusRequests()
        assertEquals(1, f.requests.size)
        f.assertOwnerDeleteDriftRefused()
        val retry = f.repository.retry(deletion).reportSuccess()
        assertSame(deletion, retry.liveReport)
        f.assertOwnerDeleteWindowResult(retry, pastBoundary, slot)
    } finally {
        f.close()
    }
}

private suspend fun ComplaintReportFixture.assertOwnerDeleteDriftRefused() {
    val changes =
        listOf(
            mobileOwnerDeleteRequest(mobileEditTarget(version = 8)),
            mobileOwnerDeleteRequest(mobileEditTarget(id = MOBILE_REPLY_PARENT)),
            mobileOwnerDeleteRequest(scope = MOBILE_EDIT_SCOPE),
            mobileEditRequest(),
            mutationReport(),
            mobileReplyRequest(),
        )
    for (changed in changes) {
        val result = assertIs<ReportAttempt.Unresolved>(repository.retry(changed).reportSuccess())
        assertEquals(Block.INVALID_CANDIDATE, result.failure.block)
    }
    val missing = repository.retry(mobileOwnerDeleteRequest(key = MUTATION_OTHER_KEY)).reportSuccess()
    assertEquals(Block.MISSING, assertIs<ReportAttempt.Unresolved>(missing).failure.block)
    assertEquals(1, requests.size)
}

private fun ComplaintReportFixture.assertOwnerDeleteWindowResult(
    retry: ReportAttempt,
    pastBoundary: Int,
    slot: PendingComplaintSlot,
) {
    if (pastBoundary <= 0) {
        assertIs<ReportAttempt.Completed>(retry)
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Post, HttpMethod.Delete), requests.map { it.method })
        assertTrue(storage.pending.slots.isEmpty())
    } else {
        assertEquals(Block.RECEIPT_WINDOW_EXPIRED, assertIs<ReportAttempt.Unresolved>(retry).failure.block)
        assertEquals(2, requests.size)
        assertOnlyEditStatusRequests()
        assertTrue(slot.sameAs(storage.pending.slots.single()))
    }
}

private suspend fun TestScope.assertOwnerDeleteNonOperation404(code: String) {
    val status = HttpStatusCode.NotFound
    val f =
        ComplaintReportFixture(
            this,
            mutationHandler = { respond(mutationProblem(status, code), status, mutationHeaders(status)) },
        )
    f.storage.pending.slots += mobileOwnerDeleteSlot()
    try {
        assertIs<ReportAttempt.Unresolved>(f.repository.retry(mobileOwnerDeleteRequest()).reportSuccess())
        f.assertOnlyEditStatusRequests()
        assertEquals(1, f.requests.size)
        assertEquals(1, f.storage.pending.slots.size)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertPriorOwnerDeleteMissingProofNotReused() {
    var calls = 0
    val f =
        ComplaintReportFixture(
            this,
            mutationHandler = { request ->
                if (++calls == 1) {
                    mobileOwnerDeleteNotFoundOrApplied(request)
                } else {
                    val status = HttpStatusCode.ServiceUnavailable
                    respond(historyProblem(status), status, mutationHeaders(status))
                }
            },
        )
    val slot = mobileOwnerDeleteSlot()
    f.storage.pending.slots += slot
    try {
        f.repository.reconcile().reportSuccess()
        assertIs<ReportAttempt.Unresolved>(f.repository.retry(mobileOwnerDeleteRequest()).reportSuccess())
        assertEquals(2, f.requests.size)
        f.assertOnlyEditStatusRequests()
        assertTrue(slot.sameAs(f.storage.pending.slots.single()))
    } finally {
        f.close()
    }
}
