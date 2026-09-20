package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.platform.storage.CredentialCleanupReason
import me.manga.kira.platform.storage.CredentialDeleteResult
import me.manga.kira.platform.storage.CredentialReadResult
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationTemporaryFailure
import me.manga.kira.platform.storage.PendingClearResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class InstallationCredentialMarkerRecoveryTest {
    @Test
    fun positiveMarkerResumesAfterKeyDeletionMakesPayloadUnreadable() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.RESET)
            val fixture = scenario.fixture
            fixture.faults.failAt(Step.KEY_REMOVED)
            assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
            val marker = requireNotNull(fixture.credentials.marker)
            assertEquals(2L, marker.expectedGeneration)
            assertFalse(fixture.credentials.keyPresent)
            assertTrue(fixture.credentials.payloadPresent)
            assertEquals(
                InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.INVALIDATED),
                fixture.credentials.read(),
            )
            fixture.restart().resumeCleanup().success()
            fixture.assertAbsent()
            assertEquals(1, fixture.faults.trace.count { it == Step.KEY_REMOVED })
            assertEquals(1, fixture.faults.trace.count { it == Step.PAYLOAD_REMOVED })
        }

    @Test
    fun credentialAbsentWithMarkerRetainedResumesWithoutNewCredentials() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.TERMINAL)
            val fixture = scenario.fixture
            fixture.faults.failAt(Step.PAYLOAD_REMOVED)
            assertStorageFailure(InstallationStoreFaults.ioFailure, scenario.execute())
            assertEquals(CredentialReadResult.Missing, fixture.credentials.read())
            assertNotNull(fixture.credentials.marker)
            assertRefused(Block.CLEANUP_REQUIRED, fixture.restart().admit(Fixtures.record()))
            fixture.restart().resumeCleanup().success()
            fixture.assertAbsent()
            assertFalse(Step.CREATE_BEFORE in fixture.faults.trace)
        }

    @Test
    fun exactExistingMarkerContinuesButIsNeverOverwritten() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.RESET)
            val fixture = scenario.fixture
            val existing = Fixtures.marker(2, CredentialCleanupReason.USER_RESET_CONFIRMED)
            fixture.faults.onStep = { if (it == Step.MARKER_CREATE_BEFORE) fixture.credentials.marker = existing }
            scenario.execute().success()
            fixture.assertAbsent()
            assertFalse(Step.MARKER_STORED in fixture.faults.trace)
            assertEquals(1, fixture.faults.trace.count { it == Step.MARKER_CREATE_BEFORE })
        }

    @Test
    fun differentExistingMarkerIsPreservedWithoutDeletingCredentialPieces() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.RESET)
            val fixture = scenario.fixture
            val conflict = Fixtures.marker(3, CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED)
            fixture.faults.onStep = { if (it == Step.MARKER_CREATE_BEFORE) fixture.credentials.marker = conflict }
            val failure = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.MARKER_CONFLICT)
            assertStorageFailure(failure, scenario.execute())
            assertTrue(requireNotNull(fixture.credentials.marker).sameAs(conflict))
            assertTrue(fixture.credentials.keyPresent)
            assertFalse(Step.CLEANUP_BEFORE in fixture.faults.trace)
        }

    @Test
    fun corruptMarkerIsNotMissingAndNeverOverwrittenOrRemoved() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val corrupt = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.CORRUPT)
            fixture.credentials.markerFailure = corrupt
            assertStorageFailure(corrupt, fixture.coordinator.resumeCleanup())
            assertStorageFailure(corrupt, fixture.coordinator.admit(Fixtures.record()))
            assertTrue(fixture.faults.mutations.isEmpty())
            assertTrue(fixture.credentials.keyPresent)
        }

    @Test
    fun positiveMarkerRefusesReadableGenerationOrReasonMismatch() =
        runTest {
            val records =
                listOf(
                    Fixtures.record(generation = 4).beginLocalReset().valid(),
                    Fixtures.record(generation = 2),
                )
            records.forEach { record ->
                val fixture = InstallationCoordinatorFixture(record)
                val marker = Fixtures.marker(2, CredentialCleanupReason.USER_RESET_CONFIRMED)
                fixture.credentials.marker = marker
                assertRefused(Block.STALE_BINDING, fixture.coordinator.resumeCleanup())
                assertTrue(requireNotNull(fixture.credentials.payloadRecord).sameAs(record))
                assertTrue(requireNotNull(fixture.credentials.marker).sameAs(marker))
                assertFalse(Step.KEY_REMOVED in fixture.faults.trace)
                assertFalse(Step.MARKER_REMOVE_BEFORE in fixture.faults.trace)
            }
        }

    @Test
    fun nullMarkerCannotClearPendingForANewlyReadableCredential() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val marker = Fixtures.marker(null, CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED)
            fixture.credentials.marker = marker
            val slot = Fixtures.slot(1)
            fixture.pending.slots += slot
            val failure = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.STATE_CHANGED)
            assertStorageFailure(failure, fixture.coordinator.resumeCleanup())
            assertTrue(
                fixture.pending.slots
                    .single()
                    .sameAs(slot),
            )
            assertTrue(requireNotNull(fixture.credentials.marker).sameAs(marker))
            assertTrue(fixture.faults.mutations.isEmpty())
        }

    @Test
    fun nullMarkerReadUnavailabilityRemainsTypedAndPreservesPending() =
        runTest {
            val failures =
                InstallationTemporaryFailure.entries.map { InstallationStorageFailure.TemporarilyUnavailable(it) } +
                    InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.UNSUPPORTED)
            failures.forEach { failure ->
                val fixture = InstallationCoordinatorFixture(Fixtures.record())
                fixture.credentials.marker =
                    Fixtures.marker(
                        null,
                        CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED,
                    )
                fixture.credentials.readFailure = failure
                val slot = Fixtures.slot(1)
                fixture.pending.slots += slot
                assertStorageFailure(failure, fixture.coordinator.resumeCleanup())
                assertTrue(
                    fixture.pending.slots
                        .single()
                        .sameAs(slot),
                )
                assertTrue(fixture.faults.mutations.isEmpty())
            }
        }

    @Test
    fun positiveMarkerNeverQualifiesUnexpectedNewOpaquePendingBytes() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.ABANDON)
            val fixture = scenario.fixture
            val unexpected = Fixtures.slot(3, "not tuple-qualified".encodeToByteArray())
            fixture.faults.onStep = { if (it == Step.MARKER_STORED) fixture.pending.slots += unexpected }
            assertRefused(Block.RECONCILIATION_REQUIRED, scenario.execute())
            val clears = fixture.faults.trace.count { it == Step.PENDING_CLEAR_BEFORE }
            assertRefused(Block.RECONCILIATION_REQUIRED, fixture.restart().resumeCleanup())
            assertEquals(clears, fixture.faults.trace.count { it == Step.PENDING_CLEAR_BEFORE })
            assertTrue(
                fixture.pending.slots
                    .single()
                    .sameAs(unexpected),
            )
            assertTrue(fixture.credentials.keyPresent)
            assertNotNull(fixture.credentials.marker)
        }

    @Test
    fun falsePendingClearSuccessDoesNotPermitMarkerCreation() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.RESET)
            val fixture = scenario.fixture
            fixture.pending.clearReply = PendingClearResult.Cleared
            assertRefused(Block.RECONCILIATION_REQUIRED, scenario.execute())
            assertEquals(2, fixture.pending.slots.size)
            assertNull(fixture.credentials.marker)
            assertFalse(Step.MARKER_CREATE_BEFORE in fixture.faults.trace)
            assertFalse(Step.CLEANUP_BEFORE in fixture.faults.trace)
        }

    @Test
    fun falseCredentialCleanupSuccessLeavesMarkerUntilAbsenceIsProven() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.RESET)
            val fixture = scenario.fixture
            fixture.credentials.cleanupReply = CredentialDeleteResult.Deleted
            val failure = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.READ_BACK_MISMATCH)
            assertStorageFailure(failure, scenario.execute())
            assertTrue(fixture.credentials.keyPresent)
            assertTrue(fixture.credentials.payloadPresent)
            assertNotNull(fixture.credentials.marker)
            assertFalse(Step.MARKER_REMOVE_BEFORE in fixture.faults.trace)
        }

    @Test
    fun storedMarkerWithMissingReadbackNeverReachesCredentialCleanup() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.RESET)
            val fixture = scenario.fixture
            fixture.faults.onStep = { if (it == Step.MARKER_STORED) fixture.credentials.marker = null }
            val failure = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.READ_BACK_MISMATCH)
            assertStorageFailure(failure, scenario.execute())
            assertTrue(fixture.credentials.keyPresent)
            assertFalse(Step.CLEANUP_BEFORE in fixture.faults.trace)
        }

    @Test
    fun markerReappearingAfterReportedRemovalStillBlocksNewEnrollment() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.RESET)
            val fixture = scenario.fixture
            val marker = Fixtures.marker(2, CredentialCleanupReason.USER_RESET_CONFIRMED)
            fixture.faults.onStep = { if (it == Step.MARKER_REMOVED) fixture.credentials.marker = marker }
            assertRefused(Block.CLEANUP_REQUIRED, scenario.execute())
            assertFalse(fixture.credentials.keyPresent)
            assertNotNull(fixture.credentials.marker)
            assertRefused(Block.CLEANUP_REQUIRED, fixture.restart().admit(Fixtures.record()))
            assertFalse(Step.CREATE_BEFORE in fixture.faults.trace)
        }
}
