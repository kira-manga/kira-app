package me.manga.kira.core.cbz

import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals

internal enum class CbzFault {
    MISSING_SOURCE,
    CORRUPT_SOURCE,
    NULL_REGION,
    REGION_CLOSE,
    REGION_CLOSE_DURING_OOM,
    ENCODE_FALSE,
    EMPTY_PAYLOAD,
    ENCODE_EXCEPTION,
    ENCODE_OOM,
    ENCODE_CANCEL,
    OPEN,
    WRITE,
    CLOSE,
    CENTRAL_DIRECTORY,
    CRC,
    MOVE,
    PROGRESS,
    ;

    val regional: Boolean get() = this in setOf(NULL_REGION, REGION_CLOSE, REGION_CLOSE_DURING_OOM)

    fun failure(): Throwable =
        when (this) {
            ENCODE_OOM, REGION_CLOSE_DURING_OOM -> OutOfMemoryError("synthetic App64 OOM")
            ENCODE_CANCEL -> CancellationException("synthetic App64 cancellation")
            else -> IOException("synthetic App64 $name")
        }
}

internal fun cbzFaultManager(
    fixture: CbzHostFixture,
    decoder: CbzImageDecoder,
    fault: CbzFault,
    failure: Throwable,
): OptimizedCbzManager =
    OptimizedCbzManager(
        fixture.context,
        cbzTier(),
        decoder,
        FaultArchiveOutput(fault, failure),
        encode = { bitmap, format, quality, output ->
            when (fault) {
                CbzFault.ENCODE_FALSE -> false
                CbzFault.EMPTY_PAYLOAD -> true
                CbzFault.ENCODE_EXCEPTION,
                CbzFault.ENCODE_OOM,
                CbzFault.ENCODE_CANCEL,
                CbzFault.REGION_CLOSE_DURING_OOM,
                -> throw failure
                else -> bitmap.compress(format, quality, output)
            }
        },
    )

/** Real sink for all successful operations; faults are confined to the selected boundary. */
private class FaultArchiveOutput(
    private val fault: CbzFault,
    private val failure: Throwable,
) : CbzArchiveOutput() {
    override fun open(temporary: File): OutputStream {
        if (fault == CbzFault.OPEN) throw failure
        return object : FilterOutputStream(super.open(temporary)) {
            private val closed = AtomicBoolean()
            private val writeFailed = AtomicBoolean()

            override fun write(
                bytes: ByteArray,
                offset: Int,
                count: Int,
            ) {
                if (fault == CbzFault.WRITE && writeFailed.compareAndSet(false, true)) throw failure
                out.write(bytes, offset, count)
            }

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                super.close()
                when (fault) {
                    CbzFault.CLOSE -> throw failure
                    CbzFault.CENTRAL_DIRECTORY ->
                        RandomAccessFile(temporary, "rw").use { it.setLength(it.length() - 22L) }
                    CbzFault.CRC -> corruptCentralCrc(temporary)
                    else -> Unit
                }
            }
        }
    }

    override fun publish(
        temporary: File,
        destination: File,
    ) {
        if (fault == CbzFault.MOVE) throw failure
        super.publish(temporary, destination)
    }
}

private fun corruptCentralCrc(file: File) {
    // Tiny fixed test ZIP only. Change CENCRC, leaving a readable central directory and payload.
    RandomAccessFile(file, "rw").use {
        // ZIPs produced here have no comment: ENDOFF is six bytes before EOF.
        it.seek(it.length() - 6L)
        val start = Integer.reverseBytes(it.readInt()).toLong() and 0xffff_ffffL
        it.seek(start)
        assertEquals(0x504b0102, it.readInt())
        it.seek(start + 16L)
        val old = it.read()
        it.seek(start + 16L)
        it.write(old xor 0xff)
    }
}
