package me.manga.kira.platform.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.BufferedSource
import okio.IOException
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
            assertContentEquals(bytes, readAvifBytes(source, maxBytes = bytes.size))
            assertTrue(source.closed)
            assertEquals(bytes.size, source.consumed)
        }

    @Test
    fun overLimitConsumesOnlyOneSentinelByteAndClosesTheSource() =
        runTest {
            val source = RecordingSource(Buffer().write(ByteArray(100_000)))
            assertFailsWith<AvifDecodeException> { readAvifBytes(source, maxBytes = 16_384) }
            assertEquals(16_385, source.consumed)
            assertTrue(source.closed)
            assertTrue(source.largestRead <= 8192)
        }

    @Test
    fun emptyInputFailsTerminallyAndClosesTheSource() =
        runTest {
            val source = RecordingSource(Buffer())
            assertFailsWith<AvifDecodeException> { readAvifBytes(source, maxBytes = 16) }
            assertTrue(source.closed)
        }

    @Test
    fun inputFailureIsNotReplacedByADecline() =
        runTest {
            val failure = IOException("fixture read failure")
            val source = RecordingSource(Buffer().writeByte(1)).apply { afterRead = { throw failure } }
            assertSame(failure, assertFailsWith<IOException> { readAvifBytes(source, maxBytes = 16) })
            assertTrue(source.closed)
        }

    @Test
    fun cancellationFromTheSourceIsPropagatedUnchanged() =
        runTest {
            val cancellation = CancellationException("fixture cancellation")
            val source = RecordingSource(Buffer().writeByte(1)).apply { afterRead = { throw cancellation } }
            assertSame(cancellation, assertFailsWith<CancellationException> { readAvifBytes(source, maxBytes = 16) })
            assertTrue(source.closed)
        }

    @Test
    fun coroutineCancellationStopsTheReadAndClosesTheSource() =
        runTest {
            val source = RecordingSource(Buffer().write(ByteArray(32_768)))
            val job = launch(start = CoroutineStart.LAZY) { readAvifBytes(source, maxBytes = 32_768) }
            source.afterRead = { job.cancel() }
            job.start()
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(source.closed)
            assertEquals(8192, source.consumed)
        }

    private class RecordingSource(private val delegate: BufferedSource) : BufferedSource by delegate {
        var consumed = 0
        var largestRead = 0
        var closed = false
        var afterRead: () -> Unit = {}

        override fun read(sink: ByteArray, offset: Int, byteCount: Int): Int {
            largestRead = maxOf(largestRead, byteCount)
            val read = delegate.read(sink, offset, byteCount)
            if (read > 0) consumed += read
            afterRead()
            return read
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }
}
