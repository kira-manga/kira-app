@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.manga.kira.platform.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspectionPolicy
import okio.FileSystem
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSinceNow
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real transport handler custody and native main-queue dispatch, with explicitly held receipts.
 * Reuses the existing unresumed-session fixture; not repository/Room settlement, actual NSURLSession
 * delivery, relaunch, or BG scheduling proof. Production callback receipt wiring is a separate join.
 */
class IosBackgroundEventDrainTest {
    @Test
    fun heldAdmissionDelaysMainCompletionAndDuplicateAcknowledgementsCannotRepeatIt() =
        withHarness { h ->
            val trace = CompletionTrace()
            val acknowledge = onWorker { h.transport.admitEvent() }
            h.transport.setSystemCompletionHandler { trace.record("first") }
            onWorker { h.transport.handleFinishedEvents() }
            flushMainQueue()
            trace.assertNames()
            onWorker { acknowledge() }
            flushMainQueue()
            trace.assertNames("first")
            onWorker {
                acknowledge()
                h.transport.handleFinishedEvents()
            }
            flushMainQueue()
            trace.assertNames("first")
        }

    @Test
    fun finishedBatchExcludesFutureEventsAndCannotConsumeTheirHandler() =
        withHarness { h ->
            val trace = CompletionTrace()
            val first = onWorker { h.transport.admitEvent() }
            h.transport.setSystemCompletionHandler { trace.record("first") }
            onWorker { h.transport.handleFinishedEvents() }
            val second = onWorker { h.transport.admitEvent() }
            h.transport.setSystemCompletionHandler { trace.record("second") }
            onWorker { first() }
            flushMainQueue()
            trace.assertNames("first")
            onWorker { h.transport.handleFinishedEvents() }
            flushMainQueue()
            trace.assertNames("first")
            onWorker {
                second()
                second()
            }
            flushMainQueue()
            trace.assertNames("first", "second")
        }

    @Test
    fun handlerReplacementPreservesBothOwnersAndDuplicateFinishDoesNotCloseTheNextWindow() =
        withHarness { h ->
            val trace = CompletionTrace()
            val acknowledge = onWorker { h.transport.admitEvent() }
            h.transport.setSystemCompletionHandler { trace.record("original") }
            h.transport.setSystemCompletionHandler { trace.record("replacement") }
            onWorker {
                h.transport.handleFinishedEvents()
                h.transport.handleFinishedEvents()
            }
            flushMainQueue()
            trace.assertNames()
            onWorker { acknowledge() }
            flushMainQueue()
            trace.assertNames("original", "replacement")
            h.transport.setSystemCompletionHandler { trace.record("next") }
            flushMainQueue()
            trace.assertNames("original", "replacement")
            onWorker { h.transport.handleFinishedEvents() }
            flushMainQueue()
            trace.assertNames("original", "replacement", "next")
        }

    @Test
    fun lateHandlerWaitsForItsAlreadyFinishedUnownedBatch() =
        withHarness { h ->
            val trace = CompletionTrace()
            val acknowledge = onWorker { h.transport.admitEvent() }
            onWorker { h.transport.handleFinishedEvents() }
            h.transport.setSystemCompletionHandler { trace.record("late") }
            flushMainQueue()
            trace.assertNames()
            onWorker {
                acknowledge()
                acknowledge()
            }
            flushMainQueue()
            trace.assertNames("late")
        }

    @Test
    fun newAdmissionInvalidatesAnUnclaimedFinishInsteadOfReleasingTheHostEarly() =
        withHarness { h ->
            val trace = CompletionTrace()
            val first = onWorker { h.transport.admitEvent() }
            onWorker {
                h.transport.handleFinishedEvents()
                first()
            }
            val second = onWorker { h.transport.admitEvent() }
            h.transport.setSystemCompletionHandler { trace.record("current") }
            onWorker { second() }
            flushMainQueue()
            trace.assertNames()
            onWorker { h.transport.handleFinishedEvents() }
            flushMainQueue()
            trace.assertNames("current")
        }

    @Test
    fun concurrentAcknowledgementsAndNativeFinishConsumeOneMainCompletion() =
        withHarness { h ->
            val trace = CompletionTrace()
            val first = onWorker { h.transport.admitEvent() }
            val second = onWorker { h.transport.admitEvent() }
            h.transport.setSystemCompletionHandler { trace.record("raced") }
            runBlocking(Dispatchers.Default) {
                launch {
                    first()
                    first()
                }
                launch {
                    second()
                    second()
                }
                launch { h.transport.handleFinishedEvents() }
            }
            flushMainQueue()
            trace.assertNames("raced")
        }

    private inline fun withHarness(test: (TransportHarness) -> Unit) {
        val policy = PageBytePolicy()
        val harness = TransportHarness(policy, FileSystem.SYSTEM, PageInspectionPolicy(bytePolicy = policy))
        try {
            test(harness)
        } finally {
            harness.close()
        }
    }
}

internal class CompletionTrace {
    private val calls = MutableStateFlow<List<Pair<String, Boolean>>>(emptyList())

    fun record(name: String) {
        val onMain = NSThread.isMainThread
        calls.update { it + (name to onMain) }
    }

    fun assertNames(vararg names: String) {
        val snapshot = calls.value
        assertEquals(names.toList(), snapshot.map { it.first })
        assertTrue(snapshot.all { it.second }, "every system completion must run on the native main thread")
    }
}

internal fun <T> onWorker(block: () -> T): T =
    runBlocking(Dispatchers.Default) {
        assertFalse(NSThread.isMainThread, "exercise worker/delegate to native main dispatch")
        block()
    }

/** Pump the actual main queue, including a sentinel after all preceding completion submissions. */
internal fun flushMainQueue() {
    val reached = MutableStateFlow(false)
    dispatch_async(dispatch_get_main_queue()) { reached.value = true }
    val deadline = NSDate.dateWithTimeIntervalSinceNow(MAIN_QUEUE_TIMEOUT_SECONDS)
    while (!reached.value && deadline.timeIntervalSinceNow > 0) {
        NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(MAIN_QUEUE_TICK_SECONDS))
    }
    assertTrue(reached.value, "native test runner did not service the main queue")
}

private const val MAIN_QUEUE_TIMEOUT_SECONDS = 5.0
private const val MAIN_QUEUE_TICK_SECONDS = 0.01
