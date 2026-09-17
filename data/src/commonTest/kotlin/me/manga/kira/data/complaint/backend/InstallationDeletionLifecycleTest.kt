package me.manga.kira.data.complaint.backend

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.platform.storage.InstallationCredentialRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

class InstallationDeletionLifecycleTest {
    @Test
    fun changedCredentialOrInventoryDuringFreshSessionCannotAllocateKeyOrPersistIntent() =
        runTest {
            for (record in deletionReplacementRecords()) assertDeletionSessionReplacement(record)
            assertDeletionSessionReplacement(null)
        }

    @Test
    fun consentThenCancellationOfThePromptStillInvalidatesTheEarlierStart() =
        runTest {
            val barrier = DeletionExchangeBarrier()
            val fixture = deletionConsentFixture(barrier)
            val permit = fixture.coordinator.admit().success()
            try {
                val caller = async { fixture.repository.startDeletion() }
                barrier.entered.await()
                val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
                fixture.coordinator.cancelRecovery(prompt).success()
                barrier.release.complete(Unit)
                assertFailsWith<CancellationException> { caller.await() }
                fixture.assertRetained(Fixtures.record())
                assertEquals(0, fixture.keyCalls)
                assertTrue(fixture.requests.isEmpty() && fixture.storage.faults.mutations.isEmpty())
            } finally {
                barrier.release.complete(Unit)
                fixture.close()
            }
        }

    @Test
    fun closeAtDurableIntentBoundaryRetainsExactTupleAndNeverDispatchesOrCleansUp() =
        runTest {
            val fixture = InstallationDeletionFixture(this)
            fixture.storage.faults.onStep = { if (it == Step.REPLACE_STORED) fixture.works.close() }
            try {
                assertFailsWith<CancellationException> { fixture.repository.startDeletion() }
                fixture.assertRetained(deletingRecord())
                assertTrue(fixture.requests.isEmpty())
                assertTrue(Step.PENDING_CLEAR_BEFORE !in fixture.storage.faults.trace)
                assertTrue(Step.CLEANUP_BEFORE !in fixture.storage.faults.trace)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun lateTerminalCannotEraseChangedIdSecretScopeVersionGenerationOrDeletionKey() =
        runTest {
            val replacements =
                deletionReplacementRecords().map { deletingRecord(it) } + deletingRecord(key = historyId(90))
            for (record in replacements) {
                assertDeletionTerminalReplacement(record)
            }
        }
}

private suspend fun TestScope.assertDeletionSessionReplacement(replacement: InstallationCredentialRecord?) {
    val barrier = DeletionExchangeBarrier()
    val fixture = pausedDeletionSession(barrier)
    try {
        val caller = async { fixture.repository.startDeletion() }
        barrier.entered.await()
        if (replacement == null) {
            fixture.storage.pending.slots += sessionPendingSlot()
        } else {
            fixture.storage.credentials.install(replacement)
        }
        barrier.release.complete(Unit)
        assertIs<AppResult.Failure>(caller.await())
        assertEquals(0, fixture.keyCalls)
        assertTrue(fixture.requests.isEmpty() && fixture.storage.faults.mutations.isEmpty())
        assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(replacement ?: Fixtures.record()))
    } finally {
        barrier.release.complete(Unit)
        fixture.close()
    }
}

private suspend fun TestScope.assertDeletionTerminalReplacement(replacement: InstallationCredentialRecord) {
    val barrier = DeletionExchangeBarrier()
    val storage = InstallationCoordinatorFixture(deletingRecord())
    val slot = Fixtures.slot(1)
    storage.pending.slots += slot
    val fixture = pausedDeletionTerminal(storage, barrier)
    try {
        val caller = async { fixture.repository.continueDeletion() }
        barrier.entered.await()
        storage.credentials.install(replacement)
        barrier.release.complete(Unit)
        assertNotNull(assertDeletionPending(caller.await()).error)
        fixture.assertRetained(replacement, listOf(slot))
        assertTrue(storage.faults.mutations.isEmpty())
    } finally {
        barrier.release.complete(Unit)
        fixture.close()
    }
}

private fun TestScope.deletionConsentFixture(barrier: DeletionExchangeBarrier): InstallationDeletionFixture =
    InstallationDeletionFixture(
        this,
        sessionHandler = {
            withContext(NonCancellable) {
                barrier.pause()
                respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
            }
        },
    )

private fun TestScope.pausedDeletionSession(barrier: DeletionExchangeBarrier): InstallationDeletionFixture =
    InstallationDeletionFixture(
        this,
        sessionHandler = {
            barrier.pause()
            respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
        },
    )

private fun TestScope.pausedDeletionTerminal(
    storage: InstallationCoordinatorFixture,
    barrier: DeletionExchangeBarrier,
): InstallationDeletionFixture =
    InstallationDeletionFixture(
        this,
        storage,
        deletionHandler = {
            barrier.pause()
            respond("", HttpStatusCode.NoContent, deletionHeaders())
        },
    )

private class DeletionExchangeBarrier {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    suspend fun pause() {
        entered.complete(Unit)
        release.await()
    }
}

private fun deletionReplacementRecords(): List<InstallationCredentialRecord> =
    listOf(
        Fixtures.record(material = Fixtures.material(id = Fixtures.OTHER_ID)),
        Fixtures.record(material = Fixtures.material(secret = "B".repeat(42) + "E")),
        Fixtures.record(material = Fixtures.material(scope = historyId(91))),
        Fixtures.record(version = 2),
        Fixtures.record(generation = 3),
    )
