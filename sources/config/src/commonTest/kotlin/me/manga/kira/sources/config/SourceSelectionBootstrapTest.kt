package me.manga.kira.sources.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceCatalogStore
import me.manga.kira.sources.contracts.SourceSelectionRead
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real manager plus controlled local store: not Room, native-return, DI, or shipping-readiness proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceSelectionBootstrapTest {
    @Test
    fun concurrent_callers_share_local_preparation_and_one_cancellation_is_isolated() = runTest {
        val f = BootstrapFixture(backgroundScope)
        try {
            assertEquals(0, f.store.reads, "construction must not start preparation")
            assertNull(f.store.durable.ready)
            f.store.beforeRead = {
                f.entered.complete(Unit)
                f.release.await()
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) { f.subject.prepareLocal() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { f.subject.prepareLocal() }
            f.entered.await()
            first.cancelAndJoin()
            assertFailsWith<CancellationException> { first.await() }
            assertFalse(second.isCompleted)
            assertEquals(1, f.store.reads)
            assertEquals(1, f.lifetime.children.count())
            f.release.complete(Unit)
            assertEquals(Unit, assertIs<AppResult.Success<*>>(second.await()).value)
            assertEquals(1, f.store.reads)
            assertEquals(1, f.store.durable.bundleProjectionCount)
        } finally {
            f.close()
        }
    }

    @Test
    fun last_waiter_cancellation_retains_closing_until_the_actual_cleanup_returns() = runTest {
        val f = BootstrapFixture(backgroundScope)
        try {
            f.holdCancelledRead()
            val first = async(start = CoroutineStart.UNDISPATCHED) { f.subject.prepareLocal() }
            f.entered.await()
            val held = f.lifetime.children.single()
            first.cancelAndJoin()
            f.cleaning.await()
            val successor = async(start = CoroutineStart.UNDISPATCHED) { f.subject.prepareLocal() }
            runCurrent()
            assertFalse(held.isCompleted)
            assertSame(
                held,
                f.lifetime.children.single(),
                "even a successor queued behind the manager would be a second flight",
            )
            assertFalse(successor.isCompleted)
            assertEquals(1, f.store.reads)
            f.finishCleanup.complete(Unit)
            assertIs<AppResult.Success<*>>(successor.await())
            assertTrue(held.isCompleted)
            assertEquals(2, f.store.reads)
        } finally {
            f.close()
        }
    }

    @Test
    fun a_shared_failure_is_unchanged_and_only_explicit_later_calls_prepare_again() = runTest {
        val f = BootstrapFixture(backgroundScope)
        try {
            val refused = SourceSelectionUnavailable("fixture local authority unavailable")
            f.store.beforeRead = { throw refused }
            val first = async(start = CoroutineStart.UNDISPATCHED) { f.subject.prepareLocal() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { f.subject.prepareLocal() }
            val failure = assertIs<AppResult.Failure>(first.await())
            assertSame(failure, second.await())
            assertSame(refused, assertIs<AppError.Unexpected>(failure.error).cause)
            runCurrent()
            assertEquals(1, f.store.reads, "a failure must not start an automatic retry")
            assertNull(f.store.durable.ready)
            f.store.beforeRead = {}
            assertIs<AppResult.Success<*>>(f.subject.prepareLocal())
            assertIs<AppResult.Success<*>>(f.subject.prepareLocal())
            assertEquals(3, f.store.reads, "successful preparation is not cached authority")
            assertEquals(1, f.store.durable.bundleProjectionCount)
        } finally {
            f.close()
        }
    }

    @Test
    fun owner_and_graph_close_reject_new_calls_but_still_own_the_running_cleanup() = runTest {
        for (closeGraph in listOf(false, true)) {
            val f = BootstrapFixture(backgroundScope)
            try {
                f.holdCancelledRead()
                val request = async(start = CoroutineStart.UNDISPATCHED) { f.subject.prepareLocal() }
                f.entered.await()
                val held = f.lifetime.children.single()
                if (closeGraph) f.graph.cancel() else f.subject.close()
                f.cleaning.await()
                assertFalse(held.isCompleted)
                assertFalse(f.lifetime.isCompleted)
                assertFailsWith<CancellationException> { f.subject.prepareLocal() }
                assertEquals(1, f.store.reads)
                f.finishCleanup.complete(Unit)
                assertFailsWith<CancellationException> { request.await() }
                f.lifetime.join()
                assertTrue(f.lifetime.isCompleted)
                assertEquals(!closeGraph, f.graph.isActive, "closing the owner must not cancel its graph")
            } finally {
                f.close()
            }
        }
    }

    @Test
    fun cancel_before_body_does_no_local_work_or_strand_a_live_owner() = runTest {
        for (closeGraph in listOf(false, true)) {
            val f = BootstrapFixture(backgroundScope)
            try {
                // Detach the caller synchronously while the graph's dispatched flight is still queued.
                val request = async(UnconfinedTestDispatcher(testScheduler)) { f.subject.prepareLocal() }
                assertEquals(0, f.store.reads)
                if (closeGraph) f.graph.cancel() else request.cancel()
                assertFailsWith<CancellationException> { request.await() }
                runCurrent()
                assertEquals(0, f.store.reads)
                assertFalse(f.lifetime.children.any())
                if (closeGraph) {
                    assertFailsWith<CancellationException> { f.subject.prepareLocal() }
                } else {
                    assertIs<AppResult.Success<*>>(f.subject.prepareLocal())
                }
                assertEquals(if (closeGraph) 0 else 1, f.store.reads)
            } finally {
                f.close()
            }
        }
    }
}

private class BootstrapFixture(parent: CoroutineScope) {
    val graph = SupervisorJob(requireNotNull(parent.coroutineContext[Job]))
    private val graphScope = CoroutineScope(parent.coroutineContext + graph)
    val store = BootstrapStore()
    private val remote = FakeRemote(SourceCatalogManifestResult.Unavailable)
    private val manager = IncrementalSourceCatalogManager(store, FakeCatalogVerifier, SchemaOnlyValidator, remote)
    val subject = SourceSelectionBootstrap(manager, graphScope)
    val lifetime = graph.children.single()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val cleaning = CompletableDeferred<Unit>()
    val finishCleanup = CompletableDeferred<Unit>()

    fun holdCancelledRead() {
        store.beforeRead = {
            if (store.reads == 1) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        finishCleanup.await()
                    }
                }
            }
        }
    }

    suspend fun close() {
        release.complete(Unit)
        finishCleanup.complete(Unit)
        subject.close()
        withContext(NonCancellable) { graph.cancelAndJoin() }
        assertEquals(0, remote.manifestFetches)
        assertEquals(0, remote.sourceFetches)
    }
}

private class BootstrapStore(val durable: FakeCatalogStore = FakeCatalogStore(null)) : SourceCatalogStore by durable {
    var reads = 0
        private set
    var beforeRead: suspend () -> Unit = {}

    override suspend fun readSelection(): SourceSelectionRead {
        reads++
        beforeRead()
        return durable.readSelection()
    }
}
