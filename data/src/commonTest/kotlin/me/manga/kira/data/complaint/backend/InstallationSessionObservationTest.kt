package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class InstallationSessionObservationTest {
    @Test
    fun pendingObservationsReuseTheTokenWithoutRefreshingItsClockOrOldHistoryAuthority() =
        runTest {
            val fixture = ComplaintSessionFixture(this)
            try {
                val prepared = fixture.assertPreparedObservationKeepsTokenOnly()
                fixture.assertDispatchObservationKeepsOriginalExpiry(prepared)
            } finally {
                fixture.close()
            }
        }
}

@OptIn(ExperimentalTime::class)
private suspend fun ComplaintSessionFixture.assertPreparedObservationKeepsTokenOnly(): ComplaintHistorySession {
    val old = assertIs<ComplaintHistorySessionResult.Ready>(manager.historySession()).session
    val ordinary = storage.coordinator.admit().success()
    clock += 300.seconds
    storage.pending.slots += sessionPendingSlot()
    val prepared = assertIs<ComplaintHistorySessionResult.Ready>(manager.historySession()).session
    assertSame(old.entry, prepared.entry)
    assertNotSame(old.permit, prepared.permit)
    assertRefused(
        Block.STALE_BINDING,
        storage.coordinator.applyReconciliationIfCurrent(old.permit) { error("stale observation") },
    )
    assertRefused(Block.RECONCILIATION_REQUIRED, storage.coordinator.admit())
    assertRefused(
        Block.RECONCILIATION_REQUIRED,
        storage.coordinator.applyIfCurrent(ordinary) { error("ordinary admission") },
    )
    return prepared
}

@OptIn(ExperimentalTime::class)
private suspend fun ComplaintSessionFixture.assertDispatchObservationKeepsOriginalExpiry(prepared: ComplaintHistorySession) {
    clock += 599.seconds
    storage.pending.slots[0] = sessionPendingSlot(dispatched = true)
    val dispatched = assertIs<ComplaintHistorySessionResult.Ready>(manager.historySession()).session
    assertSame(prepared.entry, dispatched.entry)
    assertEquals(1, engine.requestHistory.size)
    assertRefused(
        Block.STALE_BINDING,
        storage.coordinator.applyReconciliationIfCurrent(prepared.permit) { error("changed slot") },
    )
    clock += 1.seconds
    val renewed = assertIs<ComplaintHistorySessionResult.Ready>(manager.historySession()).session
    assertNotSame(prepared.entry, renewed.entry)
    assertEquals(2, engine.requestHistory.size)
    manager.invalidateHistorySession(dispatched)
    assertTrue(manager.historySessionIsCurrent(renewed))
    assertTrue(storage.faults.mutations.isEmpty())
}
