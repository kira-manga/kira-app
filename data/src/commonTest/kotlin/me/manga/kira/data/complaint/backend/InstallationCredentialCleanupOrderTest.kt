package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step
import me.manga.kira.platform.storage.CredentialCleanupReason as Reason
import me.manga.kira.platform.storage.InstallationCredentialState as State

class InstallationCredentialCleanupOrderTest {
    @Test
    fun readableResetPersistsGenerationThenPendingAbsenceThenMarkerAndPieces() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.RESET)
            val fixture = scenario.fixture
            fixture.faults.onStep = { step ->
                if (step == Step.MARKER_STORED) {
                    assertMarker(fixture, 2, Reason.USER_RESET_CONFIRMED)
                    assertEquals(State.LOCAL_RESET_PENDING, fixture.credentials.payloadRecord?.state)
                }
            }
            scenario.execute().success()
            assertEquals(
                listOf(
                    Step.REPLACE_STORED,
                    Step.PENDING_SLOT_REMOVED,
                    Step.PENDING_SLOT_REMOVED,
                    Step.PENDING_CLEARED,
                    Step.MARKER_STORED,
                    Step.KEY_REMOVED,
                    Step.PAYLOAD_REMOVED,
                    Step.MARKER_REMOVED,
                ),
                durableEvents(fixture),
            )
            assertBetween(fixture, Step.REPLACE_STORED, Step.PENDING_CLEAR_BEFORE, Step.CREDENTIAL_READ)
            assertBetween(fixture, Step.PENDING_CLEARED, Step.MARKER_STORED, Step.PENDING_READ)
            assertFinalAbsenceOrder(fixture)
        }

    @Test
    fun unreadableResetPersistsNullMarkerBeforeAnyPendingDeletion() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.UNREADABLE)
            val fixture = scenario.fixture
            fixture.faults.onStep = { step ->
                if (step == Step.MARKER_STORED) {
                    assertMarker(fixture, null, Reason.UNREADABLE_RESET_CONFIRMED)
                    assertEquals(2, fixture.pending.slots.size)
                }
            }
            scenario.execute().success()
            assertEquals(
                listOf(
                    Step.MARKER_STORED,
                    Step.PENDING_SLOT_REMOVED,
                    Step.PENDING_SLOT_REMOVED,
                    Step.PENDING_CLEARED,
                    Step.KEY_REMOVED,
                    Step.PAYLOAD_REMOVED,
                    Step.MARKER_REMOVED,
                ),
                durableEvents(fixture),
            )
            assertBetween(fixture, Step.MARKER_STORED, Step.PENDING_CLEAR_BEFORE, Step.MARKER_READ)
            assertFalse(Step.REPLACE_BEFORE in fixture.faults.trace)
            assertFinalAbsenceOrder(fixture)
        }

    @Test
    fun abandonmentRetainsDeletionGenerationAndKeyUntilMarkedCleanup() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.ABANDON)
            val fixture = scenario.fixture
            val deleting = requireNotNull(fixture.credentials.payloadRecord)
            fixture.faults.onStep = { step ->
                if (step == Step.MARKER_STORED) {
                    assertMarker(fixture, deleting.localGeneration, Reason.REMOTE_DELETE_ABANDON_CONFIRMED)
                    assertTrue(requireNotNull(fixture.credentials.payloadRecord).sameAs(deleting))
                    assertEquals(Fixtures.KEY, deleting.pendingDeletionKey)
                }
            }
            scenario.execute().success()
            assertEquals(
                listOf(
                    Step.PENDING_SLOT_REMOVED,
                    Step.PENDING_SLOT_REMOVED,
                    Step.PENDING_CLEARED,
                    Step.MARKER_STORED,
                    Step.KEY_REMOVED,
                    Step.PAYLOAD_REMOVED,
                    Step.MARKER_REMOVED,
                ),
                durableEvents(fixture),
            )
            assertFalse(Step.REPLACE_BEFORE in fixture.faults.trace)
            assertBetween(fixture, Step.PENDING_CLEARED, Step.MARKER_STORED, Step.PENDING_READ)
            assertFinalAbsenceOrder(fixture)
        }

    @Test
    fun syntheticTerminalFactUsesOnlyVerifiedEmptyPendingAndItsExactDeletion() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.TERMINAL)
            val fixture = scenario.fixture
            val deleting = requireNotNull(fixture.credentials.payloadRecord)
            fixture.faults.onStep = { step ->
                if (step == Step.MARKER_STORED) {
                    assertMarker(fixture, deleting.localGeneration, Reason.SERVER_TERMINAL_CONFIRMED)
                    assertTrue(requireNotNull(fixture.credentials.payloadRecord).sameAs(deleting))
                }
            }
            scenario.execute().success()
            assertEquals(
                listOf(Step.MARKER_STORED, Step.KEY_REMOVED, Step.PAYLOAD_REMOVED, Step.MARKER_REMOVED),
                durableEvents(fixture),
            )
            assertFalse(Step.PENDING_CLEAR_BEFORE in fixture.faults.trace)
            assertFalse(Step.REPLACE_BEFORE in fixture.faults.trace)
            assertFinalAbsenceOrder(fixture)
        }

    @Test
    fun unqualifiedNonemptyTerminalPendingIsPreservedAndRefused() =
        runTest {
            val scenario = prepareCleanup(InstallationCleanupPath.TERMINAL)
            val fixture = scenario.fixture
            val slot = Fixtures.slot(1, "unqualified bytes".encodeToByteArray())
            fixture.pending.slots += slot
            assertRefused(Block.RECONCILIATION_REQUIRED, scenario.execute())
            assertTrue(
                fixture.pending.slots
                    .single()
                    .sameAs(slot),
            )
            assertTrue(fixture.faults.mutations.isEmpty())
            assertEquals(State.DELETION_PENDING, fixture.credentials.payloadRecord?.state)
            assertEquals(Fixtures.KEY, fixture.credentials.payloadRecord?.pendingDeletionKey)
        }

    @Test
    fun allTerminalPendingFailuresRemainTypedWithoutClearingAnything() =
        runTest {
            Fixtures.failures().forEach { failure ->
                val scenario = prepareCleanup(InstallationCleanupPath.TERMINAL)
                val fixture = scenario.fixture
                fixture.pending.slots += Fixtures.slot(1)
                fixture.pending.readFailure = failure
                assertStorageFailure(failure, scenario.execute())
                assertTrue(fixture.faults.mutations.isEmpty())
                assertEquals(1, fixture.pending.slots.size)
                assertEquals(Fixtures.KEY, fixture.credentials.payloadRecord?.pendingDeletionKey)
            }
        }

    @Test
    fun unmarkedDeletionPendingAloneNeverAuthorizesLocalErasure() =
        runTest {
            listOf(false, true).forEach { hasPending ->
                val scenario = prepareCleanup(InstallationCleanupPath.TERMINAL)
                val fixture = scenario.fixture
                if (hasPending) fixture.pending.slots += Fixtures.slot(1)
                val restarted = fixture.restart()
                // No terminal fact is supplied: 202/timeouts/cancellation are outside this local authority.
                assertRefused(Block.REMOTE_DELETION_PENDING, restarted.resumeCleanup())
                assertTrue(fixture.faults.mutations.isEmpty())
                assertEquals(
                    Fixtures.KEY,
                    restarted
                        .pendingDeletion()
                        .success()
                        .record.pendingDeletionKey,
                )
                assertEquals(if (hasPending) 1 else 0, fixture.pending.slots.size)
            }
        }

    private fun assertMarker(
        fixture: InstallationCoordinatorFixture,
        generation: Long?,
        reason: Reason,
    ) {
        val marker = requireNotNull(fixture.credentials.marker)
        assertEquals(generation, marker.expectedGeneration)
        assertEquals(reason, marker.reason)
    }

    private fun assertFinalAbsenceOrder(fixture: InstallationCoordinatorFixture) {
        assertBetween(fixture, Step.MARKER_STORED, Step.CLEANUP_BEFORE, Step.MARKER_READ)
        assertBetween(fixture, Step.MARKER_STORED, Step.CLEANUP_BEFORE, Step.PENDING_READ)
        assertBetween(fixture, Step.PAYLOAD_REMOVED, Step.MARKER_REMOVE_BEFORE, Step.CREDENTIAL_READ)
        assertBetween(fixture, Step.PAYLOAD_REMOVED, Step.MARKER_REMOVE_BEFORE, Step.PENDING_READ)
        val afterRemoval = fixture.faults.trace.drop(fixture.faults.trace.indexOf(Step.MARKER_REMOVED) + 1)
        assertEquals(listOf(Step.MARKER_READ, Step.CREDENTIAL_READ, Step.PENDING_READ), afterRemoval)
        fixture.assertAbsent()
    }

    private fun assertBetween(
        fixture: InstallationCoordinatorFixture,
        start: Step,
        end: Step,
        required: Step,
    ) {
        val trace = fixture.faults.trace
        val first = trace.indexOf(start)
        val last = trace.indexOf(end)
        assertTrue(first >= 0 && last > first)
        assertTrue(required in trace.subList(first + 1, last))
    }

    private fun durableEvents(fixture: InstallationCoordinatorFixture): List<Step> =
        fixture.faults.trace.filter {
            it in
                setOf(
                    Step.REPLACE_STORED,
                    Step.PENDING_SLOT_REMOVED,
                    Step.PENDING_CLEARED,
                    Step.MARKER_STORED,
                    Step.KEY_REMOVED,
                    Step.PAYLOAD_REMOVED,
                    Step.MARKER_REMOVED,
                )
        }
}
