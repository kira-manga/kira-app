package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class ComplaintBackendReportOwnerTest {
    @Test
    fun reportLaneNeverReplacesAdmittedWorkAndOwnerCloseFencesLateResponseWithoutOwningEngines() =
        runTest {
            val fixture = ReportOwnerFixture(this)
            fixture.assertUnselectedAndAliasedFactories()
            val feedback = assertNotNull(fixture.owner.feedback)
            try {
                val first = async { feedback.submit(mutationReport()) }
                fixture.entered.await()
                assertIs<AppResult.Failure>(feedback.submit(mutationReport(key = historyId(30))))
                assertTrue(first.isActive)
                val history = fixture.owner.history.loadUserComplaints()
                assertIs<ComplaintHistory.Backend>(history.reportSuccess())
                fixture.owner.close()
                fixture.release.complete(Unit)
                assertFailsWith<CancellationException> { first.await() }
                val pending = fixture.storage.pending
                val retained = reportRecord(pending.slots.single())
                assertEquals(PendingComplaintState.MAY_HAVE_DISPATCHED, retained.state)
                assertTrue(Step.PENDING_DELETE_BEFORE !in fixture.storage.faults.trace)
                assertTrue(fixture.engines.all { it.coroutineContext.job.isActive })
                assertIs<AppResult.Failure>(feedback.reconcile())
            } finally {
                fixture.close()
            }
        }
}

/** Wiring only: the real owner owns both work lanes and all four borrowing clients. */
private class ReportOwnerFixture(
    scope: TestScope,
) {
    val storage = InstallationCoordinatorFixture(Fixtures.record())
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val endpoint = assertNotNull(ComplaintBackendEndpoint.checked(SESSION_BASE_URL))
    private val generator = EnrollmentMaterialGenerator()
    private val enrollment = historyMockEngine(scope) { error("report must not enroll") }
    private val session = historyMockEngine(scope) { respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders()) }
    private val history = historyMockEngine(scope) { respond(historyResponse(), HttpStatusCode.OK, sessionHeaders()) }
    private val mutation =
        historyMockEngine(scope) {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                respond(mutationAck(), HttpStatusCode.Created, mutationHeaders(HttpStatusCode.Created))
            }
        }
    val engines: List<HttpClientEngine> = listOf(enrollment, session, history, mutation)
    val owner = createOwner(mutation).reportSuccess()

    fun assertUnselectedAndAliasedFactories() {
        val unselected = createOwner(null).reportSuccess()
        assertNull(unselected.feedback)
        unselected.close()
        assertIs<AppResult.Failure>(createOwner(history))
        assertTrue(engines.all { it.coroutineContext.job.isActive })
    }

    private fun createOwner(mutationEngine: HttpClientEngine?): AppResult<ComplaintBackendOwner> =
        ComplaintBackendOwner.create(
            endpoint,
            storage.credentials,
            storage.pending,
            generator,
            enrollment,
            session,
            history,
            mutationEngine,
        )

    fun close() {
        release.complete(Unit)
        owner.close()
        engines.forEach { it.close() }
    }
}
