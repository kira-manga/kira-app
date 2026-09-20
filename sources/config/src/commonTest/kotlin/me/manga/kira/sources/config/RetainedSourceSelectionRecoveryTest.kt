package me.manga.kira.sources.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.CommittedSourceSelection
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceCatalogStore
import me.manga.kira.sources.contracts.SourceSelectionExpectation
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.VerifiedSourceSelection
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Controlled manager ownership/routing only: synthetic digests do not prove Room or native exclusion. */
@OptIn(ExperimentalCoroutinesApi::class)
class RetainedSourceSelectionRecoveryTest {
    @Test
    fun busy_replacement_readopts_current_a_receipt_without_hiding_the_failure() = runTest {
        val f = RetainedRecoveryFixture()
        val first = f.seed()
        var current = first
        var generation = f.durable.nextGeneration
        f.store.beforeRecovery = { a ->
            assertNull(f.durable.ready)
            assertEquals(first.payload, a.payload)
            current = replaceAndRestoreA(f.durable, a)
            generation = f.durable.nextGeneration
        }
        f.store.afterRecovery = {
            assertEquals(current, f.durable.ready)
            assertIs<UpdateState.Failed>(f.subject.state.value)
        }
        f.assertOriginalFailure(f.subject.refresh())
        f.assertOneRecovery()
        assertEquals(current, f.durable.ready)
        assertEquals(first.token.generation + 2, current.token.generation)
        assertNotEquals(first.token, current.token)
        assertEquals(generation, f.durable.nextGeneration)
        val reads = f.durable.historicalReads
        f.store.failedRemoteCommit = false
        assertIs<AppResult.Success<*>>(f.subject.restoreLocalSelection())
        assertEquals(reads, f.durable.historicalReads, "warm reuse must retain the returned current receipt, not A's old token")
        assertEquals(current, f.durable.ready)
        assertEquals(1, f.remote.manifestFetches)
    }

    @Test
    fun changed_whole_payload_at_same_identity_or_lost_selection_denies_old_a() = runTest {
        for (lost in listOf(false, true)) {
            val f = RetainedRecoveryFixture()
            val first = f.seed()
            var current: CommittedSourceSelection? = first
            var generation = f.durable.nextGeneration
            f.store.beforeRecovery = { a ->
                assertNull(f.durable.ready)
                if (lost) {
                    f.durable.loseSelectedPayload()
                    current = null
                } else {
                    current = replacePayloadAtSameIdentity(f.durable, a)
                    assertEquals(first.token.identity, assertNotNull(current).token.identity)
                    assertNotEquals(first.payload, assertNotNull(current).payload)
                    assertNotEquals(first.token.payloadDigest, assertNotNull(current).token.payloadDigest)
                }
                generation = f.durable.nextGeneration
            }
            f.assertOriginalFailure(f.subject.refresh())
            f.assertOneRecovery()
            assertNull(f.durable.ready)
            assertEquals(current, f.durable.selection)
            assertEquals(generation, f.durable.nextGeneration)
        }
    }

    @Test
    fun adoption_error_or_own_timeout_clears_readiness_without_replacing_the_original_error() = runTest {
        for (timeout in listOf(false, true)) {
            val f = RetainedRecoveryFixture()
            val first = f.seed()
            val generation = f.durable.nextGeneration
            f.store.afterRecovery = {
                assertEquals(first, f.durable.ready)
                assertIs<UpdateState.Failed>(f.subject.state.value)
                if (timeout) awaitCancellation() else error("fixture adoption return failure")
            }
            val started = testScheduler.currentTime
            f.assertOriginalFailure(f.subject.refresh())
            f.assertOneRecovery()
            assertEquals(if (timeout) 2_000L else 0L, testScheduler.currentTime - started)
            assertNull(f.durable.ready)
            assertEquals(first, f.durable.selection)
            assertEquals(generation, f.durable.nextGeneration)
        }
    }

    @Test
    fun caller_cancellation_keeps_cleanup_owned_and_never_starts_a_second_reconciliation() = runTest {
        val f = RetainedRecoveryFixture()
        val first = f.seed()
        withHeldRecovery(f) { held ->
            held.request.cancel(CancellationException("retained recovery cancelled"))
            held.cleaning.await()
            assertFalse(held.request.isCompleted)
            assertNull(f.durable.ready)
            assertIs<UpdateState.Failed>(f.subject.state.value)
            finishCancelledRecoveryWithWaiter(f, held)
        }
        assertEquals(1, f.store.recoveryAdoptions)
        assertEquals(4, f.store.adoptions.size, "base A, attempted B, one recovery A, then the separate local waiter")
        assertEquals(1, f.store.commits.size)
        assertEquals(2, f.store.invalidations)
        assertEquals(first, f.durable.ready)
        assertEquals(1, f.remote.manifestFetches)
        assertEquals(1, f.remote.sourceFetches)
    }

    @Test
    fun ordinary_remote_failure_without_a_pending_replacement_stays_visible_without_recovery() = runTest {
        val f = RetainedRecoveryFixture()
        val first = f.seed()
        val failedFetch = IllegalStateException("fixture fetch failure")
        f.remote.beforeSourceFetch = { throw failedFetch }
        val error = assertIs<AppError.Unexpected>(assertIs<AppResult.Failure>(f.subject.refresh()).error)
        assertEquals(failedFetch.message, assertIs<IllegalStateException>(error.cause).message)
        assertEquals("source catalog refresh failed", error.message)
        assertEquals(UpdateState.Failed("source catalog refresh failed"), f.subject.state.value)
        assertEquals(1, f.rejected.size)
        assertEquals(first, f.durable.ready)
        assertEquals(first, f.durable.selection)
        assertEquals(1, f.store.adoptions.size)
        assertEquals(0, f.store.recoveryAdoptions)
        assertEquals(0, f.store.commits.size)
        assertEquals(0, f.store.invalidations)
        assertEquals(1, f.remote.manifestFetches)
        assertEquals(1, f.remote.sourceFetches)
    }
}

private class RetainedRecoveryFixture {
    val catalog = verifiedCatalogAt(10, "https://a.test")
    val durable = FakeCatalogStore(assertNotNull(catalog.stored))
    val store = RetainedRecoveryStore(durable)
    val remote = FakeRemote(
        SourceCatalogManifestResult.Modified(signedManifest(11, listOf(entry("floor", 11)), 10)),
        mapOf("floor" to artifact("floor", 11)),
    )
    val rejected = mutableListOf<String>()
    val subject = IncrementalSourceCatalogManager(store, FakeCatalogVerifier, SchemaOnlyValidator, remote) { rejected.add(it) }

    suspend fun seed(): CommittedSourceSelection {
        assertIs<AppResult.Success<*>>(subject.restoreLocalSelection())
        store.adoptions.clear()
        store.commits.clear()
        return assertNotNull(durable.ready)
    }

    fun assertOriginalFailure(result: AppResult<SourceConfigDocument>) {
        val error = assertIs<AppError.Unexpected>(assertIs<AppResult.Failure>(result).error)
        assertSame(store.busy, error.cause)
        assertEquals("source catalog refresh failed", error.message)
        assertEquals(UpdateState.Failed(error.message), subject.state.value)
        assertEquals(catalog.document, subject.activeDocument())
        assertEquals(1, rejected.size)
    }

    suspend fun assertOneRecovery() {
        assertEquals(1, store.recoveryAdoptions)
        assertEquals(3, store.adoptions.size)
        assertEquals(store.adoptions.first().payload, store.adoptions.last().payload)
        assertNotEquals(store.adoptions[0].payload, store.adoptions[1].payload)
        assertEquals(listOf(store.adoptions[1]), store.commits, "only the original Busy B commit was attempted")
        assertEquals(0, durable.activationCount)
        assertEquals(0, durable.bundleProjectionCount)
        assertEquals(catalog.stored, durable.readActive())
        assertEquals(10L, durable.readAcceptanceFloor()?.catalogRevision)
        assertEquals(checksum(10), durable.readAcceptanceFloor()?.checksum)
        assertEquals(1, remote.manifestFetches)
        assertEquals(1, remote.sourceFetches)
    }
}

private class RetainedRecoveryStore(val durable: FakeCatalogStore) : SourceCatalogStore by durable {
    val busy = SourceSelectionUnavailable("fixture mutation Busy")
    val adoptions = mutableListOf<VerifiedSourceSelection>()
    val commits = mutableListOf<VerifiedSourceSelection>()
    var failedRemoteCommit = false
    var recoveryAdoptions = 0
    var invalidations = 0
    var beforeRecovery: suspend (VerifiedSourceSelection) -> Unit = {}
    var afterRecovery: suspend () -> Unit = {}

    override suspend fun commitSelection(candidate: VerifiedSourceSelection, expected: SourceSelectionExpectation): CommittedSourceSelection {
        commits += candidate
        if (candidate.advancesSignedFloor) {
            failedRemoteCommit = true
            throw busy
        }
        return durable.commitSelection(candidate, expected)
    }

    override suspend fun adoptSelection(candidate: VerifiedSourceSelection, publish: (CommittedSourceSelection) -> Unit): CommittedSourceSelection? {
        adoptions += candidate
        val recovering = failedRemoteCommit
        if (recovering) { recoveryAdoptions++; beforeRecovery(candidate) }
        return durable.adoptSelection(candidate, publish).also { if (recovering) afterRecovery() }
    }

    override fun invalidateSelection() { invalidations++; durable.invalidateSelection() }
}

private suspend fun replaceAndRestoreA(store: FakeCatalogStore, a: VerifiedSourceSelection): CommittedSourceSelection {
    val other = selectionCandidate(verifiedCatalogAt(12, "https://other.test"), null, emptyList(), false)
    store.commitSelection(other, assertNotNull(store.readSelection().expected))
    return store.commitSelection(a, assertNotNull(store.readSelection().expected))
}

private suspend fun replacePayloadAtSameIdentity(store: FakeCatalogStore, a: VerifiedSourceSelection): CommittedSourceSelection {
    val rule = a.payload.rules.single().copy(currentBaseUrl = "https://foreign-payload.test")
    val foreign = a.copy(payload = a.payload.copy(rules = listOf(rule)))
    return store.commitSelection(foreign, assertNotNull(store.readSelection().expected))
}

private data class HeldRetainedRecovery(
    val request: Deferred<AppResult<SourceConfigDocument>>,
    val cleaning: CompletableDeferred<Unit>,
    val release: CompletableDeferred<Unit>,
)

private suspend fun withHeldRecovery(f: RetainedRecoveryFixture, action: suspend (HeldRetainedRecovery) -> Unit) = coroutineScope {
    val entered = CompletableDeferred<Unit>()
    val cleaning = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    f.store.beforeRecovery = {
        try { entered.complete(Unit); awaitCancellation() } finally {
            withContext(NonCancellable) { cleaning.complete(Unit); release.await() }
        }
    }
    val request = async(start = CoroutineStart.UNDISPATCHED) { f.subject.refresh() }
    try {
        entered.await()
        action(HeldRetainedRecovery(request, cleaning, release))
    } finally {
        release.complete(Unit)
        withContext(NonCancellable) { request.cancelAndJoin() }
    }
}

private suspend fun finishCancelledRecoveryWithWaiter(f: RetainedRecoveryFixture, held: HeldRetainedRecovery) = coroutineScope {
    val waiter = async(start = CoroutineStart.UNDISPATCHED) { f.subject.restoreLocalSelection() }
    try {
        assertFalse(waiter.isCompleted, "the cancelled owner's cleanup tail still holds refresh serialization")
        f.store.failedRemoteCommit = false
        held.release.complete(Unit)
        held.request.join()
        assertEquals("retained recovery cancelled", assertFailsWith<CancellationException> { held.request.await() }.message)
        assertIs<AppResult.Success<*>>(waiter.await())
    } finally {
        withContext(NonCancellable) { waiter.cancelAndJoin() }
    }
}
