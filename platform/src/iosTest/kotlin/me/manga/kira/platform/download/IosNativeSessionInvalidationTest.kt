@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.manga.kira.platform.download

import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspectionPolicy
import me.manga.kira.platform.media.PageMediaTestImages
import okio.FileSystem
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadTask
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real ephemeral NSURLSession cancellation/invalidation through the production delegate, with its
 * queue deliberately held before delivery. No transfer is resumed and no success receipt is injected.
 * File delivery still uses the existing handler seam; these are not background-restoration, same-ID
 * session-recreation, process-death, or Room/graph qualification tests.
 */
class IosNativeSessionInvalidationTest {
    @Test
    fun emptyCensusOnlyRequestsCloseUntilTheRealInvalidationDelegateRuns() = runTest {
        withHarness { h ->
            val recovery = DownloadOperationExclusion.recovering()
            val lifetime = IosNativeSessionLifetime(recovery.takeOperation())
            withDelegateSession(h, lifetime) { _, queue ->
                lifetime.finishCensus(emptyList())
                assertNull(lifetime.tryAccess())
                assertFalse(lifetime.isSettled())
                assertFailsWith<DownloadOperationBusy> { recovery.exclusion.withExclusive {} }
                queue.suspended = false
                lifetime.awaitInvalidation()
                assertTrue(lifetime.isSettled())
                recovery.exclusion.withExclusive {}
            }
        }
    }

    @Test
    fun duplicateAccessReleaseCannotCloseOverAnotherNativeSessionAccess() = runTest {
        withHarness { h ->
            val recovery = DownloadOperationExclusion.recovering()
            val lifetime = IosNativeSessionLifetime(recovery.takeOperation())
            withDelegateSession(h, lifetime) { _, queue ->
                val first = assertNotNull(lifetime.tryAccess())
                val second = assertNotNull(lifetime.tryAccess())
                lifetime.finishCensus(emptyList())
                first.release()
                first.release()
                assertNotNull(lifetime.tryAccess()).release()
                assertFailsWith<DownloadOperationBusy> { recovery.exclusion.withExclusive {} }
                second.release()
                assertNull(lifetime.tryAccess())
                assertFalse(lifetime.isSettled())
                queue.suspended = false
                lifetime.awaitInvalidation()
                recovery.exclusion.withExclusive {}
            }
        }
    }

    @Test
    fun nativeCancellationAndAnEmptyObservationWaitForActualTerminalThenInvalidation() = runTest {
        withHarness { h ->
            val recovery = DownloadOperationExclusion.recovering()
            val lifetime = IosNativeSessionLifetime(recovery.takeOperation())
            withDelegateSession(h, lifetime) { session, queue ->
                val task = h.task(0, session)
                lifetime.finishCensus(listOf(task))
                task.cancel()
                lifetime.observeTasks(emptyList())
                assertNotNull(lifetime.tryAccess()).release()
                assertFalse(lifetime.isSettled())
                assertFailsWith<DownloadOperationBusy> { recovery.exclusion.withExclusive {} }
                queue.suspended = false
                lifetime.awaitInvalidation()
                assertTrue(h.events.isEmpty(), "actual user cancellation remains silent")
                recovery.exclusion.withExclusive {}
            }
        }
    }

    @Test
    fun createdTaskRetainsItsOwnOperationAfterTheEnqueueAccessEnds() = runTest {
        withHarness { h ->
            val recovery = DownloadOperationExclusion.recovering()
            val lifetime = IosNativeSessionLifetime(recovery.takeOperation())
            withDelegateSession(h, lifetime) { session, queue ->
                val access = assertNotNull(lifetime.tryAccess())
                lifetime.finishCensus(emptyList())
                val task = h.task(0, session)
                val nativeOwnership = recovery.exclusion.withOperation { it.retain() }
                lifetime.registerCreatedTask(task, nativeOwnership)
                access.release()
                assertNotNull(lifetime.tryAccess()).release()
                assertFailsWith<DownloadOperationBusy> { recovery.exclusion.withExclusive {} }
                task.cancel()
                queue.suspended = false
                lifetime.awaitInvalidation()
                assertFailsWith<IllegalStateException> { nativeOwnership.retain() }
                recovery.exclusion.withExclusive {}
            }
        }
    }

    @Test
    fun immediatePageAcknowledgementDoesNotReplaceTheNativeTerminalCallback() = runTest {
        withHarness { h ->
            val recovery = DownloadOperationExclusion.recovering()
            val lifetime = IosNativeSessionLifetime(recovery.takeOperation())
            withDelegateSession(h, lifetime) { session, queue ->
                val receiver = HeldTransferListener().also(h.transport::setListener)
                val task = h.task(0, session)
                lifetime.finishCensus(listOf(task))
                stagePage(h, task, lifetime)
                receiver.pages.getValue(0).discardAndAcknowledge()
                assertNotNull(lifetime.tryAccess()).release()
                assertFailsWith<DownloadOperationBusy> { recovery.exclusion.withExclusive {} }
                task.cancel()
                queue.suspended = false
                lifetime.awaitInvalidation()
                recovery.exclusion.withExclusive {}
                h.assertNoPartial()
            }
        }
    }

    @Test
    fun heldReceiverAndItsRetainedChildOutliveActualNativeInvalidation() = runTest {
        withHarness { h ->
            val recovery = DownloadOperationExclusion.recovering()
            val lifetime = IosNativeSessionLifetime(recovery.takeOperation())
            withDelegateSession(h, lifetime) { session, queue ->
                val receiver = HeldTransferListener().also(h.transport::setListener)
                val task = h.task(0, session)
                lifetime.finishCensus(listOf(task))
                stagePage(h, task, lifetime)
                val held = receiver.pages.getValue(0)
                task.cancel()
                queue.suspended = false
                lifetime.awaitInvalidation()
                assertTrue(lifetime.isSettled())
                assertFailsWith<DownloadOperationBusy> { recovery.exclusion.withExclusive {} }
                val child = held.operation.retain()
                held.discardAndAcknowledge()
                assertFalse(h.system.exists(held.page.path))
                assertFailsWith<IllegalStateException> { held.operation.retain() }
                assertFailsWith<DownloadOperationBusy> { recovery.exclusion.withExclusive {} }
                child.release()
                recovery.exclusion.withExclusive {
                    held.acknowledge()
                    recovery.exclusion.requireExclusive()
                }
            }
        }
    }

    @Test
    fun expiredGenerationRejectsALateFileCallbackDuringASuccessorWriter() = runTest {
        withHarness { h ->
            val recovery = DownloadOperationExclusion.recovering()
            val lifetime = IosNativeSessionLifetime(recovery.takeOperation())
            withDelegateSession(h, lifetime) { _, queue ->
                lifetime.finishCensus(emptyList())
                queue.suspended = false
                lifetime.awaitInvalidation()
                val lateTask = h.task(0)
                val source = h.sourceFile(PageMediaTestImages.png())
                recovery.exclusion.withExclusive {
                    h.transport.handleFinishedDownload(lateTask, source.url(), h.response(), lifetime)
                    recovery.exclusion.requireExclusive()
                }
                assertTrue(h.system.exists(source))
                assertTrue(h.inspector.paths.isEmpty())
                assertTrue(h.events.isEmpty())
            }
        }
    }

    private inline fun withDelegateSession(
        harness: TransportHarness,
        lifetime: IosNativeSessionLifetime,
        test: (NSURLSession, NSOperationQueue) -> Unit,
    ) {
        val queue = NSOperationQueue().apply {
            suspended = true
            maxConcurrentOperationCount = 1
        }
        val session = NSURLSession.sessionWithConfiguration(
            NSURLSessionConfiguration.ephemeralSessionConfiguration,
            delegate = IosBackgroundSessionDelegate(harness.transport, lifetime),
            delegateQueue = queue,
        )
        lifetime.bindSession(session)
        try {
            test(session, queue)
        } finally {
            queue.suspended = false
            // Failure cleanup only, never the successful path's evidence of terminal/invalidation.
            if (!lifetime.isSettled()) session.invalidateAndCancel()
        }
    }

    private fun stagePage(harness: TransportHarness, task: NSURLSessionDownloadTask, lifetime: IosNativeSessionLifetime) {
        harness.transport.handleFinishedDownload(
            task, harness.sourceFile(PageMediaTestImages.png()).url(), harness.response(), lifetime,
        )
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
