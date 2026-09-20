@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.manga.kira.platform.download

import kotlinx.coroutines.runBlocking
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspectionPolicy
import me.manga.kira.platform.media.PageMediaTestImages
import okio.FileSystem
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Synchronous handler tests with suspended ephemeral tasks; no transfer is resumed. */
class IosBackgroundCompletionTest {
    @Test
    fun terminalNetworkErrorKeepsItsReasonWithoutInventingAPage() {
        withHarness { h ->
            val failure = NSError("kira-test-terminal", 7, null)
            h.transport.handleCompleted(h.task(0), failure)
            assertEquals(listOf(TestEvent(0, false, failure.localizedDescription)), h.events)
            assertTrue(h.inspector.paths.isEmpty())
            h.assertNoPartial()
        }
    }

    @Test
    fun throwingTerminalListenerSettlesItsTaskWithoutReopeningItOnDuplicateCompletion() {
        withHarness { h ->
            val trace = CompletionTrace()
            h.transport.setSystemCompletionHandler { trace.record("throwing") }
            val failure = IllegalStateException("terminal listener failed")
            h.transport.setListener(FailureListener { throw failure })
            val task = h.task(0)
            assertSame(failure, assertFailsWith<IllegalStateException> { h.transport.handleCompleted(task, null) })
            val reasons = mutableListOf<String?>()
            h.transport.setListener(FailureListener { reasons += it })
            // Terminal task identity is retired even when its listener throws; duplicates stay retired.
            h.transport.handleCompleted(task, null)
            assertTrue(reasons.isEmpty())
            h.transport.handleCompleted(h.task(1), null)
            assertEquals(listOf<String?>("Download completed without a page"), reasons)
            assertTrue(h.inspector.paths.isEmpty())
            h.transport.handleFinishedEvents()
            flushMainQueue()
            trace.assertNames("throwing")
        }
    }

    @Test
    fun nativePageHandoffsDrainAfterDisposalWithoutWaitingForTheNextWindow() =
        withHarness { h ->
            val trace = CompletionTrace()
            val receiver = HeldTransferListener()
            h.transport.setListener(receiver)
            h.transport.setSystemCompletionHandler { trace.record("first") }
            h.transport.handleFinishedDownload(h.task(0), h.sourceFile(PageMediaTestImages.png()).url(), h.response())
            h.transport.handleFinishedEvents()
            h.transport.setSystemCompletionHandler { trace.record("second") }
            h.transport.handleFinishedDownload(h.task(1), h.sourceFile(PageMediaTestImages.png()).url(), h.response())
            flushMainQueue()
            trace.assertNames()
            val first = receiver.pages.getValue(0)
            val second = receiver.pages.getValue(1)
            assertTrue(h.system.exists(first.page.path))
            onWorker { first.discardAndAcknowledge() }
            flushMainQueue()
            assertFalse(h.system.exists(first.page.path))
            assertTrue(h.system.exists(second.page.path))
            trace.assertNames("first")
            h.transport.handleFinishedEvents()
            onWorker { second.discardAndAcknowledge() }
            flushMainQueue()
            trace.assertNames("first", "second")
        }

    @Test
    fun eagerFailureAcknowledgementCannotBeatCallbackTemporaryDisposal() =
        withHarness { h ->
            val trace = CompletionTrace()
            h.transport.setSystemCompletionHandler { trace.record("disposed") }
            h.transport.setListener(FailureListener(afterAcknowledged = {
                h.transport.handleFinishedEvents()
                flushMainQueue() // Deliberately pump main while native finally still owns the file.
                trace.assertNames()
                assertTrue(h.system.exists(h.inspector.paths.single()))
            }) {})
            h.transport.handleFinishedDownload(h.task(0), h.sourceFile(PageMediaTestImages.html()).url(), h.response())
            flushMainQueue()
            assertFalse(h.system.exists(h.inspector.paths.single()))
            trace.assertNames("disposed")
        }

    @Test
    fun absentReceiverAndSilentCancellationReleaseOnlyTheirOwnCallbackCustody() =
        withHarness(registerListener = false) { h ->
            val trace = CompletionTrace()
            h.transport.setSystemCompletionHandler { trace.record("unreceived") }
            val task = h.task(0)
            h.transport.handleFinishedDownload(task, h.sourceFile(PageMediaTestImages.png()).url(), h.response())
            h.transport.handleCompleted(task, null)
            h.transport.handleCompleted(h.task(1), NSError(NSURLErrorDomain, NSURLErrorCancelled, null))
            h.transport.handleFinishedEvents()
            flushMainQueue()
            assertFalse(h.system.exists(h.inspector.paths.single()))
            h.assertNoPartial()
            trace.assertNames("unreceived")
        }

    @Test
    fun invalidUrlFailureIsAdmittedUntilItsReceiverAcknowledges() =
        withHarness { h ->
            val trace = CompletionTrace()
            val receiver = HeldTransferListener()
            val invalidUrl = "https://["
            assertNull(NSURL.URLWithString(invalidUrl))
            h.transport.setListener(receiver)
            h.transport.setSystemCompletionHandler { trace.record("invalid") }
            runBlocking { h.transport.enqueue(listOf(TransferRequest(1, 2, 0, invalidUrl, emptyMap(), TEST_ATTEMPT_TOKEN))) }
            h.transport.handleFinishedEvents()
            flushMainQueue()
            trace.assertNames()
            val failure = receiver.failures.single()
            assertEquals("Invalid download URL", failure.message)
            onWorker { failure.acknowledge() }
            flushMainQueue()
            trace.assertNames("invalid")
        }

    @Test
    fun failedPageIsReportedBeforeItsTemporaryIsDiscardedEvenWhenTheListenerThrows() {
        withHarness { h ->
            val failure = IllegalStateException("page listener failed")
            val source = h.sourceFile(PageMediaTestImages.html())
            h.transport.setListener(
                FailureListener {
                    assertTrue(h.system.exists(h.inspector.paths.single()), "report before finally cleanup")
                    throw failure
                },
            )
            assertSame(
                failure,
                assertFailsWith<IllegalStateException> {
                    h.transport.handleFinishedDownload(h.task(0), source.url(), h.response())
                },
            )
            assertFalse(h.system.exists(source))
            assertFalse(h.system.exists(h.inspector.paths.single()))
            h.assertNoPartial()
        }
    }

    @Test
    fun unmatchedTaskDescriptionsCannotProduceTerminalCallbacks() {
        withHarness { h ->
            val invalid = listOf(null, "1|2", "bad|2|0", "1|bad|0", "1|2|-1", "1|2|2147483648", "1|2|0|extra")
            invalid.forEachIndexed { index, description ->
                val task = h.task(index)
                task.taskDescription = description
                h.transport.handleCompleted(task, null)
            }
            assertTrue(h.events.isEmpty())
            assertTrue(h.inspector.paths.isEmpty())
        }
    }

    private inline fun withHarness(registerListener: Boolean = true, test: (TransportHarness) -> Unit) {
        val policy = PageBytePolicy()
        val harness = TransportHarness(policy, FileSystem.SYSTEM, PageInspectionPolicy(bytePolicy = policy), registerListener)
        try {
            test(harness)
        } finally {
            harness.close()
        }
    }
}

private class FailureListener(
    private val afterAcknowledged: () -> Unit = {},
    private val failed: (String?) -> Unit,
) : TransferListener {
    override fun onPageComplete(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        attemptToken: String,
        page: StagedDownloadPage,
        operation: DownloadOperationExclusion.Operation,
        acknowledge: () -> Unit,
    ) = error("completion without a published page")

    override fun onPageFailed(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        attemptToken: String,
        message: String?,
        operation: DownloadOperationExclusion.Operation,
        acknowledge: () -> Unit,
    ) {
        failed(message)
        acknowledge()
        afterAcknowledged()
    }
}
