@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.manga.kira.platform.download

import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspectionPolicy
import me.manga.kira.platform.media.PageMediaTestImages
import okio.FileSystem
import platform.Foundation.NSError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun throwingTerminalListenerStillReleasesThePerTaskOutcome() {
        withHarness { h ->
            val failure = IllegalStateException("terminal listener failed")
            h.transport.setListener(FailureListener { throw failure })
            val task = h.task(0)
            assertSame(failure, assertFailsWith<IllegalStateException> { h.transport.handleCompleted(task, null) })
            val reasons = mutableListOf<String?>()
            h.transport.setListener(FailureListener { reasons += it })
            // A fresh completion for this identifier must not find the old reported marker.
            h.transport.handleCompleted(task, null)
            assertEquals(listOf<String?>("Download completed without a page"), reasons)
            assertTrue(h.inspector.paths.isEmpty())
        }
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

private class FailureListener(
    private val failed: (String?) -> Unit,
) : TransferListener {
    override fun onPageComplete(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
    ) = error("completion without a published page")

    override fun onPageFailed(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        message: String?,
    ) {
        failed(message)
    }
}
