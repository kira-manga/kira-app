package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Confirmation
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Permit
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class InstallationCredentialConcurrencyTest {
    @Test
    fun concurrentCandidatesCannotPassTheFirstCandidatesReadbackFence() =
        runTest {
            val fixture = InstallationCoordinatorFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.faults.onStep = { if (it == Step.CREATE_STORED) suspendAt(entered, release) }
            val candidate = Fixtures.record()
            val first = async { fixture.coordinator.admit(candidate) }
            entered.await()
            val other = Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID))
            val second = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.admit(other) }
            assertFalse(second.isCompleted)
            assertEquals(1, fixture.faults.trace.count { it == Step.CREATE_BEFORE })
            release.complete(Unit)
            assertTrue(
                first
                    .await()
                    .success()
                    .record
                    .sameAs(candidate),
            )
            assertTrue(
                second
                    .await()
                    .success()
                    .record
                    .sameAs(candidate),
            )
            assertEquals(1, fixture.faults.trace.count { it == Step.CREATE_STORED })
        }

    @Test
    fun applicationCallbackRetainsTheMutexThroughItsBoundedPublication() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val permit = fixture.coordinator.admit().success()
            var queued: Deferred<Outcome<Confirmation>>? = null
            var applications = 0
            fixture.coordinator
                .applyIfCurrent(permit) {
                    queued =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit))
                        }
                    assertFalse(requireNotNull(queued).isCompleted)
                    applications += 1
                }.success()
            val prompt = requireNotNull(queued).await().success()
            assertEquals(1, applications)
            assertRefused(Block.CONSENT_PENDING, fixture.coordinator.applyIfCurrent(permit) { applications += 1 })
            assertEquals(1, applications)
            fixture.coordinator.cancelRecovery(prompt).success()
        }

    @Test
    fun queuedOldApplicationCannotRunAcrossAResetThatHasPersistedIntent() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val permit = fixture.coordinator.admit().success()
            val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.faults.onStep = { if (it == Step.REPLACE_STORED) suspendAt(entered, release) }
            val reset = async { fixture.coordinator.confirmRecovery(prompt) }
            entered.await()
            var applications = 0
            val late =
                async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.coordinator.applyIfCurrent(permit) { applications += 1 }
                }
            assertFalse(late.isCompleted)
            release.complete(Unit)
            reset.await().success()
            assertRefused(Block.MISSING, late.await())
            assertEquals(0, applications)
            fixture.assertAbsent()
        }

    @Test
    fun cancellationBeforeAndAfterReplacementPropagatesAndReleasesTheSameMutex() =
        runTest {
            listOf(Step.REPLACE_BEFORE, Step.REPLACE_STORED).forEach { point ->
                val fixture = InstallationCoordinatorFixture(Fixtures.record())
                val permit = fixture.coordinator.admit().success()
                val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                fixture.faults.onStep = { if (it == point) suspendAt(entered, release) }
                var returned: Outcome<Unit>? = null
                val operation = launch { returned = fixture.coordinator.confirmRecovery(prompt) }
                entered.await()
                operation.cancelAndJoin()
                assertNull(returned)
                assertFalse(Step.PENDING_CLEAR_BEFORE in fixture.faults.trace)
                assertTrue(fixture.credentials.keyPresent)
                fixture.faults.clearFaults()
                if (point == Step.REPLACE_BEFORE) {
                    fixture.coordinator.admit().success()
                } else {
                    assertRefused(Block.CLEANUP_REQUIRED, fixture.coordinator.admit())
                    fixture.restart().resumeCleanup().success()
                    fixture.assertAbsent()
                }
            }
        }

    @Test
    fun cancellationAfterCreationReconcilesTheStoredWinnerWithoutCreatingAgain() =
        runTest {
            val fixture = InstallationCoordinatorFixture()
            val candidate = Fixtures.record()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.faults.onStep = { if (it == Step.CREATE_STORED) suspendAt(entered, release) }
            var returned: Outcome<Permit>? = null
            val operation = launch { returned = fixture.coordinator.admit(candidate) }
            entered.await()
            operation.cancelAndJoin()
            assertNull(returned)
            assertTrue(fixture.credentials.keyPresent)
            assertTrue(fixture.credentials.payloadPresent)
            fixture.faults.clearFaults()
            assertTrue(
                fixture.coordinator
                    .admit()
                    .success()
                    .record
                    .sameAs(candidate),
            )
            assertEquals(1, fixture.faults.trace.count { it == Step.CREATE_BEFORE })
        }

    @Test
    fun cancellationAtCleanupWritesPreservesEvidenceForRealCoordinatorRestart() =
        runTest {
            val points =
                listOf(
                    Step.PENDING_SLOT_REMOVED,
                    Step.MARKER_STORED,
                    Step.KEY_REMOVED,
                    Step.PAYLOAD_REMOVED,
                    Step.MARKER_REMOVED,
                )
            points.forEach { point ->
                val scenario = prepareCleanup(InstallationCleanupPath.RESET)
                val fixture = scenario.fixture
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                fixture.faults.onStep = { if (it == point) suspendAt(entered, release) }
                var returned: Outcome<Unit>? = null
                val operation = launch { returned = scenario.execute() }
                entered.await()
                operation.cancelAndJoin()
                assertNull(returned)
                fixture.faults.clearFaults()
                assertIs<Outcome.Refused>(fixture.coordinator.admit())
                fixture.restart().resumeCleanup().success()
                fixture.assertAbsent()
                assertFalse(Step.CREATE_BEFORE in fixture.faults.trace)
            }
        }

    @Test
    fun unknownApplicationExceptionsPropagateWithoutFinallyCleanupOrLockedAdmission() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val permit = fixture.coordinator.admit().success()
            assertFailsWith<IllegalStateException> {
                fixture.coordinator.applyIfCurrent(permit) { error("synthetic application failure") }
            }
            var applications = 0
            fixture.coordinator.applyIfCurrent(permit) { applications += 1 }.success()
            assertEquals(1, applications)
            assertTrue(fixture.faults.mutations.isEmpty())
            assertTrue(fixture.credentials.keyPresent)
        }

    private suspend fun suspendAt(
        entered: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ) {
        entered.complete(Unit)
        release.await()
    }
}
