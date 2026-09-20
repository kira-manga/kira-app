package me.manga.kira.data.complaint.backend

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Exact edit v1 frame: nullable subject and canonical precondition, without a key, parent or diagnostics. */
internal class ComplaintEditFingerprint private constructor(
    private val digest: ByteString,
) {
    val version: Int get() = VERSION
    val encoded: String get() = digest.base64Url().trimEnd('=')

    fun bytes(): ByteArray = digest.toByteArray()

    override fun toString(): String = "ComplaintEditFingerprint(redacted)"

    companion object {
        private const val VERSION = 1
        private const val MAX_FRAME_BYTES = 6_144

        fun of(request: ComplaintEditRequest): ComplaintEditFingerprint {
            val frame = frameBytes(request)
            return try {
                ComplaintEditFingerprint(frame.toByteString().sha256())
            } finally {
                frame.fill(0)
            }
        }

        /** Transient prose-bearing bytes for independent fixture comparison; never store or log. */
        internal fun frameBytes(request: ComplaintEditRequest): ByteArray {
            val frame = Buffer()
            frame.editField("kira-complaint-request-fingerprint")
            frame.writeInt(VERSION)
            frame.editField("PATCH")
            frame.editField("/api/v1/complaints/{id}/content")
            frame.editField("OWNER_EDIT")
            frame.editField(request.dataScopeId)
            frame.writeInt(1)
            frame.editField(request.target.id)
            frame.editField(request.subject)
            frame.editField(request.body)
            frame.editField(request.target.precondition)
            return frame.readByteArray().also { check(it.size <= MAX_FRAME_BYTES) { "Complaint frame exceeds limit." } }
        }
    }
}

private fun Buffer.editField(value: String?) {
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
