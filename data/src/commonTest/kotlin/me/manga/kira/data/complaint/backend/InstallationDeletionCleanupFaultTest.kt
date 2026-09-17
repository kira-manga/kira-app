package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step
import me.manga.kira.platform.storage.CredentialCleanupReason as Reason

class InstallationDeletionCleanupFaultTest {
    @Test
    fun everyFailureBeforeMarkerRetainsTheTupleAndRestartMustRepeatTheExactHttpRequest() =
        runTest {
            listOf(
                Step.PENDING_CLEAR_BEFORE,
                Step.PENDING_SLOT_REMOVED,
                Step.PENDING_CLEARED,
                Step.MARKER_CREATE_BEFORE,
            ).forEach { assertPreMarkerDeletionFailure(it) }
        }

    @Test
    fun independentPendingAbsenceReadbackFailureAlsoRequiresExactTerminalReplay() =
        runTest {
            assertPreMarkerDeletionFailure(Step.PENDING_READ)
        }

    @Test
    fun markerAndEveryPartialCredentialBoundaryResumeLocallyWithoutHttpSessionOrNewKey() =
        runTest {
            listOf(
                Step.MARKER_STORED,
                Step.CLEANUP_BEFORE,
                Step.KEY_REMOVED,
                Step.PAYLOAD_REMOVED,
                Step.MARKER_REMOVE_BEFORE,
            ).forEach { assertPostMarkerDeletionFailure(it) }
        }
}

private suspend fun TestScope.assertPreMarkerDeletionFailure(point: Step) {
    val first = InstallationDeletionFixture(this, InstallationCoordinatorFixture(deletingRecord()))
    val storage = first.storage
    storage.pending.slots += listOf(Fixtures.slot(1), Fixtures.slot(2))
    installDeletionCleanupFailure(storage, point)
    val original =
        try {
            assertNotNull(assertDeletionPending(first.repository.continueDeletion()).error)
            assertNull(storage.credentials.marker)
            assertTrue(assertNotNull(storage.credentials.payloadRecord).sameAs(deletingRecord()))
            assertTrue(storage.credentials.keyPresent && storage.credentials.payloadPresent)
            assertRefused(Block.REMOTE_DELETION_PENDING, storage.restart().resumeCleanup())
            first.bodies.single()
        } finally {
            first.close()
            storage.faults.clearFaults()
        }
    val resumed = deletionRestart(storage)
    try {
        assertDeletionCompleted(resumed.repository.continueDeletion())
        assertEquals(listOf(original), resumed.bodies)
        resumed.assertNoNewIdentity()
        storage.assertAbsent()
    } finally {
        resumed.close()
    }
}

private suspend fun TestScope.assertPostMarkerDeletionFailure(point: Step) {
    val first = InstallationDeletionFixture(this, InstallationCoordinatorFixture(deletingRecord()))
    val storage = first.storage
    storage.pending.slots += Fixtures.slot(1)
    storage.faults.failAt(point)
    try {
        assertNotNull(assertDeletionPending(first.repository.continueDeletion()).error)
        val marker = assertNotNull(storage.credentials.marker)
        assertEquals(Reason.SERVER_TERMINAL_CONFIRMED, marker.reason)
        assertEquals(2L, marker.expectedGeneration)
        assertTrue(storage.pending.slots.isEmpty())
    } finally {
        first.close()
    }
    val resumed = deletionRestart(storage)
    try {
        assertDeletionCompleted(resumed.repository.continueDeletion())
        assertTrue(resumed.requests.isEmpty())
        resumed.assertNoNewIdentity()
        storage.assertAbsent()
    } finally {
        resumed.close()
    }
}

private fun installDeletionCleanupFailure(
    storage: InstallationCoordinatorFixture,
    point: Step,
) {
    if (point == Step.PENDING_READ) {
        storage.faults.onStep = { step -> if (step == Step.PENDING_CLEARED) storage.faults.failAt(point) }
    } else {
        storage.faults.failAt(point)
    }
}
