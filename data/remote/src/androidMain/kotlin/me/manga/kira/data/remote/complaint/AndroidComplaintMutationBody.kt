package me.manga.kira.data.remote.complaint

import okhttp3.MediaType
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
    return mutationBodySnapshot(body, declared, Policy.MAX_REQUEST_BYTES, "application/json".toMediaType())
}

/** The held NoContent converter supplies a known zero-length, null-media writer, never an unknown stream. */
internal fun boundedComplaintOwnerDeleteBody(body: RequestBody): RequestBody {
    if (body.isDuplex() || body.contentType() != null || body.contentLength() != 0L) {
        throw IOException("Complaint owner-delete body rejected")
    }
    return mutationBodySnapshot(body, 0, 0, null)
}

private fun mutationBodySnapshot(
    body: RequestBody,
    declared: Long,
    maximum: Int,
    media: MediaType?,
): RequestBody {
    val payload = Buffer()
    val checked = MutationRequestSink(payload, maximum)
    val sink = checked.buffer()
    return try {
        body.writeTo(sink)
        sink.flush()
        checked.requireValid()
        if (payload.size != declared) throw IOException("Complaint mutation body length rejected")
        // The in-memory snapshot is sent once; no retry ever re-invokes the caller's body writer.
        OneShotSessionBody(payload.readByteArray().toRequestBody(media))
    } finally {
        sink.buffer.clear()
        payload.clear()
    }
}

private class MutationRequestSink(
    private val payload: Buffer,
    private val maximum: Int,
) : Sink {
    private var rejected = false

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        if (rejected || byteCount < 0 || byteCount > maximum - payload.size) {
            rejected = true
            throw IOException("Complaint mutation body limit rejected")
        }
        payload.write(source, byteCount)
    }

    fun requireValid() {
        if (rejected) throw IOException("Complaint mutation body limit rejected")
    }

    override fun timeout(): Timeout = Timeout.NONE

    override fun flush() = Unit

    override fun close() = Unit
}
