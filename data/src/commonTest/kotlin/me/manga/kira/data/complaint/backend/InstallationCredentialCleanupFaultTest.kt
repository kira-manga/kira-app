package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class InstallationCredentialCleanupFaultTest {
    @Test
    fun resetFailuresBeforeMarkerResumeFromTheSamePendingGeneration() =
        runTest {
            listOf(
                Step.REPLACE_STORED,
                Step.PENDING_CLEAR_BEFORE,
                Step.PENDING_SLOT_REMOVED,
                Step.PENDING_CLEARED,
                Step.MARKER_CREATE_BEFORE,
            ).forEach { point ->
                val scenario = prepareCleanup(InstallationCleanupPath.RESET)
                val fixture = scenario.fixture
                fixture.faults.failAt(point)
                assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
                assertEquals(InstallationCredentialState.LOCAL_RESET_PENDING, fixture.credentials.payloadRecord?.state)
                assertEquals(2L, fixture.credentials.payloadRecord?.localGeneration)
                assertNull(fixture.credentials.marker)
                assertTrue(fixture.credentials.keyPresent)
                fixture.restart().resumeCleanup().success()
                fixture.assertAbsent()
                assertEquals(1, fixture.faults.trace.count { it == Step.REPLACE_BEFORE })
                assertFalse(Step.CREATE_BEFORE in fixture.faults.trace)
            }
        }

    @Test
    fun eachPartialPendingRemovalRetainsResetAuthorityAndTheRemainingSlots() =
        runTest {
            listOf(1, 2).forEach { removed ->
                val scenario = prepareCleanup(InstallationCleanupPath.RESET)
                val fixture = scenario.fixture
                val retained = fixture.pending.slots.last()
                fixture.faults.failAt(Step.PENDING_SLOT_REMOVED, occurrence = removed)
                assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
                assertEquals(2 - removed, fixture.pending.slots.size)
                if (removed == 1) {
                    assertTrue(
                        fixture.pending.slots
                            .single()
                            .sameAs(retained),
                    )
                }
                assertNull(fixture.credentials.marker)
                assertFalse(Step.CLEANUP_BEFORE in fixture.faults.trace)
                fixture.restart().resumeCleanup().success()
                fixture.assertAbsent()
            }
        }

    @Test
    fun everyMarkedPieceBoundaryResumesAcrossAllFourAuthorizations() =
        runTest {
            val points =
                listOf(
                    Step.MARKER_STORED,
                    Step.CLEANUP_BEFORE,
                    Step.KEY_REMOVED,
                    Step.PAYLOAD_REMOVED,
                    Step.MARKER_REMOVE_BEFORE,
                )
            InstallationCleanupPath.entries.forEach { path ->
                points.forEach { point ->
                    val scenario = prepareCleanup(path)
                    val fixture = scenario.fixture
                    fixture.faults.failAt(point)
                    assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
                    assertNotNull(fixture.credentials.marker)
                    assertFalse(Step.MARKER_REMOVED in fixture.faults.trace)
                    fixture.restart().resumeCleanup().success()
                    fixture.assertAbsent()
                    assertEquals(1, fixture.faults.trace.count { it == Step.MARKER_STORED })
                    assertFalse(Step.CREATE_BEFORE in fixture.faults.trace)
                }
            }
        }

    @Test
    fun failureAfterMarkerRemovalStillRequiresFreshVerifiedAbsenceNotRecreation() =
        runTest {
            InstallationCleanupPath.entries.forEach { path ->
                val scenario = prepareCleanup(path)
                val fixture = scenario.fixture
                fixture.faults.failAt(Step.MARKER_REMOVED)
                assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
                fixture.assertAbsent()
                fixture.restart().resumeCleanup().success()
                assertRefused(Block.MISSING, fixture.restart().admit())
                assertFalse(Step.CREATE_BEFORE in fixture.faults.trace)
            }
        }

    @Test
    fun markerReadbackFailureRetainsTheMarkerBeforeAnyCredentialCleanup() =
        runTest {
            InstallationCleanupPath.entries.forEach { path ->
                listOf(2, 3).forEach { occurrence ->
                    val scenario = prepareCleanup(path)
                    val fixture = scenario.fixture
                    fixture.faults.failAt(Step.MARKER_READ, occurrence = occurrence)
                    assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
                    assertNotNull(fixture.credentials.marker)
                    assertTrue(fixture.credentials.keyPresent)
                    assertFalse(Step.CLEANUP_BEFORE in fixture.faults.trace)
                    fixture.restart().resumeCleanup().success()
                    fixture.assertAbsent()
                }
            }
        }

    @Test
    fun unreadablePartialPendingCleanupResumesUnderTheAlreadyStoredNullMarker() =
        runTest {
            listOf(Step.PENDING_CLEAR_BEFORE, Step.PENDING_SLOT_REMOVED, Step.PENDING_CLEARED).forEach { point ->
                val scenario = prepareCleanup(InstallationCleanupPath.UNREADABLE)
                val fixture = scenario.fixture
                fixture.faults.failAt(point)
                assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
                assertNull(requireNotNull(fixture.credentials.marker).expectedGeneration)
                assertTrue(fixture.credentials.keyPresent)
                assertFalse(Step.CLEANUP_BEFORE in fixture.faults.trace)
                fixture.restart().resumeCleanup().success()
                fixture.assertAbsent()
                assertEquals(1, fixture.faults.trace.count { it == Step.MARKER_STORED })
            }
        }

    @Test
    fun pendingClearReadbackFailureCannotAdvanceToCredentialCleanup() =
        runTest {
            listOf(
                InstallationCleanupPath.RESET,
                InstallationCleanupPath.UNREADABLE,
                InstallationCleanupPath.ABANDON,
            ).forEach { path ->
                val scenario = prepareCleanup(path)
                val fixture = scenario.fixture
                fixture.faults.failAt(Step.PENDING_READ)
                assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
                assertTrue(fixture.pending.slots.isEmpty())
                assertTrue(fixture.credentials.keyPresent)
                assertFalse(Step.CLEANUP_BEFORE in fixture.faults.trace)
                if (path == InstallationCleanupPath.ABANDON) {
                    assertRefused(Block.REMOTE_DELETION_PENDING, fixture.restart().resumeCleanup())
                } else {
                    fixture.restart().resumeCleanup().success()
                    fixture.assertAbsent()
                }
            }
        }

    @Test
    fun failedFinalPendingAbsenceCheckRetainsTheCredentialCleanupMarker() =
        runTest {
            InstallationCleanupPath.entries.forEach { path ->
                val scenario = prepareCleanup(path)
                val fixture = scenario.fixture
                val occurrence = if (path == InstallationCleanupPath.UNREADABLE) 2 else 3
                fixture.faults.failAt(Step.PENDING_READ, occurrence = occurrence)
                assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
                assertFalse(fixture.credentials.keyPresent)
                assertFalse(fixture.credentials.payloadPresent)
                assertNotNull(fixture.credentials.marker)
                assertFalse(Step.MARKER_REMOVE_BEFORE in fixture.faults.trace)
                fixture.restart().resumeCleanup().success()
                fixture.assertAbsent()
            }
        }

    @Test
    fun failedCredentialAbsenceReadKeepsTheMarkerUntilRestartVerifiesIt() =
        runTest {
            InstallationCleanupPath.entries.forEach { path ->
                val scenario = prepareCleanup(path)
                val fixture = scenario.fixture
                val occurrence =
                    if (path == InstallationCleanupPath.RESET || path == InstallationCleanupPath.UNREADABLE) 3 else 2
                fixture.faults.failAt(Step.CREDENTIAL_READ, occurrence = occurrence)
                assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
                assertFalse(fixture.credentials.keyPresent)
                assertFalse(fixture.credentials.payloadPresent)
                assertNotNull(fixture.credentials.marker)
                assertFalse(Step.MARKER_REMOVE_BEFORE in fixture.faults.trace)
                fixture.restart().resumeCleanup().success()
                fixture.assertAbsent()
            }
        }

    @Test
    fun markerRemovalReadbackFailureDoesNotMintAnotherIdentity() =
        runTest {
            InstallationCleanupPath.entries.forEach { path ->
                val scenario = prepareCleanup(path)
                val fixture = scenario.fixture
                fixture.faults.failAt(Step.MARKER_READ, occurrence = 4)
                assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
                fixture.assertAbsent()
                fixture.restart().resumeCleanup().success()
                assertFalse(Step.CREATE_BEFORE in fixture.faults.trace)
            }
        }

    @Test
    fun finalCredentialAndPendingReadFailuresRequireReconciliationAfterMarkerRemoval() =
        runTest {
            InstallationCleanupPath.entries.forEach { path ->
                val credentialRead =
                    if (path == InstallationCleanupPath.RESET || path == InstallationCleanupPath.UNREADABLE) 4 else 3
                val pendingRead = if (path == InstallationCleanupPath.UNREADABLE) 3 else 4
                listOf(
                    Step.CREDENTIAL_READ to credentialRead,
                    Step.PENDING_READ to pendingRead,
                ).forEach { (point, occurrence) ->
                    val scenario = prepareCleanup(path)
                    val fixture = scenario.fixture
                    fixture.faults.failAt(point, occurrence = occurrence)
                    assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
                    fixture.assertAbsent()
                    fixture.restart().resumeCleanup().success()
                    assertRefused(Block.MISSING, fixture.restart().admit())
                    assertFalse(Step.CREATE_BEFORE in fixture.faults.trace)
                }
            }
        }

    @Test
    fun unreadableMarkerCreationFailureLeavesEveryPendingAndCredentialPiece() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.UNREADABLE)
            val fixture = scenario.fixture
            fixture.faults.failAt(Step.MARKER_CREATE_BEFORE)
            assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
            assertEquals(2, fixture.pending.slots.size)
            assertNull(fixture.credentials.marker)
            assertTrue(fixture.credentials.keyPresent)
            assertTrue(fixture.credentials.payloadPresent)
            val invalidated = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.INVALIDATED)
            assertStorageFailure(invalidated, fixture.restart().resumeCleanup())
            assertFalse(Step.PENDING_CLEAR_BEFORE in fixture.faults.trace)
            assertFalse(Step.CLEANUP_BEFORE in fixture.faults.trace)
        }

    @Test
    fun abandonmentBeforeMarkerCannotResumeAsUnwarnedAutomaticErasure() =
        runTest {
            listOf(
                Step.PENDING_CLEAR_BEFORE,
                Step.PENDING_SLOT_REMOVED,
                Step.PENDING_CLEARED,
                Step.MARKER_CREATE_BEFORE,
            ).forEach { point ->
                val scenario = prepareCleanup(InstallationCleanupPath.ABANDON)
                val fixture = scenario.fixture
                fixture.faults.failAt(point)
                assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
                assertNull(fixture.credentials.marker)
                val pendingCount = fixture.pending.slots.size
                val restarted = fixture.restart()
                assertRefused(Block.REMOTE_DELETION_PENDING, restarted.resumeCleanup())
                assertEquals(pendingCount, fixture.pending.slots.size)
                assertTrue(fixture.credentials.keyPresent)
                val deletion = restarted.pendingDeletion().success()
                val warning = restarted.requestRecovery(RecoveryIntent.Abandon(deletion)).success()
                restarted.confirmRecovery(warning).success()
                fixture.assertAbsent()
            }
        }

    @Test
    fun terminalBeforeMarkerFailureRetainsDeletionWithoutAutomaticCleanup() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.TERMINAL)
            val fixture = scenario.fixture
            fixture.faults.failAt(Step.MARKER_CREATE_BEFORE)
            assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
            assertRefused(Block.REMOTE_DELETION_PENDING, fixture.restart().resumeCleanup())
            assertTrue(fixture.credentials.keyPresent)
            assertNull(fixture.credentials.marker)
            assertFalse(Step.CLEANUP_BEFORE in fixture.faults.trace)
            // The exact terminal prerequisite must be supplied again; startup cannot invent it.
            scenario.execute().success()
            fixture.assertAbsent()
        }
}
