@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.manga.kira.platform.download

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspectionPolicy
import me.manga.kira.platform.media.PageMediaTestImages
import okio.FileSystem
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lifetime algorithm and handler fault controls with suspended native tasks. No transfer is resumed.
 * Bound sessions here have no delegate: manually injected invalidation is only a negative control,
 * never evidence of successful native drain. Actual delegate delivery is covered separately.
 */
class IosNativeSessionLifetimeTest {
    @Test
    fun initialReservationBlocksSelectionBeforeProductionSessionOrStagingCapture() = runTest {
        withHarness { h ->
            assertTrue(h.inspector.paths.isEmpty())
            assertFailsWith<DownloadOperationBusy> { h.operationExclusion.withExclusive {} }
            h.operationExclusion.withOperation {} // Recovery/cancellation is still admitted.
            assertFailsWith<DownloadOperationBusy> { h.operationExclusion.withExclusive {} }
        }
    }

    @Test
    fun lateFileCallbackCannotReopenATerminalTaskOrAdoptItsTemporary() = runTest {
        withHarness { h ->
            val task = h.task(0)
            h.transport.handleCompleted(task, NSError(NSURLErrorDomain, NSURLErrorCancelled, null))
            val source = h.sourceFile(PageMediaTestImages.png())
            h.transport.handleFinishedDownload(task, source.url(), h.response())
            h.transport.handleCompleted(task, null)
            assertTrue(h.system.exists(source), "late callback bytes remain OS-owned")
            assertTrue(h.events.isEmpty())
            assertTrue(h.inspector.paths.isEmpty())
            h.assertNoPartial()
        }
    }

    @Test
    fun cancelledRecoveredTaskAndMissingSnapshotDoNotReplaceItsTerminalReceipt() = runTest {
        withHarness { h ->
            withBoundLifetime { gate, lifetime ->
                val task = h.task(0)
                lifetime.finishCensus(listOf(task))
                task.cancel()
                lifetime.observeTasks(emptyList())
                assertNotNull(lifetime.tryAccess()).release()
                assertNotNull(lifetime.beginCallback(task)).release()
                assertFailsWith<IllegalStateException> { lifetime.finishTask(task) }
                assertFalse(lifetime.isSettled())
                assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
            }
        }
    }

    @Test
    fun duplicateTerminalCannotRetireAnotherRecoveredTask() = runTest {
        withHarness { h ->
            withBoundLifetime { gate, lifetime ->
                val first = h.task(0)
                val second = h.task(1)
                lifetime.finishCensus(listOf(first, second))
                finishTask(lifetime, first)
                assertNull(lifetime.beginCallback(first, terminal = true))
                lifetime.finishTask(first)
                assertNotNull(lifetime.tryAccess()).release()
                assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
                finishTask(lifetime, second)
                assertNull(lifetime.tryAccess(), "only the second terminal receipt permits closing")
                assertFalse(lifetime.isSettled(), "requesting invalidation is not its delegate receipt")
                assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
            }
        }
    }

    @Test
    fun cancelledInvalidationWaiterDoesNotReleaseTheNativeReservation() = runTest {
        withBoundLifetime { gate, lifetime ->
            lifetime.finishCensus(emptyList())
            val waiter = launch { lifetime.awaitInvalidation() }
            runCurrent()
            waiter.cancelAndJoin()
            assertFalse(lifetime.isSettled())
            assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
        }
    }

    @Test
    fun invalidationWithoutAllTerminalReceiptsFailsClosedEvenAfterADuplicateSuccess() = runTest {
        withHarness { h ->
            withBoundLifetime { gate, lifetime ->
                val task = h.task(0)
                lifetime.finishCensus(listOf(task))
                lifetime.didBecomeInvalid(null) // Fault injection: unsolicited, missing terminal receipt.
                assertFailsWith<IllegalStateException> { lifetime.awaitInvalidation() }
                lifetime.didBecomeInvalid(null)
                assertNull(lifetime.beginCallback(task, terminal = true))
                assertFalse(lifetime.isSettled())
                assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
            }
        }
    }

    @Test
    fun nativeInvalidationErrorCannotPromoteAnEmptyCensusIntoSuccessfulDrain() = runTest {
        withBoundLifetime { gate, lifetime ->
            lifetime.finishCensus(emptyList())
            assertNull(lifetime.tryAccess())
            lifetime.didBecomeInvalid(NSError("kira-test-invalidation", 1, null))
            assertFailsWith<IllegalStateException> { lifetime.awaitInvalidation() }
            lifetime.didBecomeInvalid(null)
            assertFalse(lifetime.isSettled())
            assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
        }
    }

    @Test
    fun eventAcknowledgementsCannotReleaseNativeTaskSiblingOrSuccessorOwnership() = runTest {
        val gate = DownloadOperationExclusion()
        val nativeTask = gate.withOperation { it.retain() }
        val completed = mutableListOf<Int>()
        val events = List(2) { index -> IosTransferEvent(nativeTask) { completed += index } }
        val acknowledgements = mutableListOf<() -> Unit>()
        events.forEach { event ->
            event.deliver { _, acknowledge -> acknowledgements += acknowledge }
            event.finishDelivery()
        }
        acknowledgements[0]()
        acknowledgements[0]()
        assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
        nativeTask.release()
        assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
        acknowledgements[1]()
        assertEquals(listOf(0, 1), completed)
        gate.withExclusive {
            acknowledgements.forEach { it() }
            gate.requireExclusive()
        }
        val successor = gate.withOperation { it.retain() }
        events.forEach { it.finishDelivery() }
        acknowledgements.forEach { it() }
        assertFailsWith<DownloadOperationBusy> { gate.withExclusive {} }
        successor.release()
        gate.withExclusive {}
    }

    private fun finishTask(lifetime: IosNativeSessionLifetime, task: NSURLSessionTask) {
        assertNotNull(lifetime.beginCallback(task, terminal = true)).release()
        lifetime.finishTask(task)
    }

    private inline fun withBoundLifetime(
        test: (DownloadOperationExclusion, IosNativeSessionLifetime) -> Unit,
    ) {
        val recovery = DownloadOperationExclusion.recovering()
        val lifetime = IosNativeSessionLifetime(recovery.takeOperation())
        val session = NSURLSession.sessionWithConfiguration(
            NSURLSessionConfiguration.ephemeralSessionConfiguration, delegate = null, delegateQueue = null,
        )
        lifetime.bindSession(session)
        try {
            test(recovery.exclusion, lifetime)
        } finally {
            session.invalidateAndCancel() // Test cleanup only; never a lifetime settlement receipt.
        }
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
