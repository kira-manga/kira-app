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
class ComplaintEditRetryTest {
    @Test
    fun explicitSameHandleRetryKeepsTheCapturedKeyTargetReplacementAndOriginalPrecondition() =
        runTest {
            val f = ambiguousEditFixture()
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
                val live = assertIs<EditLiveHandle>(consumer.preparedConsumerEdit())
                val request = live.request
                assertIs<ComplaintReportAttempt.Unresolved>(consumer.submit(live).reportSuccess().attempt)
                nextKey = MUTATION_OTHER_KEY
                assertIs<ComplaintReportAttempt.Completed>(consumer.retry(live).reportSuccess())
                assertSame(request, live.request)
                assertEquals(1, keys)
                f.assertExactEditRetryWire(request)
            } finally {
                consumer.close()
                f.close()
            }
        }

    @Test
    fun onlyAnExactLiveTupleCanUseStatus404AtTheInclusiveServerIssuedSafeWindow() =
        runTest {
            for (pastBoundary in listOf(-1, 0, 1)) assertEditReceiptWindow(pastBoundary)
        }

    @Test
    fun foreignOrGeneric404AndPriorMissingProofCannotAuthorizePatchAfterCurrentStatusFails() =
        runTest {
            for (code in listOf("COMPLAINT_NOT_FOUND", "NOT_FOUND")) {
                assertNonOperation404CannotRetryEdit(code)
            }
            assertPriorMissingEditProofIsNotReused()
        }
}

private fun ComplaintReportFixture.assertExactEditRetryWire(request: ComplaintEditRequest) {
    assertEquals(listOf(HttpMethod.Patch, HttpMethod.Post, HttpMethod.Patch), requests.map { it.method })
    assertEquals(listOf(Fixtures.KEY, null, Fixtures.KEY), requests.map { it.headers[Policy.IDEMPOTENCY_HEADER] })
    assertEquals(
        listOf(request.target.precondition, null, request.target.precondition),
        requests.map { it.headers[HttpHeaders.IfMatch] },
    )
    assertEquals(sentBodies.first(), sentBodies.last())
    assertTrue(sentBodies[1].contains("\"operation\":\"OWNER_EDIT\""))
    assertTrue(!sentBodies[1].contains("\"body\""))
}

private fun TestScope.ambiguousEditFixture(): ComplaintReportFixture {
    var patches = 0
    return ComplaintReportFixture(
        this,
        mutationHandler = { request ->
            if (request.method == HttpMethod.Patch && ++patches == 1) {
                val status = HttpStatusCode.ServiceUnavailable
                respond(historyProblem(status), status, mobileEditHeaders(status))
            } else {
                mobileEditNotFoundOrApplied(request)
            }
        },
    )
}

@OptIn(ExperimentalTime::class)
private suspend fun TestScope.assertEditReceiptWindow(pastBoundary: Int) {
    val edit = mobileEditRequest()
    val slot = mobileEditSlot(edit)
    val f = editWindowFixture(slot, pastBoundary)
    f.storage.pending.slots += slot
    try {
        val cold = f.repository.reconcile().reportSuccess().entries().single().attempt
        assertNull(assertIs<ReportAttempt.Unresolved>(cold).liveReport)
        assertEquals(1, f.requests.size)
        f.assertEditDriftRefused()
        val retry = f.repository.retry(edit).reportSuccess()
        assertSame(edit, retry.liveReport)
        f.assertEditWindowResult(retry, pastBoundary, slot)
    } finally {
        f.close()
    }
}

@OptIn(ExperimentalTime::class)
private fun TestScope.editWindowFixture(slot: PendingComplaintSlot, pastBoundary: Int): ComplaintReportFixture {
    val time = reportRecord(slot).times.serverReceiptSafeUntil + pastBoundary.nanoseconds
    return ComplaintReportFixture(
        this,
        sessionHandler = {
            respond(sessionResponse().replace(SESSION_ISSUED_AT, time.toString()), HttpStatusCode.OK, sessionHeaders())
        },
        mutationHandler = { mobileEditNotFoundOrApplied(it) },
    )
}

private fun ComplaintReportFixture.assertEditWindowResult(
    retry: ReportAttempt,
    pastBoundary: Int,
    slot: PendingComplaintSlot,
) {
    if (pastBoundary <= 0) {
        assertIs<ReportAttempt.Completed>(retry)
        assertEquals(3, requests.size)
        assertTrue(storage.pending.slots.isEmpty())
    } else {
        assertEquals(Block.RECEIPT_WINDOW_EXPIRED, assertIs<ReportAttempt.Unresolved>(retry).failure.block)
        assertEquals(2, requests.size)
        assertTrue(slot.sameAs(storage.pending.slots.single()))
    }
}

private suspend fun ComplaintReportFixture.assertEditDriftRefused() {
    val changed =
        listOf(
            mobileEditRequest(subject = "Changed"),
            mobileEditRequest(body = "Changed"),
            mobileEditRequest(target = mobileEditTarget(version = 8)),
            mobileEditRequest(target = mobileEditTarget(id = MOBILE_REPLY_PARENT)),
            mobileEditRequest(scope = MOBILE_EDIT_SCOPE),
            mobileEditRequest(target = mobileEditTarget(shape = ComplaintEditShape.BODY_ONLY), subject = null),
            mutationReport(),
            mobileReplyRequest(),
        )
    for (candidate in changed) {
        val failure = assertIs<ReportAttempt.Unresolved>(repository.retry(candidate).reportSuccess()).failure
        assertEquals(Block.INVALID_CANDIDATE, failure.block)
    }
    val missing = repository.retry(mobileEditRequest(key = MUTATION_OTHER_KEY)).reportSuccess()
    assertEquals(Block.MISSING, assertIs<ReportAttempt.Unresolved>(missing).failure.block)
    assertEquals(1, requests.size)
}

private suspend fun TestScope.assertNonOperation404CannotRetryEdit(code: String) {
    val f =
        ComplaintReportFixture(
            this,
            mutationHandler = {
                val status = HttpStatusCode.NotFound
                respond(mutationProblem(status, code), status, mobileEditHeaders(status, direct = false))
            },
        )
    f.storage.pending.slots += mobileEditSlot()
    try {
        assertIs<ReportAttempt.Unresolved>(f.repository.retry(mobileEditRequest()).reportSuccess())
        f.assertOnlyEditStatusRequests()
        assertEquals(1, f.requests.size)
        assertEquals(1, f.storage.pending.slots.size)
    } finally {
        f.close()
    }
}

private suspend fun TestScope.assertPriorMissingEditProofIsNotReused() {
    var calls = 0
    val f =
        ComplaintReportFixture(
            this,
            mutationHandler = { request ->
                if (++calls == 1) {
                    mobileEditNotFoundOrApplied(request)
                } else {
                    val status = HttpStatusCode.ServiceUnavailable
                    respond(historyProblem(status), status, mobileEditHeaders(status, direct = false))
                }
            },
        )
    val slot = mobileEditSlot()
    f.storage.pending.slots += slot
    try {
        f.repository.reconcile().reportSuccess()
        assertIs<ReportAttempt.Unresolved>(f.repository.retry(mobileEditRequest()).reportSuccess())
        assertEquals(2, f.requests.size)
        f.assertOnlyEditStatusRequests()
        assertTrue(slot.sameAs(f.storage.pending.slots.single()))
    } finally {
        f.close()
    }
}
