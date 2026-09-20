package me.manga.kira.data.remote.complaint

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import java.io.IOException
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

/** Separate fixed 4KiB snapshot; normal mutation's larger request allowance cannot apply here. */
internal fun boundedComplaintDeletionBody(body: RequestBody): RequestBody {
    val declared = body.contentLength()
    if (body.isDuplex() || declared !in 1L..Policy.MAX_REQUEST_BYTES.toLong()) {
        throw IOException("Complaint deletion body rejected")
    }
    val payload = Buffer()
    val sink = DeletionRequestSink(payload).buffer()
    return try {
        body.writeTo(sink)
        sink.flush()
        if (payload.size != declared) throw IOException("Complaint deletion body length rejected")
        OneShotSessionBody(payload.readByteArray().toRequestBody("application/json".toMediaType()))
    } finally {
        sink.buffer.clear()
        payload.clear()
    }
}

private class DeletionRequestSink(
    private val payload: Buffer,
) : Sink {
    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        if (byteCount > Policy.MAX_REQUEST_BYTES - payload.size) {
            throw IOException("Complaint deletion body limit rejected")
        }
        payload.write(source, byteCount)
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun flush() = Unit

    override fun close() = Unit
}
