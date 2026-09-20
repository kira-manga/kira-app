package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class ComplaintDetailLifecycleTest {
    @Test
    fun lateContentAndUnavailableCannotOutliveConsentResetDeletionOrClosedReader() =
        runTest {
            for (unavailable in listOf(false, true)) {
                for (change in listOf("consent", "reset", "deletion", "close")) {
                    assertLateDetailFenced(change, unavailable, cancelled = true)
                }
            }
        }

    @Test
    fun lateContentAndUnavailableCannotOutliveIdentityVersionGenerationScopeOrPendingChange() =
        runTest {
            for (unavailable in listOf(false, true)) {
                for (change in listOf("identity", "version", "generation", "scope", "pending")) {
                    assertLateDetailFenced(change, unavailable, cancelled = false)
                }
            }
        }

    @Test
    fun finalPublicationAfterBodyCleanupStillFencesBothContentAndUnavailableOnClose() =
        runTest {
            for (unavailable in listOf(false, true)) assertFinalPublicationFenced(unavailable)
        }

    @Test
    fun capturedSessionDoesNotAuthorizeDispatchAfterConsentOrDeletion() =
        runTest {
            for (change in listOf("none", "consent", "deletion")) assertDispatchFenced(change)
        }

    private suspend fun TestScope.assertLateDetailFenced(
        change: String,
        unavailable: Boolean,
        cancelled: Boolean,
    ) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val status = if (unavailable) HttpStatusCode.NotFound else HttpStatusCode.OK
        val fixture =
            ComplaintHistoryFixture(this, historyHandler = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                val text = if (unavailable) detailNotFoundProblem() else historyItem().toString()
                respond(text, status, if (unavailable) sessionHeaders(status) else detailHeaders())
            })
        try {
            val load = async { fixture.repository.loadComplaintDetail(historyId(100)) }
            entered.await()
            changeDetailBinding(fixture, change)
            // Reaching this before releasing HTTP also proves the coordinator did not hold its mutex over I/O.
            val mutations = fixture.storage.faults.mutations.toList()
            release.complete(Unit)
            if (cancelled) {
                assertFailsWith<CancellationException> { load.await() }
            } else {
                assertIs<AppResult.Failure>(load.await())
            }
            load.join()
            assertEquals(mutations, fixture.storage.faults.mutations)
            assertEquals(1, fixture.historyRequests.size)
            assertTrue(fixture.enrollment.requests.isEmpty())
            assertTrue(fixture.enrollment.generator.scopes.isEmpty())
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    private suspend fun TestScope.assertFinalPublicationFenced(unavailable: Boolean) {
        var checksAfterBody = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val status = if (unavailable) HttpStatusCode.NotFound else HttpStatusCode.OK
        val text = if (unavailable) detailNotFoundProblem() else historyItem().toString()
        val body = HistoryTrackedChannel(ByteReadChannel(text))
        val fixture =
            ComplaintHistoryFixture(this, historyHandler = {
                respond(body, status, if (unavailable) sessionHeaders(status) else detailHeaders())
            })
        fixture.storage.faults.onStep = { step ->
            // Post-response read is first; final publication is the second durable check after cleanup.
            if (step == InstallationStoreStep.PENDING_READ && body.cancelled && ++checksAfterBody == 2) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
        }
        try {
            val load = async { fixture.repository.loadComplaintDetail(historyId(100)) }
            entered.await()
            fixture.loads.close()
            fixture.sessions.close()
            fixture.http.close()
            release.complete(Unit)
            assertFailsWith<CancellationException> { load.await() }
            assertTrue(body.cancelled)
            assertIs<AppResult.Failure>(fixture.repository.loadComplaintDetail(historyId(100)))
            assertEquals(1, fixture.historyRequests.size)
            fixture.assertPreserved()
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }

    private suspend fun TestScope.assertDispatchFenced(change: String) {
        val registered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture =
            ComplaintHistoryFixture(this, historyHandler = {
                respond(historyItem().toString(), HttpStatusCode.OK, detailHeaders())
            })
        try {
            val session = assertIs<ComplaintHistorySessionResult.Ready>(fixture.sessions.historySession()).session
            val load =
                async {
                    fixture.loads.run { work ->
                        fixture.storage.coordinator.beginHistory(work).success()
                        try {
                            registered.complete(Unit)
                            release.await()
                            fixture.storage.coordinator.readComplaintDetail(
                                session,
                                fixture.sessions,
                                work,
                                fixture.http,
                                detailRequest(),
                            )
                        } finally {
                            fixture.storage.coordinator.finishHistory(work)
                        }
                    }
                }
            registered.await()
            if (change != "none") changeDetailBinding(fixture, change)
            release.complete(Unit)
            if (change == "none") {
                assertIs<AppResult.Success<*>>(load.await())
                assertEquals(1, fixture.historyRequests.size)
            } else {
                assertFailsWith<CancellationException> { load.await() }
                assertTrue(fixture.historyRequests.isEmpty())
            }
            load.join()
        } finally {
            release.complete(Unit)
            fixture.close()
        }
    }
}

private suspend fun changeDetailBinding(
    fixture: ComplaintHistoryFixture,
    change: String,
) {
    when (change) {
        "identity" -> fixture.storage.credentials.install(Fixtures.record(Fixtures.material(id = Fixtures.OTHER_ID)))
        "version" -> fixture.storage.credentials.install(Fixtures.record(version = 2))
        "generation" -> fixture.storage.credentials.install(Fixtures.record(generation = 2))
        "scope" -> fixture.storage.credentials.install(Fixtures.record(Fixtures.material(scope = Fixtures.OTHER_ID)))
        "pending" -> fixture.storage.pending.slots += reportSlot(mobileReplyRequest())
        "close" -> fixture.loads.close()
        else -> changeDetailLifecycle(fixture, change)
    }
}

private suspend fun changeDetailLifecycle(
    fixture: ComplaintHistoryFixture,
    change: String,
) {
    val permit = fixture.storage.coordinator.admit().success()
    if (change == "deletion") {
        fixture.storage.coordinator.beginDeletion(permit, Fixtures.KEY).success()
    } else {
        val prompt = fixture.storage.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
        if (change == "reset") {
            fixture.storage.coordinator.confirmRecovery(prompt).success()
        } else {
            fixture.storage.coordinator.cancelRecovery(prompt).success()
        }
    }
}
