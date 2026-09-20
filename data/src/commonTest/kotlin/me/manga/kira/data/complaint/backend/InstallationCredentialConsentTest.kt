package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationTemporaryFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class InstallationCredentialConsentTest {
    @Test
    fun outstandingConsentBlocksAdmissionApplicationAndOtherTransitions() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val permit = fixture.coordinator.admit().success()
            val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
            var applications = 0
            assertRefused(Block.CONSENT_PENDING, fixture.coordinator.admit())
            assertRefused(Block.CONSENT_PENDING, fixture.coordinator.applyIfCurrent(permit) { applications += 1 })
            assertRefused(Block.CONSENT_PENDING, fixture.coordinator.beginDeletion(permit, Fixtures.KEY))
            assertRefused(Block.CONSENT_PENDING, fixture.coordinator.pendingDeletion())
            assertRefused(Block.CONSENT_PENDING, fixture.coordinator.resumeCleanup())
            assertRefused(Block.CONSENT_PENDING, fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)))
            assertEquals(0, applications)
            assertTrue(fixture.faults.mutations.isEmpty())
            fixture.coordinator.cancelRecovery(prompt).success()
            fixture.coordinator.applyIfCurrent(permit) { applications += 1 }.success()
            assertEquals(1, applications)
        }

    @Test
    fun stalePromptCannotConfirmOrCancelTheNewOutstandingPrompt() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val permit = fixture.coordinator.admit().success()
            val first = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
            fixture.coordinator.cancelRecovery(first).success()
            val second = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
            assertRefused(Block.STALE_CONSENT, fixture.coordinator.confirmRecovery(first))
            assertRefused(Block.STALE_CONSENT, fixture.coordinator.cancelRecovery(first))
            assertRefused(Block.CONSENT_PENDING, fixture.coordinator.admit())
            assertTrue(fixture.faults.mutations.isEmpty())
            fixture.coordinator.cancelRecovery(second).success()
            fixture.coordinator.admit().success()
        }

    @Test
    fun processLocalConfirmationCannotBeReusedAfterReconstruction() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val permit = fixture.coordinator.admit().success()
            val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
            val restarted = fixture.restart()
            assertRefused(Block.STALE_CONSENT, restarted.confirmRecovery(prompt))
            assertRefused(Block.STALE_CONSENT, restarted.cancelRecovery(prompt))
            restarted.admit().success()
            assertTrue(fixture.faults.mutations.isEmpty())
        }

    @Test
    fun confirmationRechecksTheRecordRatherThanTargetingAReplacement() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val permit = fixture.coordinator.admit().success()
            val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
            val replacement = Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID))
            fixture.credentials.install(replacement)
            assertRefused(Block.STALE_BINDING, fixture.coordinator.confirmRecovery(prompt))
            assertTrue(fixture.faults.mutations.isEmpty())
            assertTrue(requireNotNull(fixture.credentials.payloadRecord).sameAs(replacement))
            fixture.coordinator.cancelRecovery(prompt).success()
        }

    @Test
    fun unreadableRecoveryRefusesReadableAndWhollyEmptyStores() =
        runTest {
            listOf(null, Fixtures.record()).forEach { original ->
                val fixture = InstallationCoordinatorFixture(original)
                assertRefused(Block.NOT_UNREADABLE, fixture.coordinator.requestRecovery(RecoveryIntent.Unreadable))
                assertTrue(fixture.faults.mutations.isEmpty())
            }
        }

    @Test
    fun temporaryAndUnsupportedFailuresNeverBecomeUnreadableConsent() =
        runTest {
            val failures =
                InstallationTemporaryFailure.entries.map { InstallationStorageFailure.TemporarilyUnavailable(it) } +
                    InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.UNSUPPORTED)
            failures.forEach { failure ->
                val fixture = InstallationCoordinatorFixture(Fixtures.record())
                fixture.credentials.readFailure = failure
                assertStorageFailure(failure, fixture.coordinator.requestRecovery(RecoveryIntent.Unreadable))
                assertTrue(fixture.faults.mutations.isEmpty())
                fixture.credentials.readFailure = null
                fixture.coordinator.admit().success()
            }
        }

    @Test
    fun missingCredentialWithNewOpaquePendingRequiresAWarnedRequest() =
        runTest {
            val fixture = InstallationCoordinatorFixture()
            val slot = Fixtures.slot(1)
            fixture.pending.slots += slot
            assertRefused(Block.RECONCILIATION_REQUIRED, fixture.coordinator.admit(Fixtures.record()))
            val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Unreadable).success()
            assertTrue(fixture.faults.mutations.isEmpty())
            assertTrue(
                fixture.pending.slots
                    .single()
                    .sameAs(slot),
            )
            fixture.coordinator.confirmRecovery(prompt).success()
            fixture.assertAbsent()
        }

    @Test
    fun missingCredentialWithCorruptOrOversizedNewPendingStillRequiresConfirmation() =
        runTest {
            listOf(InstallationPermanentFailure.CORRUPT, InstallationPermanentFailure.TOO_LARGE).forEach { reason ->
                val fixture = InstallationCoordinatorFixture()
                fixture.pending.slots += Fixtures.slot(1)
                fixture.pending.readFailure = InstallationStorageFailure.PermanentFailure(reason)
                val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Unreadable).success()
                assertTrue(fixture.faults.mutations.isEmpty())
                assertEquals(1, fixture.pending.slots.size)
                fixture.coordinator.confirmRecovery(prompt).success()
                fixture.assertAbsent()
            }
        }

    @Test
    fun changedUnreadableObservationInvalidatesThePromptWithoutClearing() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            fixture.credentials.readFailure =
                InstallationStorageFailure.PermanentFailure(
                    InstallationPermanentFailure.CORRUPT,
                )
            val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Unreadable).success()
            fixture.credentials.readFailure =
                InstallationStorageFailure.PermanentFailure(
                    InstallationPermanentFailure.INVALIDATED,
                )
            assertRefused(Block.STALE_BINDING, fixture.coordinator.confirmRecovery(prompt))
            assertTrue(fixture.faults.mutations.isEmpty())
            assertTrue(fixture.credentials.keyPresent)
        }

    @Test
    fun becomingReadableCannotReuseAnUnreadablePrompt() =
        runTest {
            val fixture = InstallationCoordinatorFixture()
            fixture.pending.slots += Fixtures.slot(1)
            val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Unreadable).success()
            fixture.credentials.install(Fixtures.record())
            assertRefused(Block.NOT_UNREADABLE, fixture.coordinator.confirmRecovery(prompt))
            assertTrue(fixture.faults.mutations.isEmpty())
            assertFalse(fixture.pending.slots.isEmpty())
        }
}
