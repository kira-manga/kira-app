package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class InstallationDeletionNormalWorkTest {
    @Test
    fun busyNormalStatusDoesNotBlockDeleteStartAndItsLateSuccessCannotApplyAfterGenerationChange() =
        runTest {
            val fixture = DeletionNormalWorkFixture(this)
            try {
                val normal = async { fixture.readStatus() }
                fixture.entered.await()
                assertDeletionPending(fixture.deletion.repository.startDeletion())
                assertFalse(fixture.work.isCurrent())
                fixture.release.complete(Unit)
                assertIs<Outcome.Refused>(normal.await())
                fixture.deletion.assertRetained(deletingRecord(), listOf(fixture.slot))
                assertEquals(2, fixture.deletion.sessionRequests.size)
                assertTrue(Step.PENDING_DELETE_BEFORE !in fixture.deletion.storage.faults.trace)
            } finally {
                fixture.close()
            }
        }
}

private class DeletionNormalWorkFixture(scope: TestScope) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val deletion = InstallationDeletionFixture(scope, deletionHandler = {
        val status = HttpStatusCode.ServiceUnavailable
        respond(mutationProblem(status, "SERVICE_UNAVAILABLE"), status, deletionHeaders(status))
    })
    private val engine = historyMockEngine(scope) {
        entered.complete(Unit)
        release.await()
        respond(mutationApplied(), HttpStatusCode.OK, mutationHeaders())
    }
    private val http = ComplaintMutationHttp(assertNotNull(ComplaintBackendEndpoint.checked(SESSION_BASE_URL)), engine)
    val slot = reportSlot().also { deletion.storage.pending.slots += it }
    private val job = Job()
    val work = assertNotNull(ReportWorkOwner().begin(job))

    suspend fun readStatus(): Outcome<ReportExchange.Status> {
        val binding = deletion.coordinator.beginReportAction(work, ReportStart.Retained(slot)).success()
        val session = assertIs<ReportSessionResult.Ready>(deletion.sessions.reportSession(binding)).session
        return deletion.coordinator.readReportStatus(binding, session, deletion.sessions, http)
    }

    suspend fun close() {
        release.complete(Unit)
        deletion.coordinator.finishReport(work)
        job.cancel()
        http.close()
        engine.close()
        deletion.close()
    }
}
