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
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = InstallationDeletionFixture(this, sessionHandler = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
                }
            })
            val permit = fixture.coordinator.admit().success()
            try {
                val caller = async { fixture.repository.startDeletion() }
                entered.await()
                val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
                fixture.coordinator.cancelRecovery(prompt).success()
                release.complete(Unit)
                assertFailsWith<CancellationException> { caller.await() }
                fixture.assertRetained(Fixtures.record())
                assertEquals(0, fixture.keyCalls)
                assertTrue(fixture.requests.isEmpty() && fixture.storage.faults.mutations.isEmpty())
            } finally {
                release.complete(Unit)
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
            for (record in deletionReplacementRecords().map { deletingRecord(it) } + deletingRecord(key = historyId(90))) {
                assertDeletionTerminalReplacement(record)
            }
        }
}

private suspend fun TestScope.assertDeletionSessionReplacement(replacement: InstallationCredentialRecord?) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val fixture = InstallationDeletionFixture(this, sessionHandler = {
        entered.complete(Unit)
        release.await()
        respond(sessionResponse(), HttpStatusCode.OK, sessionHeaders())
    })
    try {
        val caller = async { fixture.repository.startDeletion() }
        entered.await()
        if (replacement == null) {
            fixture.storage.pending.slots += sessionPendingSlot()
        } else {
            fixture.storage.credentials.install(replacement)
        }
        release.complete(Unit)
        assertIs<AppResult.Failure>(caller.await())
        assertEquals(0, fixture.keyCalls)
        assertTrue(fixture.requests.isEmpty() && fixture.storage.faults.mutations.isEmpty())
        assertTrue(assertNotNull(fixture.storage.credentials.payloadRecord).sameAs(replacement ?: Fixtures.record()))
    } finally {
        release.complete(Unit)
        fixture.close()
    }
}

private suspend fun TestScope.assertDeletionTerminalReplacement(replacement: InstallationCredentialRecord) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val storage = InstallationCoordinatorFixture(deletingRecord())
    val slot = Fixtures.slot(1)
    storage.pending.slots += slot
    val fixture = InstallationDeletionFixture(this, storage, deletionHandler = {
        entered.complete(Unit)
        release.await()
        respond("", HttpStatusCode.NoContent, deletionHeaders())
    })
    try {
        val caller = async { fixture.repository.continueDeletion() }
        entered.await()
        storage.credentials.install(replacement)
        release.complete(Unit)
        assertNotNull(assertDeletionPending(caller.await()).error)
        fixture.assertRetained(replacement, listOf(slot))
        assertTrue(storage.faults.mutations.isEmpty())
    } finally {
        release.complete(Unit)
        fixture.close()
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
