package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
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

class ComplaintEditSessionTest {
    @Test
    fun oneMatched401RefreshKeepsExactEditAndNeverLoopsAfterTheSecond401() =
        runTest {
            for (secondUnauthorized in listOf(false, true)) assertEditRefreshBound(secondUnauthorized)
        }

    @Test
    fun status401CanRefreshOnlyStatusAndDoesNotGrantAnyPatchDispatchAuthority() =
        runTest {
            val f = editRefreshFixture(secondUnauthorized = true)
            val work = assertNotNull(f.works.begin(Job()))
            try {
                f.assertEditStatusRefreshOnly(work)
            } finally {
                f.coordinator.finishReport(work)
                work.job.cancel()
                f.close()
            }
        }

    @Test
    fun staleSessionCannotSendPatchOrApplyItsAckAndOldEvictionCannotRemoveTheNewLease() =
        runTest {
            val f = mobileEditReportFixture()
            val work = assertNotNull(f.works.begin(Job()))
            try {
                f.assertStaleEditSessionFenced(work)
            } finally {
                f.coordinator.finishReport(work)
                work.job.cancel()
                f.close()
            }
        }
}

private suspend fun ComplaintReportFixture.assertEditStatusRefreshOnly(work: ReportWork) {
    val edit = mobileEditRequest()
    val slot = mobileEditSlot(edit)
    storage.pending.slots += slot
    val binding = coordinator.beginReportAction(work, ReportStart.Retained(slot, edit)).success()
    val first = coordinator.readEditStatus(binding, readySession(binding), sessions, http).success()
    coordinator.authorizeReportRefresh(first, sessions).success()
    sessions.invalidateReportSession(first.session)
    val renewed = readySession(binding)
    assertRefused(Block.RECONCILIATION_REQUIRED, coordinator.dispatchEdit(binding, renewed, sessions, http))
    val second = coordinator.readEditStatus(binding, renewed, sessions, http).success()
    assertRefused(Block.RECONCILIATION_REQUIRED, coordinator.authorizeReportRefresh(second, sessions))
    assertOnlyEditStatusRequests()
    assertEquals(2, requests.size)
    assertTrue(slot.sameAs(storage.pending.slots.single()))
}

private suspend fun ComplaintReportFixture.assertStaleEditSessionFenced(work: ReportWork) {
    val binding = coordinator.beginReportAction(work, ReportStart.New(mobileEditRequest())).success()
    val original = readySession(binding)
    val prepared = coordinator.prepareReport(binding, original, sessions).success()
    sessions.invalidateReportSession(original)
    val current = readySession(prepared)
    sessions.invalidateReportSession(original)
    assertTrue(sessions.reportSessionIsCurrent(current))
    assertRefused(Block.STALE_BINDING, coordinator.dispatchEdit(prepared, original, sessions, http))
    assertTrue(requests.isEmpty())
    val outcome = coordinator.dispatchEdit(prepared, current, sessions, http).success()
    assertIs<ComplaintEditHttpResult.Applied>(outcome.result)
    sessions.invalidateReportSession(current)
    readySession(outcome.binding)
    assertRefused(Block.STALE_BINDING, coordinator.applyReportOutcome(outcome, sessions))
    assertNull(work.application())
    assertEquals(1, storage.pending.slots.size)
    assertEquals(1, requests.size)
}

private suspend fun TestScope.assertEditRefreshBound(secondUnauthorized: Boolean) {
    val f = editRefreshFixture(secondUnauthorized)
    try {
        val result = f.repository.submit(mobileEditRequest()).reportSuccess().attempt
        if (secondUnauthorized) {
            assertIs<ReportAttempt.Unresolved>(result)
        } else {
            assertIs<ReportAttempt.Completed>(result)
        }
        assertEquals(2, f.requests.size)
        assertEquals(2, f.sessionRequests.size)
        assertEquals(f.sentBodies.first(), f.sentBodies.last())
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

private fun TestScope.editRefreshFixture(secondUnauthorized: Boolean): ComplaintReportFixture {
    var calls = 0
    return ComplaintReportFixture(
        this,
        mutationHandler = {
            if (++calls == 1 || secondUnauthorized) {
                val status = HttpStatusCode.Unauthorized
                respond(mutationProblem(status, "UNAUTHORIZED"), status, mobileEditHeaders(status))
            } else {
                respond(mobileEditAck(), HttpStatusCode.OK, mobileEditHeaders())
            }
        },
    )
}
