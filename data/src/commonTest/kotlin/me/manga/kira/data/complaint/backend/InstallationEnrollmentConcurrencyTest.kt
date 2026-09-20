package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.ComplaintSessionFailure as Failure
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

@OptIn(ExperimentalCoroutinesApi::class)
class InstallationEnrollmentConcurrencyTest {
    @Test
    fun enrollmentOwnsTheExistingMutexAndQueuedRecoveryBlocksAnotherAttempt() =
        runTest {
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture =
                InstallationEnrollmentFixture(this, enrollmentStorage()) {
                    reached.complete(Unit)
                    release.await()
                    respond(sessionResponse(), HttpStatusCode.Created, enrollmentHeaders())
                }
            val permit =
                fixture.storage.coordinator
                    .admit()
                    .success()
            try {
                val first = async { fixture.enroll() }
                reached.await()
                val recovery =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.storage.coordinator.requestRecovery(RecoveryIntent.Reset(permit))
                    }
                val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.enroll() }
                assertFalse(recovery.isCompleted)
                assertFalse(second.isCompleted)
                assertEquals(1, fixture.requests.size)
                release.complete(Unit)
                assertIs<InstallationEnrollmentResult.Ready<Unit>>(first.await())
                val confirmation = recovery.await().success()
                assertRefused(
                    Block.CONSENT_PENDING,
                    assertIs<InstallationEnrollmentResult.LocalFailure>(second.await()).outcome,
                )
                assertEquals(1, fixture.requests.size)
                fixture.storage.coordinator
                    .cancelRecovery(confirmation)
                    .success()
                assertTrue(
                    fixture.storage.faults.mutations
                        .isEmpty(),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun cancellationAtDurableCreationOrHttpRetainsExactIdentityForRestart() =
        runTest {
            for (atCreation in listOf(true, false)) {
                val reached = CompletableDeferred<Unit>()
                val fixture =
                    InstallationEnrollmentFixture(this) { request ->
                        if (request.method == HttpMethod.Get) {
                            respond(bootstrapResponse(), HttpStatusCode.OK, sessionHeaders())
                        } else {
                            reached.complete(Unit)
                            awaitCancellation()
                        }
                    }
                fixture.storage.faults.onStep = {
                    if (atCreation && it == Step.CREATE_STORED) {
                        reached.complete(Unit)
                        awaitCancellation()
                    }
                }
                var result: InstallationEnrollmentResult<Unit>? = null
                try {
                    val operation = launch { result = fixture.enroll() }
                    reached.await()
                    operation.cancelAndJoin()
                    assertNull(result)
                    assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(Fixtures.record()))
                    assertFalse(Step.CLEANUP_BEFORE in fixture.storage.faults.trace)
                    fixture.storage.faults.clearFaults()
                    val restarted = InstallationEnrollmentFixture(this, fixture.storage)
                    try {
                        assertIs<InstallationEnrollmentResult.Ready<Unit>>(restarted.enroll(fixture.storage.restart()))
                        assertTrue(restarted.generator.scopes.isEmpty())
                        assertEquals(listOf(HttpMethod.Post), restarted.requests.map { it.method })
                        assertEquals(
                            1,
                            fixture.storage.faults.trace
                                .count { it == Step.CREATE_BEFORE },
                        )
                    } finally {
                        restarted.close()
                    }
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun thirtySecondsIsOneAttemptBudgetNotOneBudgetPerRoute() =
        runTest {
            val fixture =
                InstallationEnrollmentFixture(this) { request ->
                    delay(PHASE_DELAY_MS)
                    if (request.method == HttpMethod.Get) {
                        respond(bootstrapResponse(), HttpStatusCode.OK, sessionHeaders())
                    } else {
                        respond(sessionResponse(), HttpStatusCode.Created, enrollmentHeaders())
                    }
                }
            try {
                assertEquals(Failure.TIMEOUT, assertIs<InstallationEnrollmentResult.Failed>(fixture.enroll()).reason)
                assertEquals(InstallationEnrollmentHttp.ATTEMPT_TIMEOUT_MS, testScheduler.currentTime)
                assertEquals(listOf(HttpMethod.Get, HttpMethod.Post), fixture.requests.map { it.method })
                assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(Fixtures.record()))
                assertFalse(Step.CLEANUP_BEFORE in fixture.storage.faults.trace)
                fixture.storage.coordinator
                    .admit()
                    .success()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun closingDuringFinalDurableRecheckRejectsPublicationWithoutOwningTheEngine() =
        runTest {
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var responseOffered = false
            val fixture =
                InstallationEnrollmentFixture(this, enrollmentStorage()) {
                    responseOffered = true
                    respond(sessionResponse(), HttpStatusCode.Created, enrollmentHeaders())
                }
            fixture.storage.faults.onStep = {
                if (responseOffered && it == Step.CREDENTIAL_READ) {
                    reached.complete(Unit)
                    release.await()
                }
            }
            try {
                val operation = async { fixture.enroll() }
                reached.await()
                fixture.http.close()
                release.complete(Unit)
                assertEquals(Failure.CLOSED, assertIs<InstallationEnrollmentResult.Failed>(operation.await()).reason)
                assertTrue(assertNotNull(fixture.engine.coroutineContext[Job]).isActive)
                fixture.storage.faults.clearFaults()
                fixture.storage.coordinator
                    .admit()
                    .success()
                assertEquals(Failure.CLOSED, assertIs<InstallationEnrollmentResult.Failed>(fixture.enroll()).reason)
                assertEquals(1, fixture.requests.size)
                assertTrue(
                    fixture.storage.faults.mutations
                        .isEmpty(),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun unknownStorageExceptionAfterPersistencePropagatesWithoutFinallyCleanup() =
        runTest {
            val fixture = InstallationEnrollmentFixture(this)
            fixture.storage.faults.onStep = { if (it == Step.CREATE_STORED) error("synthetic storage failure") }
            try {
                assertFailsWith<IllegalStateException> { fixture.enroll() }
                assertEquals(listOf(HttpMethod.Get), fixture.requests.map { it.method })
                assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(Fixtures.record()))
                fixture.storage.faults.clearFaults()
                assertIs<InstallationEnrollmentResult.Ready<Unit>>(fixture.enroll(fixture.storage.restart()))
                assertEquals(
                    1,
                    fixture.storage.faults.trace
                        .count { it == Step.CREATE_BEFORE },
                )
                assertFalse(Step.CLEANUP_BEFORE in fixture.storage.faults.trace)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun externalBootstrapCancellationIsNotTranslatedIntoTimeoutOrNewIdentity() =
        runTest {
            val cancellation = CancellationException("synthetic external cancellation")
            val fixture = InstallationEnrollmentFixture(this) { throw cancellation }
            try {
                val actual = assertFailsWith<CancellationException> { fixture.enroll() }
                assertCancellationIdentity(cancellation, actual)
                fixture.storage.assertAbsent()
                assertTrue(fixture.generator.scopes.isEmpty())
                assertTrue(
                    fixture.storage.faults.mutations
                        .isEmpty(),
                )
                assertRefused(Block.MISSING, fixture.storage.coordinator.admit())
            } finally {
                fixture.close()
            }
        }

    private companion object {
        const val PHASE_DELAY_MS = 20_000L
    }
}
