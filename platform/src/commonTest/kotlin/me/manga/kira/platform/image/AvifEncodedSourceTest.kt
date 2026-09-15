package me.manga.kira.platform.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.IOException
import okio.Source
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AvifEncodedSourceTest {
    @Test
    fun exactEncodedLimitSucceedsAndClosesTheSource() =
        runTest {
            val bytes = ByteArray(17) { it.toByte() }
            val source = RecordingSource(Buffer().write(bytes))
            assertContentEquals(bytes, readAvifBytes(source.buffered, maxBytes = bytes.size))
            assertTrue(source.closed)
            assertEquals(bytes.size.toLong(), source.consumed)
        }

    @Test
    fun overLimitConsumesOnlyOneSentinelByteAndClosesTheSource() =
        runTest {
            val source = RecordingSource(Buffer().write(ByteArray(100_000)))
            assertFailsWith<AvifDecodeException> { readAvifBytes(source.buffered, maxBytes = 16_384) }
            assertEquals(16_385L, source.consumed)
            assertTrue(source.closed)
            assertTrue(source.largestUpstreamRead <= 8192L)
        }

    @Test
    fun emptyInputFailsTerminallyAndClosesTheSource() =
        runTest {
            val source = RecordingSource(Buffer())
            assertFailsWith<AvifDecodeException> { readAvifBytes(source.buffered, maxBytes = 16) }
            assertTrue(source.closed)
        }

    @Test
    fun inputFailureIsNotReplacedByADecline() =
        runTest {
            val failure = IOException("fixture read failure")
            val source = RecordingSource(Buffer().writeByte(1)).apply { afterUpstreamRead = { throw failure } }
            assertSame(failure, assertFailsWith<IOException> { readAvifBytes(source.buffered, maxBytes = 16) })
            assertTrue(source.closed)
        }

    @Test
    fun cancellationFromTheSourceIsPropagatedUnchanged() =
        runTest {
            val cancellation = CancellationException("fixture cancellation")
            val source = RecordingSource(Buffer().writeByte(1)).apply { afterUpstreamRead = { throw cancellation } }
            assertSame(
                cancellation,
                assertFailsWith<CancellationException> { readAvifBytes(source.buffered, maxBytes = 16) },
            )
            assertTrue(source.closed)
        }

    @Test
    fun coroutineCancellationStopsTheReadAndClosesTheSource() =
        runTest {
            val source = RecordingSource(Buffer().write(ByteArray(32_768)))
            val job = launch(start = CoroutineStart.LAZY) { readAvifBytes(source.buffered, maxBytes = 32_768) }
            source.afterUpstreamRead = { job.cancel() }
            job.start()
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(source.closed)
            assertEquals(8192L, source.consumed)
        }

    private class RecordingSource(
        delegate: Source,
    ) : ForwardingSource(delegate) {
        val buffered: BufferedSource = buffer()
        private var upstreamBytes = 0L
        var consumed = 0L
        var largestUpstreamRead = 0L
        var closed = false
        var afterUpstreamRead: () -> Unit = {}

        override fun read(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            largestUpstreamRead = maxOf(largestUpstreamRead, byteCount)
            val read = super.read(sink, byteCount)
            if (read > 0) upstreamBytes += read
            afterUpstreamRead()
            return read
        }

        override fun close() {
            if (closed) return
            // Okio closes its upstream before clearing unread prefetched bytes.
            // Count bytes consumed by the reader, not bytes prefetched by Okio.
            consumed = upstreamBytes - buffered.buffer.size
            closed = true
            super.close()
        }
    }
}
