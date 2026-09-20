package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Permit
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class InstallationCredentialFailureTest {
    @Test
    fun everyDeclaredCredentialReadFailureRemainsDistinctAndNeverCreates() =
        runTest {
            Fixtures.failures().forEach { failure ->
                val original = Fixtures.record()
                val fixture = InstallationCoordinatorFixture(original)
                fixture.credentials.readFailure = failure
                assertStorageFailure(failure, fixture.coordinator.admit(original))
                assertTrue(fixture.faults.mutations.isEmpty())
                assertTrue(requireNotNull(fixture.credentials.payloadRecord).sameAs(original))
            }
        }

    @Test
    fun keyOnlyAndPayloadOnlyAreDamageNotMissing() =
        runTest {
            val keyOnly = InstallationCoordinatorFixture()
            keyOnly.credentials.keyPresent = true
            val payloadOnly = InstallationCoordinatorFixture()
            payloadOnly.credentials.payloadPresent = true
            val cases =
                listOf(
                    keyOnly to InstallationPermanentFailure.CORRUPT,
                    payloadOnly to InstallationPermanentFailure.INVALIDATED,
                )
            cases.forEach { (fixture, reason) ->
                assertStorageFailure(
                    InstallationStorageFailure.PermanentFailure(reason),
                    fixture.coordinator.admit(Fixtures.record()),
                )
                assertTrue(fixture.faults.mutations.isEmpty())
            }
        }

    @Test
    fun everyPendingReadFailurePreservesItsTypeAndOpaqueBytes() =
        runTest {
            Fixtures.failures().forEach { failure ->
                val fixture = InstallationCoordinatorFixture(Fixtures.record())
                fixture.pending.slots += Fixtures.slot(1)
                fixture.pending.readFailure = failure
                assertStorageFailure(failure, fixture.coordinator.admit(Fixtures.record()))
                assertContentEquals(
                    byteArrayOf(1, 2, 3),
                    fixture.pending.slots
                        .single()
                        .bytes(),
                )
                assertTrue(fixture.faults.mutations.isEmpty())
            }
        }

    @Test
    fun nonemptyInventoryBlocksBothMissingAndReadableCredentialAdmission() =
        runTest {
            listOf(null, Fixtures.record()).forEach { original ->
                val fixture = InstallationCoordinatorFixture(original)
                val slot = Fixtures.slot(1, "unqualified opaque fixture".encodeToByteArray())
                fixture.pending.slots += slot
                assertRefused(Block.RECONCILIATION_REQUIRED, fixture.coordinator.admit(Fixtures.record()))
                assertTrue(
                    fixture.pending.slots
                        .single()
                        .sameAs(slot),
                )
                assertTrue(fixture.faults.mutations.isEmpty())
            }
        }

    @Test
    fun malformedOrOversizedPhysicalInventoryIsNotEmptinessOrAnEvictionRequest() =
        runTest {
            val cases =
                listOf(
                    listOf(Fixtures.slot(1), Fixtures.slot(1, byteArrayOf(9))) to InstallationPermanentFailure.CORRUPT,
                    (1..17).map { Fixtures.slot(it) } to InstallationPermanentFailure.TOO_LARGE,
                )
            cases.forEach { (slots, reason) ->
                val fixture = InstallationCoordinatorFixture()
                fixture.pending.slots += slots
                val failure = InstallationStorageFailure.PermanentFailure(reason)
                assertStorageFailure(failure, fixture.coordinator.admit(Fixtures.record()))
                assertEquals(slots.size, fixture.pending.slots.size)
                slots.zip(fixture.pending.slots).forEach { (expected, actual) -> assertTrue(expected.sameAs(actual)) }
                assertTrue(fixture.faults.mutations.isEmpty())
            }
        }

    @Test
    fun everyMarkerReadFailureBlocksWithoutReadingOrMutatingCredentialPieces() =
        runTest {
            Fixtures.failures().forEach { failure ->
                val fixture = InstallationCoordinatorFixture(Fixtures.record())
                fixture.credentials.markerFailure = failure
                assertStorageFailure(failure, fixture.coordinator.admit(Fixtures.record()))
                assertEquals(listOf(Step.MARKER_READ), fixture.faults.trace)
                assertTrue(fixture.credentials.keyPresent)
            }
        }

    @Test
    fun unsupportedHasZeroMutationAndApplicationWitnesses() =
        runTest {
            val original = Fixtures.record()
            val fixture = InstallationCoordinatorFixture(original)
            val unsupported = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.UNSUPPORTED)
            fixture.credentials.readFailure = unsupported
            var applied = 0
            assertStorageFailure(unsupported, fixture.coordinator.admit(original))
            assertStorageFailure(unsupported, fixture.coordinator.applyIfCurrent(Permit(original)) { applied += 1 })
            assertEquals(0, applied)
            assertTrue(fixture.faults.mutations.isEmpty())
            // There is deliberately no material producer, transport or dispatch hook in this slice.
        }

    @Test
    fun staleAndMissingCasNeverFallThroughToCreate() =
        runTest {
            listOf(false, true).forEach { missing ->
                val fixture = InstallationCoordinatorFixture(Fixtures.record())
                val permit = fixture.coordinator.admit().success()
                fixture.faults.onStep = { step ->
                    if (step == Step.REPLACE_BEFORE) {
                        if (missing) {
                            fixture.credentials.removePieces()
                        } else {
                            fixture.credentials.install(Fixtures.record(generation = 4))
                        }
                    }
                }
                assertRefused(Block.STALE_BINDING, fixture.coordinator.beginDeletion(permit, Fixtures.KEY))
                assertFalse(Step.CREATE_BEFORE in fixture.faults.trace)
                assertFalse(Step.REPLACE_STORED in fixture.faults.trace)
            }
        }

    @Test
    fun everyReplaceFailurePropagatesWithoutAnImplicitSecondIdentity() =
        runTest {
            Fixtures.failures().forEach { failure ->
                val original = Fixtures.record()
                val fixture = InstallationCoordinatorFixture(original)
                val permit = fixture.coordinator.admit().success()
                fixture.faults.failAt(Step.REPLACE_BEFORE, failure)
                assertStorageFailure(failure, fixture.coordinator.beginDeletion(permit, Fixtures.KEY))
                assertTrue(requireNotNull(fixture.credentials.payloadRecord).sameAs(original))
                assertFalse(Step.CREATE_BEFORE in fixture.faults.trace)
            }
        }

    @Test
    fun storedReplacementWithWrongReadbackRetainsEvidenceAndRefuses() =
        runTest {
            val original = Fixtures.record()
            val fixture = InstallationCoordinatorFixture(original)
            val permit = fixture.coordinator.admit().success()
            fixture.faults.onStep = { if (it == Step.REPLACE_STORED) fixture.credentials.install(original) }
            val failure = InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.READ_BACK_MISMATCH)
            assertStorageFailure(failure, fixture.coordinator.beginDeletion(permit, Fixtures.KEY))
            assertFalse(Step.CREATE_BEFORE in fixture.faults.trace)
            assertTrue(fixture.credentials.keyPresent)
        }

    @Test
    fun failedReadbackAfterDurableReplacementKeepsDeletionPending() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val permit = fixture.coordinator.admit().success()
            fixture.faults.failAt(Step.CREDENTIAL_READ, occurrence = 2)
            assertStorageFailure(
                InstallationStoreFaults.ioFailure,
                fixture.coordinator.beginDeletion(permit, Fixtures.KEY),
            )
            assertRefused(Block.REMOTE_DELETION_PENDING, fixture.coordinator.admit())
            assertFalse(Step.CREATE_BEFORE in fixture.faults.trace)
            assertEquals(
                Fixtures.KEY,
                fixture.coordinator
                    .pendingDeletion()
                    .success()
                    .record.pendingDeletionKey,
            )
        }
}
