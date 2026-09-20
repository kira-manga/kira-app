package me.manga.kira.data.complaint.backend

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Exact reply v1 frame, separate from report serialization and from the actual parent HTTP path. */
internal class ComplaintReplyFingerprint private constructor(
    private val digest: ByteString,
) {
    val version: Int get() = VERSION
    val encoded: String get() = digest.base64Url().trimEnd('=')

    fun bytes(): ByteArray = digest.toByteArray()

    override fun toString(): String = "ComplaintReplyFingerprint(redacted)"

    companion object {
        private const val VERSION = 1
        private const val MAX_FRAME_BYTES = 4_096

        fun of(request: ComplaintReplyRequest): ComplaintReplyFingerprint {
            val frame = frameBytes(request)
            return try {
                ComplaintReplyFingerprint(frame.toByteString().sha256())
            } finally {
                frame.fill(0)
            }
        }

        /** Transient prose-bearing bytes for independent frame fixtures; never persist or log. */
        internal fun frameBytes(request: ComplaintReplyRequest): ByteArray {
            val frame = Buffer()
            frame.field("kira-complaint-request-fingerprint")
            frame.writeInt(VERSION)
            frame.field("POST")
            frame.field("/api/v1/complaints/{id}/replies")
            frame.field(request.operation.name)
            frame.field(request.identity.dataScopeId)
            frame.writeInt(2)
            frame.field(request.parentId)
            frame.field(request.identity.clientId.canonical)
            frame.field(request.body)
            frame.field(request.metadata.appVersion)
            frame.field(request.metadata.osVersion)
            frame.field(request.metadata.manufacturer)
            frame.field(request.metadata.deviceModel)
            frame.field(null)
            return frame.readByteArray().also { check(it.size <= MAX_FRAME_BYTES) { "Complaint frame exceeds limit." } }
        }
    }
}

/** Big-endian byte-length framing; null and empty retain distinct encodings. */
private fun Buffer.field(value: String?) {
    if (value == null) {
        writeInt(-1)
    } else {
        val bytes = value.encodeToByteArray()
        try {
            writeInt(bytes.size)
            write(bytes)
        } finally {
            bytes.fill(0)
        }
    }
}
