package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationTemporaryFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import me.manga.kira.data.complaint.backend.ComplaintSessionFailure as Failure
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class InstallationSessionManagerTest {
    @Test
    fun singleflightSharesSuccessAndNetworkElapsedConsumesTheFixedTtl() =
        runTest {
            val clock = TestTimeSource()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val fixture =
                ComplaintSessionFixture(this, clock = clock) {
                    calls++
                    clock += 20.seconds
                    entered.complete(Unit)
                    release.await()
                    respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
                }
            try {
                val callers = List(12) { async { fixture.manager.session() } }
                entered.await()
                runCurrent()
                assertEquals(1, calls)
                release.complete(Unit)
                val responses = callers.awaitAll().map { assertIs<ComplaintSessionResult.Ready>(it).session }
                responses.forEach { assertSame(responses.first(), it) }
                clock += 879.seconds
                assertSame(responses.first(), assertIs<ComplaintSessionResult.Ready>(fixture.manager.session()).session)
                clock += 1.seconds
                assertNotSame(
                    responses.first(),
                    assertIs<ComplaintSessionResult.Ready>(fixture.manager.session()).session,
                )
                assertEquals(2, calls)
                fixture.assertPreserved()
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }

    @Test
    fun responseExpiredDuringRequestAndNegativeElapsedNeverReuseAToken() =
        runTest {
            val clock = TestTimeSource()
            var calls = 0
            val fixture =
                ComplaintSessionFixture(this, clock = clock) {
                    calls++
                    if (calls == 1) clock += 900.seconds
                    respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
                }
            try {
                assertSessionFailure(Failure.EXPIRED, fixture.manager.session())
                val next = assertIs<ComplaintSessionResult.Ready>(fixture.manager.session()).session
                clock += (-1).seconds
                assertNotSame(next, assertIs<ComplaintSessionResult.Ready>(fixture.manager.session()).session)
                assertEquals(3, calls)
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun missingCorruptForeignAndUncertainLocalEvidenceProducesZeroHttp() =
        runTest {
            val changes: List<(InstallationCoordinatorFixture) -> Unit> =
                listOf(
                    { it.credentials.removePieces() },
                    { it.pending.slots += Fixtures.slot(1) },
                    { it.pending.slots += sessionPendingSlot(Fixtures.record(generation = 2)) },
                    {
                        it.pending.readFailure =
                            InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.UNCERTAIN)
                    },
                )
            for (change in changes) {
                val fixture = ComplaintSessionFixture(this)
                change(fixture.storage)
                val record = fixture.storage.credentials.payloadRecord
                val slots =
                    fixture.storage.pending.slots
                        .toList()
                try {
                    assertIs<ComplaintSessionResult.LocalFailure>(fixture.manager.session())
                    assertTrue(fixture.engine.requestHistory.isEmpty())
                    if (record == null) fixture.storage.assertAbsent() else fixture.assertPreserved(record, slots)
                    assertTrue(
                        fixture.storage.faults.mutations
                            .isEmpty(),
                    )
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun lateConsentCancelResetDeletionAndPendingChangesCannotPublishOrMutate() =
        runTest {
            for (change in sessionLateChanges()) {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val fixture =
                    ComplaintSessionFixture(this) {
                        entered.complete(Unit)
                        release.await()
                        respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
                    }
                try {
                    val request = async { fixture.manager.session() }
                    entered.await()
                    // This completes while HTTP is suspended: the coordinator mutex is not held across I/O.
                    change(fixture.storage)
                    val mutations =
                        fixture.storage.faults.mutations
                            .toList()
                    val record = fixture.storage.credentials.payloadRecord
                    val slots =
                        fixture.storage.pending.slots
                            .toList()
                    release.complete(Unit)
                    assertIs<ComplaintSessionResult.LocalFailure>(request.await())
                    assertEquals(mutations, fixture.storage.faults.mutations)
                    assertSame(record, fixture.storage.credentials.payloadRecord)
                    assertEquals(slots, fixture.storage.pending.slots)
                } finally {
                    release.complete(Unit)
                    fixture.close()
                }
            }
        }

    @Test
    fun externalCancellationIdentityReleasesRefreshWithoutWritesOrCachedSuccess() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var observed: CancellationException? = null
            var calls = 0
            val fixture =
                ComplaintSessionFixture(this) {
                    calls++
                    entered.complete(Unit)
                    release.await()
                    respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
                }
            try {
                val request =
                    async {
                        try {
                            fixture.manager.session()
                        } catch (cancelled: CancellationException) {
                            observed = cancelled
                            throw cancelled
                        }
                    }
                entered.await()
                val cancellation = CancellationException("synthetic caller cancellation")
                request.cancel(cancellation)
                assertFailsWith<CancellationException> { request.await() }
                request.join()
                assertCancellationIdentity(cancellation, observed)
                release.complete(Unit)
                assertIs<ComplaintSessionResult.Ready>(fixture.manager.session())
                assertEquals(2, calls)
                fixture.assertPreserved()
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }

    @Test
    fun closeDropsCacheRefusesReuseAndLeavesBorrowedEngineAlive() =
        runTest {
            val fixture = ComplaintSessionFixture(this)
            val endpoint = assertNotNull(ComplaintBackendEndpoint.checked(SESSION_BASE_URL))
            try {
                assertIs<ComplaintSessionResult.Ready>(fixture.manager.session())
                fixture.manager.close()
                assertSessionFailure(Failure.CLOSED, fixture.manager.session())
                assertTrue(assertNotNull(fixture.engine.coroutineContext[Job]).isActive)
                val other =
                    InstallationSessionManager(fixture.storage.coordinator, endpoint, fixture.engine, fixture.clock)
                try {
                    assertIs<ComplaintSessionResult.Ready>(other.session())
                } finally {
                    other.close()
                }
                val http = ComplaintSessionHttp(endpoint, fixture.engine)
                http.close()
                assertSessionFailure(Failure.CLOSED, http.fetch(Fixtures.record()))
                assertTrue(assertNotNull(fixture.engine.coroutineContext[Job]).isActive)
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun closeDuringFinalDurableRecheckPreventsLateCachePublication() =
        runTest {
            val fixture = ComplaintSessionFixture(this)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var reads = 0
            fixture.storage.faults.onStep = { step ->
                if (step == InstallationStoreStep.PENDING_READ && ++reads == 2) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                val request = async { fixture.manager.session() }
                entered.await()
                fixture.manager.close()
                release.complete(Unit)
                assertSessionFailure(Failure.CLOSED, request.await())
                assertSessionFailure(Failure.CLOSED, fixture.manager.session())
                fixture.assertPreserved()
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }

    @Test
    fun ownedTotalTimeoutIsTypedAndNeverRetriesOrClearsEvidence() =
        runTest {
            var calls = 0
            val fixture =
                ComplaintSessionFixture(this) {
                    calls++
                    awaitCancellation()
                }
            try {
                assertSessionFailure(Failure.TIMEOUT, fixture.manager.session())
                assertEquals(1, calls)
                fixture.assertPreserved()
            } finally {
                fixture.close()
            }
        }
}

private fun sessionLateChanges(): List<suspend (InstallationCoordinatorFixture) -> Unit> =
    listOf(
        {
            val permit = it.coordinator.admit().success()
            val confirmation = it.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
            it.coordinator.cancelRecovery(confirmation).success()
        },
        {
            val permit = it.coordinator.admit().success()
            val confirmation = it.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
            it.coordinator.confirmRecovery(confirmation).success()
        },
        {
            val permit = it.coordinator.admit().success()
            it.coordinator.beginDeletion(permit, Fixtures.KEY).success()
        },
        { it.pending.slots += sessionPendingSlot() },
    )
