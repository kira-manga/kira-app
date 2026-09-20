package me.manga.kira.sources.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.identity.SourceAliasReadiness
import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The real provider/owning-writer seam, not a fake readiness flow or a production P2/DI qualification. */
class EffectiveSourceSelectionReadinessRecoveryTest {
    @Test
    fun same_token_readoption_rechecks_after_coalesced_unavailability() = runTest {
        withContext(Dispatchers.Default) {
            EffectiveSourceSelectionRoomFixture().use { f ->
                assertIs<AppResult.Success<*>>(f.manager().refresh())
                val candidate = f.prepared.last()
                val before = f.state()
                val token = assertNotNull(f.store.readSelection().selection).token
                val ready = SourceAliasReadiness.Ready(token)
                val dispatcher = DeferredReadinessDispatcher()
                val emissions = mutableListOf<SourceAliasReadiness>()
                val reads = mutableListOf<SourceAliasSnapshot>()
                val transitions = mutableListOf<ProcessSourceSelectionTransition>()
                // Watch the same production StateFlow, so a queued initial Room emission cannot
                // masquerade as recovery when the process transition itself was equality-conflated.
                val processCollector = launch(dispatcher) { f.processTransitions.collect { transitions += it } }
                val collector = launch(dispatcher) {
                    f.provider.readiness.collect { emission ->
                        emissions += emission
                        reads += f.writer { f.provider.readInTransaction() }
                    }
                }
                try {
                    dispatcher.runUntil {
                        transitions.size == 1 && emissions.lastOrNull() == ready && reads.size == emissions.size
                    }
                    val emittedBefore = emissions.size
                    val readsBefore = reads.size
                    val durableEmissionsBefore = f.durableInvalidationsSeen
                    assertTrue(durableEmissionsBefore >= 2, "both real DAO invalidation flows must be primed")
                    assertEquals(token, assertNotNull(transitions.single().binding).receipt.token)

                    // Do not pump this dispatcher again until both transitions finish. Even the
                    // provider's internal StateFlow collector is deferred, not just its downstream sink.
                    f.store.invalidateSelection()
                    assertFailsWith<SourceSelectionUnavailable> { f.writer { f.provider.readInTransaction() } }
                    assertEquals(token, assertNotNull(f.store.readSelection().selection).token)
                    assertEquals(token, assertNotNull(f.store.adoptSelection(candidate) {}).token)
                    assertEquals(before, f.state())
                    assertEquals(emittedBefore, emissions.size)
                    assertEquals(readsBefore, reads.size)
                    assertEquals(1, transitions.size)

                    // Only the final process transition may rescue the failed owning-writer read:
                    // no selection/allocator write, fake invalidation or later unrelated DB mutation.
                    dispatcher.runUntil { transitions.size > 1 && reads.size > readsBefore }
                    assertEquals(2, transitions.size)
                    assertEquals(transitions.first().binding, transitions.last().binding)
                    assertEquals(listOf(ready), emissions.drop(emittedBefore))
                    assertEquals(token, reads.last().token)
                    assertEquals(durableEmissionsBefore, f.durableInvalidationsSeen)
                    assertEquals(before, f.state())
                } finally {
                    withContext(NonCancellable) {
                        collector.cancel()
                        processCollector.cancel()
                        dispatcher.runUntil { collector.isCompleted && processCollector.isCompleted }
                        dispatcher.close()
                    }
                }
            }
        }
    }
}

/** Manual continuation queue: Room can complete real I/O, but readiness cannot advance without a pump. */
private class DeferredReadinessDispatcher : CoroutineDispatcher() {
    private val pending = Channel<Runnable>(Channel.UNLIMITED)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        check(pending.trySend(block).isSuccess)
    }

    suspend fun runUntil(done: () -> Boolean) = withTimeout(5_000) {
        while (!done()) pending.receive().run()
    }

    fun close() {
        while (true) {
            val next = pending.tryReceive().getOrNull() ?: break
            next.run()
        }
        pending.close()
    }
}
