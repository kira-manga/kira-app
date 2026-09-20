package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Permit
import me.manga.kira.platform.storage.CredentialCleanupReason
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationTemporaryFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class InstallationCredentialAdmissionTest {
    @Test
    fun missingWithoutAnExplicitCandidateNeverCreates() =
        runTest {
            val fixture = InstallationCoordinatorFixture()
            assertRefused(Block.MISSING, fixture.coordinator.admit())
            assertTrue(fixture.faults.mutations.isEmpty())
            fixture.assertAbsent()
        }

    @Test
    fun explicitCandidateIsReadBackBeforeItsPermitCanApply() =
        runTest {
            val fixture = InstallationCoordinatorFixture()
            val candidate = Fixtures.record()
            val permit = fixture.coordinator.admit(candidate).success()
            assertTrue(permit.record.sameAs(candidate))
            assertEquals(2, fixture.faults.trace.count { it == Step.CREDENTIAL_READ })
            assertEquals(1, fixture.faults.trace.count { it == Step.CREATE_STORED })
            var applied = 0
            fixture.coordinator.applyIfCurrent(permit) { applied += 1 }.success()
            assertEquals(1, applied)
        }

    @Test
    fun alreadyPresentDiscardsTheLosingCandidateAndReadsTheWinner() =
        runTest {
            val fixture = InstallationCoordinatorFixture()
            val loser = Fixtures.record()
            val winner = Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID))
            fixture.faults.onStep = { if (it == Step.CREATE_BEFORE) fixture.credentials.install(winner) }
            val permit = fixture.coordinator.admit(loser).success()
            assertTrue(permit.record.sameAs(winner))
            assertEquals(1, fixture.faults.trace.count { it == Step.CREATE_BEFORE })
            assertFalse(Step.CREATE_STORED in fixture.faults.trace)
            var applied = false
            assertRefused(Block.STALE_BINDING, fixture.coordinator.applyIfCurrent(Permit(loser)) { applied = true })
            assertFalse(applied)
        }

    @Test
    fun alreadyPresentWithUnreadablePiecesIsNotAnEnrollmentRetry() =
        runTest {
            val fixture = InstallationCoordinatorFixture()
            fixture.faults.onStep = { if (it == Step.CREATE_BEFORE) fixture.credentials.keyPresent = true }
            val failure = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.CORRUPT)
            assertStorageFailure(failure, fixture.coordinator.admit(Fixtures.record()))
            assertEquals(1, fixture.faults.trace.count { it == Step.CREATE_BEFORE })
            assertTrue(fixture.credentials.keyPresent)
            assertFalse(fixture.credentials.payloadPresent)
        }

    @Test
    fun noninitialShapesCannotFillMissingStorage() =
        runTest {
            val candidates =
                listOf(
                    Fixtures.record(version = 2),
                    Fixtures.record(generation = 2),
                    Fixtures.record().beginLocalReset().valid(),
                    Fixtures.record().beginDeletion(Fixtures.KEY).valid(),
                )
            candidates.forEach { candidate ->
                val fixture = InstallationCoordinatorFixture()
                assertRefused(Block.INVALID_CANDIDATE, fixture.coordinator.admit(candidate))
                assertTrue(fixture.faults.mutations.isEmpty())
                fixture.assertAbsent()
            }
        }

    @Test
    fun uncertainCreatePreservesTheWrittenIdentityAndReconcilesByReading() =
        runTest {
            val fixture = InstallationCoordinatorFixture()
            val original = Fixtures.record()
            val failure = InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.UNCERTAIN)
            fixture.faults.failAt(Step.CREATE_STORED, failure)
            assertStorageFailure(failure, fixture.coordinator.admit(original))
            val different = Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID))
            val reconciled = fixture.coordinator.admit(different).success()
            assertTrue(reconciled.record.sameAs(original))
            assertEquals(1, fixture.faults.trace.count { it == Step.CREATE_BEFORE })
        }

    @Test
    fun storedCreateWithDifferentReadbackDoesNotAdmitOrRecreate() =
        runTest {
            val fixture = InstallationCoordinatorFixture()
            val other = Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID))
            fixture.faults.onStep = { if (it == Step.CREATE_STORED) fixture.credentials.install(other) }
            val failure = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.READ_BACK_MISMATCH)
            assertStorageFailure(failure, fixture.coordinator.admit(Fixtures.record()))
            assertEquals(1, fixture.faults.trace.count { it == Step.CREATE_BEFORE })
            assertTrue(requireNotNull(fixture.credentials.payloadRecord).sameAs(other))
        }

    @Test
    fun storedCreateWithMissingReadbackDoesNotRunASecondCreate() =
        runTest {
            val fixture = InstallationCoordinatorFixture()
            fixture.faults.onStep = { if (it == Step.CREATE_STORED) fixture.credentials.removePieces() }
            assertRefused(Block.MISSING, fixture.coordinator.admit(Fixtures.record()))
            assertEquals(1, fixture.faults.trace.count { it == Step.CREATE_BEFORE })
            fixture.assertAbsent()
        }

    @Test
    fun durableDeletionIntentIsNotOrdinaryAdmissionOrCleanupAuthority() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val permit = fixture.coordinator.admit().success()
            val deletion = fixture.coordinator.beginDeletion(permit, Fixtures.KEY).success()
            assertEquals(2L, deletion.record.localGeneration)
            assertEquals(1L, deletion.record.credentialVersion)
            assertEquals(InstallationCredentialState.DELETION_PENDING, deletion.record.state)
            assertEquals(Fixtures.KEY, deletion.record.pendingDeletionKey)
            assertRefused(Block.REMOTE_DELETION_PENDING, fixture.coordinator.admit())
            assertRefused(Block.REMOTE_DELETION_PENDING, fixture.restart().resumeCleanup())
            assertTrue(fixture.credentials.keyPresent)
            assertFalse(Step.CLEANUP_BEFORE in fixture.faults.trace)
        }

    @Test
    fun retainedMarkerBlocksNewIdentityEvenWhenCredentialPiecesAreAbsent() =
        runTest {
            val fixture = InstallationCoordinatorFixture()
            fixture.credentials.marker = Fixtures.marker(2, CredentialCleanupReason.USER_RESET_CONFIRMED)
            assertRefused(Block.CLEANUP_REQUIRED, fixture.coordinator.admit(Fixtures.record()))
            assertTrue(fixture.faults.mutations.isEmpty())
            assertFalse(fixture.credentials.keyPresent)
        }
}
