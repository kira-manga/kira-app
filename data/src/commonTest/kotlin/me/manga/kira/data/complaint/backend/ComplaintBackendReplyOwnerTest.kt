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
import me.manga.kira.domain.model.feedback.ComplaintReplyDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class ComplaintBackendReplyOwnerTest {
    @Test
    fun reportsRepliesAndRecoveryAreOneConsumerAndMissingDependenciesExposeNeitherCreationPort() =
        runTest {
            val fixture = MobileReplyOwnerFixture(this)
            try {
                assertSame(fixture.owner.reports, assertNotNull(fixture.owner.replies))
                assertSame(fixture.owner.reports, fixture.owner.installationRecovery)
                fixture.assertNoReplyPort(mutation = null, inputs = consumerInputs())
                fixture.assertNoReplyPort(mutation = fixture.mutation, inputs = null)
                val replies = assertNotNull(fixture.owner.replies)
                val live = replies.preparedConsumerReply()
                fixture.owner.close()
                assertIs<AppResult.Failure>(replies.submit(live))
                assertIs<AppResult.Failure>(replies.prepare(ComplaintReplyDraft(MOBILE_REPLY_PARENT, "x")))
                assertTrue(fixture.storage.faults.mutations.isEmpty())
                assertTrue(fixture.engines.all { it.coroutineContext.job.isActive })
            } finally {
                fixture.close()
            }
        }

    @Test
    fun ownerCloseCancelsAdmittedReplyButCannotDeleteMayEvidenceOrCloseBorrowedEngines() =
        runTest {
            val fixture = MobileReplyOwnerFixture(this)
            val replies = assertNotNull(fixture.owner.replies)
            val reports = assertNotNull(fixture.owner.reports)
            try {
                val live = replies.preparedConsumerReply()
                val report = reports.preparedConsumerReport()
                val first = async { replies.submit(live) }
                fixture.entered.await()
                assertIs<AppResult.Failure>(reports.submit(report))
                assertTrue(first.isActive)
                fixture.owner.close()
                fixture.release.complete(Unit)
                assertFailsWith<CancellationException> { first.await() }
                val retained = reportRecord(fixture.storage.pending.slots.single())
                assertEquals(PendingComplaintOperation.CREATE_REPLY, retained.request.action.operation)
                assertEquals(PendingComplaintState.MAY_HAVE_DISPATCHED, retained.state)
                assertTrue(Step.PENDING_DELETE_BEFORE !in fixture.storage.faults.trace)
                assertTrue(fixture.engines.all { it.coroutineContext.job.isActive })
                assertIs<AppResult.Failure>(reports.reconcile())
            } finally {
                fixture.close()
            }
        }
}

/** One real owner/coordinator; the four synthetic engines are still borrowed, never data-owned. */
private class MobileReplyOwnerFixture(
    scope: TestScope,
) {
    val storage = InstallationCoordinatorFixture(Fixtures.record())
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    private val endpoint = assertNotNull(ComplaintBackendEndpoint.checked(SESSION_BASE_URL))
    private val generator = EnrollmentMaterialGenerator()
    private val enrollment = historyMockEngine(scope) { error("reply must not enroll") }
    private val session = historyMockEngine(scope) { respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders()) }
    private val history = historyMockEngine(scope) { error("reply must not fetch parent content") }
    val mutation =
        historyMockEngine(scope) {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                respond(mutationAck(), HttpStatusCode.Created, mutationHeaders(HttpStatusCode.Created))
            }
        }
    val engines: List<HttpClientEngine> = listOf(enrollment, session, history, mutation)
    val owner = createOwner(mutation, consumerInputs()).reportSuccess()

    fun assertNoReplyPort(
        mutation: HttpClientEngine?,
        inputs: ComplaintReportInputs?,
    ) {
        val disabled = createOwner(mutation, inputs).reportSuccess()
        try {
            assertNull(disabled.reports)
            assertNull(disabled.replies)
            assertNull(disabled.installationRecovery)
        } finally {
            disabled.close()
        }
    }

    private fun createOwner(
        mutation: HttpClientEngine?,
        inputs: ComplaintReportInputs?,
    ): AppResult<ComplaintBackendOwner> =
        ComplaintBackendOwner.create(
            endpoint,
            storage.credentials,
            storage.pending,
            generator,
            enrollment,
            session,
            history,
            mutation,
            inputs,
        )

    fun close() {
        release.complete(Unit)
        owner.close()
        engines.forEach { it.close() }
    }
}
