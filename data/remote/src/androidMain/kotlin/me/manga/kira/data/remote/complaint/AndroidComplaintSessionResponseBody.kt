package me.manga.kira.data.remote.complaint

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import kotlin.concurrent.Volatile

internal class AndroidComplaintSessionResponseBody(
    private val delegate: ResponseBody,
    budget: ComplaintReceiveBudget,
    cancelCall: () -> Unit,
) : ResponseBody() {
    private val bounded = SessionResponseSource(delegate.source(), budget, cancelCall).buffer()

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = bounded
}

private class SessionResponseSource(
    private val upstream: BufferedSource,
    private val budget: ComplaintReceiveBudget,
    private val cancelCall: () -> Unit,
) : Source {
    @Volatile
    private var complete = false

    @Volatile
    private var closed = false

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (closed) throw IOException("Complaint session response closed")
        require(byteCount >= 0)
        return when {
            byteCount == 0L -> 0
            complete -> -1
            else -> readNext(sink, byteCount)
        }
    }

    private fun readNext(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        val scratch = Buffer()
        try {
            val requested = minOf(byteCount, budget.remainingBytes + 1L, SCRATCH_BYTES)
            val received = upstream.read(scratch, requested)
            if (closed) throw IOException("Complaint session response closed")
            if (received < 0) {
                if (!budget.isComplete()) reject()
                complete = true
                return -1
            }
            if (!budget.accept(received.toULong())) reject()
            sink.write(scratch, received)
            return received
        } finally {
            // Only the reader touches this buffer; asynchronous close must not clear an in-use Buffer.
            scratch.clear()
        }
    }

    override fun timeout(): Timeout = upstream.timeout()

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (!complete) cancelCall()
        } finally {
            upstream.close()
        }
    }

    private fun reject(): Nothing {
        close()
        throw IOException("Complaint session response limit rejected")
    }

    private companion object {
        const val SCRATCH_BYTES = 8_192L
    }
}
