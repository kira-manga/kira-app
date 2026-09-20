package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.platform.storage.CredentialCleanupReason
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationTemporaryFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

class InstallationReconciliationTest {
    @Test
    fun validPreparedAndDispatchedInventoryNeverWeakensOrdinaryAdmission() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val ordinary = fixture.coordinator.admit().success()
            fixture.pending.slots += sessionPendingSlot()
            fixture.pending.slots += sessionPendingSlot(key = Fixtures.slot(2).id, dispatched = true)
            val expected = fixture.pending.slots.toList()
            val permit = fixture.coordinator.beginReconciliation().success()
            assertRefused(Block.RECONCILIATION_REQUIRED, fixture.coordinator.admit())
            assertRefused(
                Block.RECONCILIATION_REQUIRED,
                fixture.coordinator.applyIfCurrent(ordinary) { error("must not apply") },
            )
            var applied = 0
            fixture.coordinator.applyReconciliationIfCurrent(permit) { applied++ }.success()
            fixture.pending.slots.reverse()
            fixture.coordinator.applyReconciliationIfCurrent(permit) { applied++ }.success()
            assertEquals(2, applied)
            assertTrue(expected.all { old -> fixture.pending.slots.any { old.sameAs(it) } })
            assertTrue(fixture.faults.mutations.isEmpty())
            assertEquals("InstallationReconciliationPermit(redacted)", permit.toString())
        }

    @Test
    fun corruptForeignAndUncertainInventoryPreserveTheirTypedReasonAndEvidence() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val corrupt = Fixtures.slot(1)
            fixture.pending.slots += corrupt
            assertStorageFailure(
                InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.CORRUPT),
                fixture.coordinator.beginReconciliation(),
            )
            assertTrue(corrupt.sameAs(fixture.pending.slots.single()))
            fixture.pending.slots[0] = sessionPendingSlot(Fixtures.record(generation = 2))
            assertRefused(Block.STALE_BINDING, fixture.coordinator.beginReconciliation())
            val uncertain = InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.UNCERTAIN)
            fixture.pending.readFailure = uncertain
            assertStorageFailure(uncertain, fixture.coordinator.beginReconciliation())
            assertTrue(fixture.faults.mutations.isEmpty())
        }

    @Test
    fun exactSlotBytesAndEveryCredentialFieldFenceApplicationAndForeignCoordinators() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            fixture.pending.slots += sessionPendingSlot()
            val permit = fixture.coordinator.beginReconciliation().success()
            assertRefused(
                Block.STALE_BINDING,
                fixture.restart().applyReconciliationIfCurrent(permit) { error("foreign issuer") },
            )
            fixture.pending.slots[0] = sessionPendingSlot(dispatched = true)
            assertRefused(
                Block.STALE_BINDING,
                fixture.coordinator.applyReconciliationIfCurrent(permit) { error("changed bytes") },
            )
            fixture.pending.slots.clear()
            assertRefused(
                Block.STALE_BINDING,
                fixture.coordinator.applyReconciliationIfCurrent(permit) { error("removed slot") },
            )
            for (replacement in changedCredentials()) {
                val current = InstallationCoordinatorFixture(Fixtures.record())
                val captured = current.coordinator.beginReconciliation().success()
                current.credentials.install(replacement)
                assertRefused(
                    Block.STALE_BINDING,
                    current.coordinator.applyReconciliationIfCurrent(captured) { error("changed tuple") },
                )
                assertTrue(current.faults.mutations.isEmpty())
            }
        }

    @Test
    fun consentCancelCleanupAndDeletionFenceOldReconciliationWithoutGrantingErasure() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val ordinary = fixture.coordinator.admit().success()
            val permit = fixture.coordinator.beginReconciliation().success()
            val confirmation = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(ordinary)).success()
            assertRefused(Block.CONSENT_PENDING, fixture.coordinator.beginReconciliation())
            fixture.coordinator.cancelRecovery(confirmation).success()
            assertRefused(
                Block.STALE_BINDING,
                fixture.coordinator.applyReconciliationIfCurrent(permit) { error("consent ABA") },
            )
            val fresh = fixture.coordinator.beginReconciliation().success()
            fixture.credentials.marker = Fixtures.marker(1, CredentialCleanupReason.USER_RESET_CONFIRMED)
            assertRefused(
                Block.CLEANUP_REQUIRED,
                fixture.coordinator.applyReconciliationIfCurrent(fresh) { error("marker") },
            )
            fixture.credentials.marker = null
            fixture.coordinator.beginDeletion(ordinary, Fixtures.KEY).success()
            assertRefused(Block.REMOTE_DELETION_PENDING, fixture.coordinator.beginReconciliation())
            assertRefused(
                Block.STALE_BINDING,
                fixture.coordinator.applyReconciliationIfCurrent(fresh) { error("deletion") },
            )
            assertTrue(fixture.credentials.keyPresent && fixture.credentials.payloadPresent)
            assertTrue(fixture.pending.slots.isEmpty())
        }
}

private fun changedCredentials(): List<InstallationCredentialRecord> =
    listOf(
        Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID)),
        Fixtures.record(version = 2),
        Fixtures.record(generation = 2),
        Fixtures.record(material = Fixtures.material(scope = Fixtures.OTHER_ID)),
        Fixtures.record(material = Fixtures.material(secret = "B".repeat(42) + "A")),
        Fixtures.record(material = Fixtures.material(platform = "IOS")),
    )
