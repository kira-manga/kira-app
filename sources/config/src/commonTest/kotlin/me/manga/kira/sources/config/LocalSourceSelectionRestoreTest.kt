package me.manga.kira.sources.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.CommittedSourceSelection
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Local-entry routing and coroutine ownership only; fake storage is not Room or native authority. */
class LocalSourceSelectionRestoreTest {
    @Test
    fun cold_exact_signed_selection_restores_without_remote_or_commit() =
        runTest {
            val catalog = verifiedCatalogAt(10, "https://current.test")
            val store = FakeCatalogStore(assertNotNull(catalog.stored))
            val durable = seedLocalRestoreSelection(store)
            val generation = store.nextGeneration
            val historicalReads = store.historicalReads
            store.invalidateSelection()
            var commits = 0
            store.beforeCommit = {
                commits++
                throw SourceSelectionUnavailable("migration denied")
            }
            val remote = FakeRemote(SourceCatalogManifestResult.Unavailable)
            val subject = manager(store, remote)

            val restored = assertIs<AppResult.Success<*>>(subject.restoreLocalSelection())

            assertEquals(catalog.document, restored.value)
            assertEquals(durable, store.selection)
            assertEquals(durable, store.ready)
            assertEquals(generation, store.nextGeneration)
            assertEquals(0, commits)
            assertEquals(0, store.activationCount)
            assertEquals(0, store.bundleProjectionCount)
            assertTrue(store.historicalReads > historicalReads, "cold restore must revisit signed evidence")
            assertNoLocalRestoreHttp(remote)
        }

    @Test
    fun missing_selection_cannot_restore_through_a_denied_commit() =
        runTest {
            val store = FakeCatalogStore(storedCatalog(10, listOf(entry("floor", 1) to artifact("floor", 1))))
            seedLocalRestoreSelection(store)
            val generation = store.nextGeneration
            val floor = store.readAcceptanceFloor()
            store.loseSelectedPayload()
            var commits = 0
            store.beforeCommit = {
                commits++
                throw SourceSelectionUnavailable("migration denied")
            }
            val remote = FakeRemote(SourceCatalogManifestResult.Unavailable)
            val subject = manager(store, remote)

            assertIs<AppResult.Failure>(subject.restoreLocalSelection())

            assertEquals(1, commits)
            assertNull(store.selection)
            assertNull(store.ready)
            assertEquals(generation, store.nextGeneration)
            assertEquals(floor, store.readAcceptanceFloor())
            assertEquals(BUNDLED_REVISION, subject.activeDocument().revision)
            assertIs<UpdateState.Failed>(subject.state.value)
            assertNoLocalRestoreHttp(remote)
        }

    @Test
    fun local_waiter_cancellation_cannot_release_the_refresh_lock() =
        runTest {
            val store = FakeCatalogStore(storedCatalog(10, listOf(entry("floor", 1) to artifact("floor", 1))))
            val remote =
                FakeRemote(
                    SourceCatalogManifestResult.Modified(signedManifest(11, listOf(entry("floor", 2)), 10)),
                    mapOf("floor" to artifact("floor", 2)),
                )
            val subject = manager(store, remote)
            assertIs<AppResult.Success<*>>(subject.restoreLocalSelection())
            assertNoLocalRestoreHttp(remote)

            withHeldLocalRestoreRefresh(subject, remote) { held ->
                checkQueuedLocalRestores(subject, store, held)
            }

            assertEquals(11, subject.activeDocument().revision)
            assertEquals(store.selection, store.ready)
            assertEquals(1, remote.manifestFetches)
            assertEquals(1, remote.sourceFetches)
        }

    @Test
    fun cancelled_local_commit_reconciles_then_rethrows_without_remote() =
        runTest {
            val store = FakeCatalogStore(storedCatalog(10, listOf(entry("floor", 1) to artifact("floor", 1))))
            val remote = FakeRemote(SourceCatalogManifestResult.Unavailable)
            val subject = manager(store, remote)
            val committed = CompletableDeferred<Unit>()
            store.afterCommit = {
                committed.complete(Unit)
                awaitCancellation()
            }
            val request = async(start = CoroutineStart.UNDISPATCHED) { subject.restoreLocalSelection() }
            try {
                committed.await()
                val durable = assertNotNull(store.selection)
                assertNull(store.ready)
                assertEquals(BUNDLED_REVISION, subject.activeDocument().revision)
                cancelLocalRestore(request, "local commit cancellation")
                assertEquals(durable, store.ready)
                assertEquals(UpdateState.Active(10, UpdateState.Origin.CACHE), subject.state.value)
                assertEquals(10, subject.activeDocument().revision)
                store.afterCommit = {}
                assertIs<AppResult.Success<*>>(subject.restoreLocalSelection())
                assertEquals(durable, store.ready)
                assertNoLocalRestoreHttp(remote)
            } finally {
                withContext(NonCancellable) { request.cancelAndJoin() }
            }
        }
}

private suspend fun seedLocalRestoreSelection(store: FakeCatalogStore): CommittedSourceSelection {
    assertIs<AppResult.Success<*>>(manager(store, FakeRemote(SourceCatalogManifestResult.Unavailable)).refresh())
    return assertNotNull(store.ready)
}

private fun assertNoLocalRestoreHttp(remote: FakeRemote) {
    assertEquals(0, remote.manifestFetches)
    assertEquals(0, remote.sourceFetches)
}

private data class HeldLocalRestoreRefresh(
    val release: CompletableDeferred<Unit>,
    val request: Deferred<AppResult<SourceConfigDocument>>,
)

private suspend fun withHeldLocalRestoreRefresh(
    subject: IncrementalSourceCatalogManager,
    remote: FakeRemote,
    block: suspend (HeldLocalRestoreRefresh) -> Unit,
) = coroutineScope {
    val fetching = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    remote.beforeSourceFetch = {
        fetching.complete(Unit)
        release.await()
    }
    val request = async(start = CoroutineStart.UNDISPATCHED) { subject.refresh() }
    try {
        fetching.await()
        block(HeldLocalRestoreRefresh(release, request))
    } finally {
        release.complete(Unit)
        withContext(NonCancellable) { request.cancelAndJoin() }
    }
}

private suspend fun checkQueuedLocalRestores(
    subject: IncrementalSourceCatalogManager,
    store: FakeCatalogStore,
    held: HeldLocalRestoreRefresh,
) = coroutineScope {
    val ready = store.ready
    val cancelled = async(start = CoroutineStart.UNDISPATCHED) { subject.restoreLocalSelection() }
    val successor = async(start = CoroutineStart.UNDISPATCHED) { subject.restoreLocalSelection() }
    try {
        assertFalse(successor.isCompleted)
        cancelLocalRestore(cancelled, "local waiter cancellation")
        assertEquals(ready, store.ready)
        assertFalse(held.request.isCompleted)
        assertFalse(successor.isCompleted)
        held.release.complete(Unit)
        assertIs<AppResult.Success<*>>(held.request.await())
        assertIs<AppResult.Success<*>>(successor.await())
    } finally {
        withContext(NonCancellable) {
            cancelled.cancelAndJoin()
            successor.cancelAndJoin()
        }
    }
}

private suspend fun cancelLocalRestore(request: Deferred<AppResult<SourceConfigDocument>>, message: String) {
    assertFalse(request.isCompleted)
    request.cancel(CancellationException(message))
    request.join()
    val failure = assertFailsWith<CancellationException> { request.await() }
    assertEquals(message, failure.message)
}
