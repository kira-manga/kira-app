package me.manga.kira.data.remote.complaint

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import java.io.IOException
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Checks actual bytes before HTTP, not merely a caller-supplied contentLength declaration. */
internal fun boundedComplaintMutationBody(body: RequestBody): RequestBody {
    val declared = body.contentLength()
    if (body.isDuplex() || declared !in 1L..Policy.MAX_REQUEST_BYTES.toLong()) {
        throw IOException("Complaint mutation body rejected")
    }
    val payload = Buffer()
    val sink = MutationRequestSink(payload).buffer()
    return try {
        body.writeTo(sink)
        sink.flush()
        if (payload.size != declared) throw IOException("Complaint mutation body length rejected")
        // The in-memory snapshot is sent once; no retry ever re-invokes the caller's body writer.
        OneShotSessionBody(payload.readByteArray().toRequestBody("application/json".toMediaType()))
    } finally {
        sink.buffer.clear()
        payload.clear()
    }
}

private class MutationRequestSink(
    private val payload: Buffer,
) : Sink {
    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        if (byteCount > Policy.MAX_REQUEST_BYTES - payload.size) {
            throw IOException("Complaint mutation body limit rejected")
        }
        payload.write(source, byteCount)
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun flush() = Unit

    override fun close() = Unit
}
