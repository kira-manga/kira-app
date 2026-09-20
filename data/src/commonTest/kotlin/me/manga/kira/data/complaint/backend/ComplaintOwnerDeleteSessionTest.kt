package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class ComplaintOwnerDeleteSessionTest {
    @Test
    fun oneMatched401RefreshKeepsTheExactDeleteAndNeverLoopsAfterTheSecond401() =
        runTest {
            for (secondUnauthorized in listOf(false, true)) assertOwnerDeleteRefreshBound(secondUnauthorized)
        }

    @Test
    fun status401CanRefreshOnlyStatusAndGrantsNoBodylessDeleteDispatchAuthority() =
        runTest {
            val f = ownerDeleteRefreshFixture(secondUnauthorized = true)
            val work = assertNotNull(f.works.begin(Job()))
            try {
                f.assertOwnerDeleteStatusRefreshOnly(work)
            } finally {
                f.coordinator.finishReport(work)
                work.job.cancel()
                f.close()
            }
        }

    @Test
    fun staleSessionCannotSendDeleteOrApply204AndOldEvictionCannotRemoveTheNewLease() =
        runTest {
            val f = mobileOwnerDeleteReportFixture()
            val work = assertNotNull(f.works.begin(Job()))
            try {
                f.assertStaleOwnerDeleteSessionFenced(work)
            } finally {
                f.coordinator.finishReport(work)
                work.job.cancel()
                f.close()
            }
        }
}

private suspend fun ComplaintReportFixture.assertOwnerDeleteStatusRefreshOnly(work: ReportWork) {
    val deletion = mobileOwnerDeleteRequest()
    val slot = mobileOwnerDeleteSlot(deletion)
    storage.pending.slots += slot
    val binding = coordinator.beginReportAction(work, ReportStart.Retained(slot, deletion)).success()
    val first = coordinator.readOwnerDeleteStatus(binding, readySession(binding), sessions, http).success()
    coordinator.authorizeReportRefresh(first, sessions).success()
    sessions.invalidateReportSession(first.session)
    val renewed = readySession(binding)
    assertRefused(Block.RECONCILIATION_REQUIRED, coordinator.dispatchOwnerDelete(binding, renewed, sessions, http))
    val second = coordinator.readOwnerDeleteStatus(binding, renewed, sessions, http).success()
    assertRefused(Block.RECONCILIATION_REQUIRED, coordinator.authorizeReportRefresh(second, sessions))
    assertOnlyEditStatusRequests()
    assertEquals(2, requests.size)
    assertTrue(slot.sameAs(storage.pending.slots.single()))
}

private suspend fun ComplaintReportFixture.assertStaleOwnerDeleteSessionFenced(work: ReportWork) {
    val binding = coordinator.beginReportAction(work, ReportStart.New(mobileOwnerDeleteRequest())).success()
    val original = readySession(binding)
    val prepared = coordinator.prepareReport(binding, original, sessions).success()
    sessions.invalidateReportSession(original)
    val current = readySession(prepared)
    sessions.invalidateReportSession(original)
    assertTrue(sessions.reportSessionIsCurrent(current))
    assertRefused(Block.STALE_BINDING, coordinator.dispatchOwnerDelete(prepared, original, sessions, http))
    assertTrue(requests.isEmpty())
    val outcome = coordinator.dispatchOwnerDelete(prepared, current, sessions, http).success()
    assertIs<ComplaintOwnerDeleteHttpResult.Applied>(outcome.result)
    sessions.invalidateReportSession(current)
    readySession(outcome.binding)
    assertRefused(Block.STALE_BINDING, coordinator.applyReportOutcome(outcome, sessions))
    assertNull(work.application())
    assertEquals(1, storage.pending.slots.size)
    assertEquals(1, requests.size)
}

private suspend fun TestScope.assertOwnerDeleteRefreshBound(secondUnauthorized: Boolean) {
    val f = ownerDeleteRefreshFixture(secondUnauthorized)
    try {
        val result = f.repository.submit(mobileOwnerDeleteRequest()).reportSuccess().attempt
        if (secondUnauthorized) {
            assertIs<ReportAttempt.Unresolved>(result)
        } else {
            assertIs<ReportAttempt.Completed>(result)
        }
        assertEquals(listOf(HttpMethod.Delete, HttpMethod.Delete), f.requests.map { it.method })
        assertEquals(2, f.sessionRequests.size)
        assertEquals(listOf("", ""), f.sentBodies)
        assertEquals(listOf(Fixtures.KEY, Fixtures.KEY), f.requests.map { it.headers[Policy.IDEMPOTENCY_HEADER] })
        assertEquals(
            listOf("\"complaint-$MOBILE_EDIT_ID-v7\"", "\"complaint-$MOBILE_EDIT_ID-v7\""),
            f.requests.map { it.headers[HttpHeaders.IfMatch] },
        )
        assertEquals(if (secondUnauthorized) 1 else 0, f.storage.pending.slots.size)
    } finally {
        f.close()
    }
}

private fun TestScope.ownerDeleteRefreshFixture(secondUnauthorized: Boolean): ComplaintReportFixture {
    var calls = 0
    return ComplaintReportFixture(
        this,
        mutationHandler = {
            if (++calls == 1 || secondUnauthorized) {
                val status = HttpStatusCode.Unauthorized
                respond(mutationProblem(status, "UNAUTHORIZED"), status, mutationHeaders(status))
            } else {
                respond("", HttpStatusCode.NoContent, mobileOwnerDeleteHeaders())
            }
        },
    )
}
