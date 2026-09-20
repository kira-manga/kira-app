package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.PendingDeletion
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ServerTerminalFact
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.InstallationValueIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class InstallationCredentialFenceTest {
    @Test
    fun completeTupleAndImmutableMaterialAreRecheckedBeforeApplication() =
        runTest {
            val original = Fixtures.record()
            val replacements =
                listOf(
                    Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID)),
                    Fixtures.record(version = 2),
                    Fixtures.record(generation = 2),
                    Fixtures.record(material = Fixtures.material(scope = Fixtures.OTHER_ID)),
                    Fixtures.record(material = Fixtures.material(secret = "B".repeat(42) + "A")),
                    Fixtures.record(material = Fixtures.material(platform = "IOS")),
                    original.beginLocalReset().valid(),
                )
            replacements.forEach { replacement ->
                val fixture = InstallationCoordinatorFixture(original)
                val permit = fixture.coordinator.admit().success()
                fixture.credentials.install(replacement)
                var applications = 0
                assertRefused(Block.STALE_BINDING, fixture.coordinator.applyIfCurrent(permit) { applications += 1 })
                assertEquals(0, applications)
                assertTrue(fixture.faults.mutations.isEmpty())
            }
        }

    @Test
    fun oldGenerationOnePermitCannotApplyToANewGenerationOneIdentity() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val old = fixture.coordinator.admit().success()
            val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(old)).success()
            fixture.coordinator.confirmRecovery(prompt).success()
            val next = Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID))
            val current = fixture.coordinator.admit(next).success()
            assertEquals(old.record.localGeneration, current.record.localGeneration)
            assertEquals(old.record.credentialVersion, current.record.credentialVersion)
            var applications = 0
            assertRefused(Block.STALE_BINDING, fixture.coordinator.applyIfCurrent(old) { applications += 1 })
            assertRefused(Block.STALE_BINDING, fixture.coordinator.beginDeletion(old, Fixtures.KEY))
            fixture.coordinator.applyIfCurrent(current) { applications += 1 }.success()
            assertEquals(1, applications)
            assertTrue(requireNotNull(fixture.credentials.payloadRecord).sameAs(next))
        }

    @Test
    fun changedDeletionKeyCannotBeErasedByAnOldTerminalFact() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val permit = fixture.coordinator.admit().success()
            val deletion = fixture.coordinator.beginDeletion(permit, Fixtures.KEY).success()
            val replacement =
                Fixtures.record(
                    generation = deletion.record.localGeneration,
                    state = InstallationCredentialState.DELETION_PENDING,
                    key = Fixtures.OTHER_ID,
                )
            fixture.credentials.install(replacement)
            fixture.faults.trace.clear()
            assertRefused(Block.STALE_BINDING, fixture.coordinator.finishServerDeletion(ServerTerminalFact(deletion)))
            assertTrue(fixture.faults.mutations.isEmpty())
            assertEquals(Fixtures.OTHER_ID, fixture.credentials.payloadRecord?.pendingDeletionKey)
        }

    @Test
    fun oldTerminalFactCannotEraseANewIdentityAfterWarnedAbandonment() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val deletion =
                fixture.coordinator
                    .beginDeletion(fixture.coordinator.admit().success(), Fixtures.KEY)
                    .success()
            val fact = ServerTerminalFact(deletion)
            val warning = fixture.coordinator.requestRecovery(RecoveryIntent.Abandon(deletion)).success()
            fixture.coordinator.confirmRecovery(warning).success()
            val replacement = Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID))
            fixture.coordinator.admit(replacement).success()
            fixture.faults.trace.clear()
            assertRefused(Block.STALE_BINDING, fixture.coordinator.finishServerDeletion(fact))
            assertTrue(fixture.faults.mutations.isEmpty())
            assertTrue(requireNotNull(fixture.credentials.payloadRecord).sameAs(replacement))
        }

    @Test
    fun evenAnInternalSyntheticTerminalInputCannotTargetActiveState() =
        runTest {
            val active = Fixtures.record()
            val fixture = InstallationCoordinatorFixture(active)
            val forgedPrerequisite = ServerTerminalFact(PendingDeletion(active))
            assertRefused(Block.STALE_BINDING, fixture.coordinator.finishServerDeletion(forgedPrerequisite))
            assertTrue(fixture.faults.mutations.isEmpty())
            assertTrue(fixture.credentials.keyPresent)
        }

    @Test
    fun pendingDeletionCanBeRetrievedWithOpaqueWorkButCannotBecomeAReset() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val permit = fixture.coordinator.admit().success()
            val expected = fixture.coordinator.beginDeletion(permit, Fixtures.KEY).success()
            fixture.pending.slots += Fixtures.slot(1)
            val restored = fixture.restart().pendingDeletion().success()
            assertTrue(restored.record.sameAs(expected.record))
            assertRefused(Block.STALE_BINDING, fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)))
            assertRefused(Block.RECONCILIATION_REQUIRED, fixture.coordinator.admit())
            assertFalse(Step.PENDING_CLEAR_BEFORE in fixture.faults.trace)
        }

    @Test
    fun invalidDeletionKeyAndGenerationOverflowNeverAttemptReplacement() =
        runTest {
            val cases =
                listOf(
                    Triple(Fixtures.record(), "bad-key", InstallationValueIssue.STATE_KEY),
                    Triple(
                        Fixtures.record(generation = Long.MAX_VALUE),
                        Fixtures.KEY,
                        InstallationValueIssue.GENERATION_OVERFLOW,
                    ),
                )
            cases.forEach { (record, key, issue) ->
                val fixture = InstallationCoordinatorFixture(record)
                val permit = fixture.coordinator.admit().success()
                assertEquals(issue, assertIs<Outcome.Invalid>(fixture.coordinator.beginDeletion(permit, key)).issue)
                assertTrue(fixture.faults.mutations.isEmpty())
                assertTrue(requireNotNull(fixture.credentials.payloadRecord).sameAs(record))
            }
        }

    @Test
    fun publicCoordinationDiagnosticsNeverPrintTheRetainedBindingOrSecret() =
        runTest {
            val fixture = InstallationCoordinatorFixture(Fixtures.record())
            val admitted = fixture.coordinator.admit()
            val permit = admitted.success()
            val confirmation = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
            fixture.coordinator.cancelRecovery(confirmation).success()
            val deletion = fixture.coordinator.beginDeletion(permit, Fixtures.KEY).success()
            val values = listOf(admitted, permit, confirmation, deletion, ServerTerminalFact(deletion))
            values.forEach { value ->
                assertFalse(value.toString().contains(Fixtures.ID))
                assertFalse(value.toString().contains(Fixtures.secret))
                assertFalse(value.toString().contains(Fixtures.KEY))
            }
        }
}
