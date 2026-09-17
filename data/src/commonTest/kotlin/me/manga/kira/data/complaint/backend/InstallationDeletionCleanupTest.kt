package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ServerTerminalFact
import me.manga.kira.platform.storage.CredentialDeleteResult
import me.manga.kira.platform.storage.PendingClearResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step
import me.manga.kira.platform.storage.CredentialCleanupReason as Reason

class InstallationDeletionCleanupTest {
    @Test
    fun onlyActualTerminalExchangeQualifiesOpaquePendingAndCleanupOrderIncludesEveryReadback() =
        runTest {
            for (code in listOf(null, "INSTALLATION_DELETED", "INSTALLATION_RETIRED", "INSTALLATION_SCOPE_RETIRED")) {
                val storage = InstallationCoordinatorFixture(deletingRecord())
                storage.pending.slots += listOf(Fixtures.slot(1), Fixtures.slot(2))
                val fixture = terminalDeletionFixture(storage, code)
                try {
                    val synthetic = ServerTerminalFact(fixture.coordinator.pendingDeletion().success())
                    assertRefused(Block.RECONCILIATION_REQUIRED, fixture.coordinator.finishServerDeletion(synthetic))
                    assertTrue(storage.faults.mutations.isEmpty())
                    assertDeletionCompleted(fixture.repository.continueDeletion())
                    assertDeletionCleanupOrder(storage)
                    fixture.assertNoNewIdentity()
                    storage.assertAbsent()
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun dishonestPendingClearCannotCreateMarkerOrTouchCredentialPieces() =
        runTest {
            val storage = InstallationCoordinatorFixture(deletingRecord())
            val slot = Fixtures.slot(1)
            storage.pending.slots += slot
            storage.pending.clearReply = PendingClearResult.Cleared
            val fixture = InstallationDeletionFixture(this, storage)
            try {
                assertNotNull(assertDeletionPending(fixture.repository.continueDeletion()).error)
                fixture.assertRetained(deletingRecord(), listOf(slot))
                assertTrue(Step.MARKER_CREATE_BEFORE !in storage.faults.trace)
                assertTrue(Step.CLEANUP_BEFORE !in storage.faults.trace)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun dishonestCredentialDeleteKeepsServerMarkerAndNextAttemptRetriesLocalCleanupOnly() =
        runTest {
            val storage = InstallationCoordinatorFixture(deletingRecord())
            storage.credentials.cleanupReply = CredentialDeleteResult.Deleted
            val fixture = InstallationDeletionFixture(this, storage)
            try {
                assertNotNull(assertDeletionPending(fixture.repository.continueDeletion()).error)
                assertEquals(Reason.SERVER_TERMINAL_CONFIRMED, assertNotNull(storage.credentials.marker).reason)
                assertTrue(storage.credentials.keyPresent && storage.credentials.payloadPresent)
                assertTrue(Step.MARKER_REMOVE_BEFORE !in storage.faults.trace)
                storage.credentials.cleanupReply = null
                assertDeletionCompleted(fixture.repository.continueDeletion())
                assertEquals(1, fixture.requests.size)
                fixture.assertNoNewIdentity()
                storage.assertAbsent()
            } finally {
                fixture.close()
            }
        }

    @Test
    fun localAbandonmentAndResetMarkersAreNeverReportedAsRemoteCompletion() =
        runTest {
            for (reason in Reason.entries.filter { it != Reason.SERVER_TERMINAL_CONFIRMED }) {
                val storage = InstallationCoordinatorFixture(deletingRecord())
                val generation = if (reason == Reason.UNREADABLE_RESET_CONFIRMED) null else 2L
                storage.credentials.marker = Fixtures.marker(generation, reason)
                val fixture = InstallationDeletionFixture(this, storage)
                try {
                    assertIs<AppResult.Failure>(fixture.repository.continueDeletion())
                    assertEquals(reason, assertNotNull(storage.credentials.marker).reason)
                    assertTrue(storage.credentials.keyPresent && storage.credentials.payloadPresent)
                    assertTrue(storage.faults.mutations.isEmpty())
                    assertTrue(fixture.requests.isEmpty())
                    fixture.assertNoNewIdentity()
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun aServerMarkerCannotDeleteAReplacementCredentialOrNormalPendingInventory() =
        runTest {
            for (newer in listOf(false, true)) {
                val record = if (newer) Fixtures.record(generation = 3) else deletingRecord()
                val storage = InstallationCoordinatorFixture(record)
                storage.credentials.marker = Fixtures.marker(2, Reason.SERVER_TERMINAL_CONFIRMED)
                if (!newer) storage.pending.slots += Fixtures.slot(1)
                val fixture = InstallationDeletionFixture(this, storage)
                try {
                    assertIs<AppResult.Failure>(fixture.repository.continueDeletion())
                    assertTrue(assertNotNull(storage.credentials.payloadRecord).sameAs(record))
                    assertNotNull(storage.credentials.marker)
                    assertTrue(Step.KEY_REMOVED !in storage.faults.trace)
                    assertTrue(Step.PENDING_CLEAR_BEFORE !in storage.faults.trace)
                    assertTrue(fixture.requests.isEmpty() && fixture.sessionRequests.isEmpty())
                } finally {
                    fixture.close()
                }
            }
        }
}

private fun TestScope.terminalDeletionFixture(
    storage: InstallationCoordinatorFixture,
    code: String?,
): InstallationDeletionFixture =
    InstallationDeletionFixture(
        this,
        storage,
        deletionHandler = {
            val status = if (code == null) HttpStatusCode.NoContent else HttpStatusCode.Gone
            respond(if (code == null) "" else mutationProblem(status, code), status, deletionHeaders(status))
        },
    )

private fun assertDeletionCleanupOrder(storage: InstallationCoordinatorFixture) {
    val mutations = storage.faults.mutations
    assertEquals(
        listOf(
            Step.PENDING_CLEAR_BEFORE,
            Step.PENDING_SLOT_REMOVED,
            Step.PENDING_SLOT_REMOVED,
            Step.PENDING_CLEARED,
            Step.MARKER_CREATE_BEFORE,
            Step.MARKER_STORED,
            Step.CLEANUP_BEFORE,
            Step.KEY_REMOVED,
            Step.PAYLOAD_REMOVED,
            Step.MARKER_REMOVE_BEFORE,
            Step.MARKER_REMOVED,
        ),
        mutations,
    )
    assertDeletionReadback(storage, Step.PENDING_CLEARED, Step.MARKER_CREATE_BEFORE, Step.PENDING_READ)
    assertDeletionReadback(storage, Step.MARKER_STORED, Step.CLEANUP_BEFORE, Step.MARKER_READ)
    assertDeletionReadback(storage, Step.PAYLOAD_REMOVED, Step.MARKER_REMOVE_BEFORE, Step.CREDENTIAL_READ)
    val tail = storage.faults.trace.dropWhile { it != Step.MARKER_REMOVED }.drop(1)
    assertEquals(listOf(Step.MARKER_READ, Step.CREDENTIAL_READ, Step.PENDING_READ), tail)
    assertNull(storage.credentials.marker)
}

private fun assertDeletionReadback(
    storage: InstallationCoordinatorFixture,
    after: Step,
    before: Step,
    read: Step,
) {
    val trace = storage.faults.trace
    val start = trace.indexOf(after)
    val end = trace.indexOf(before)
    assertTrue(start >= 0 && end > start)
    assertTrue(read in trace.subList(start + 1, end))
}
