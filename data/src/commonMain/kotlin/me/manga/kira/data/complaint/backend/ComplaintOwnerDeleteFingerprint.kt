package me.manga.kira.data.complaint.backend

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Exact single-delete v1 frame: no body/null, actor, key, credential or diagnostics slot. */
internal class ComplaintOwnerDeleteFingerprint private constructor(
    private val digest: ByteString,
) {
    val version: Int get() = VERSION
    val encoded: String get() = digest.base64Url().trimEnd('=')

    fun bytes(): ByteArray = digest.toByteArray()

    override fun toString(): String = "ComplaintOwnerDeleteFingerprint(redacted)"

    companion object {
        private const val VERSION = 1
        private const val MAX_FRAME_BYTES = 256

        fun of(request: ComplaintOwnerDeleteRequest): ComplaintOwnerDeleteFingerprint {
            val frame = frameBytes(request)
            return try {
                ComplaintOwnerDeleteFingerprint(frame.toByteString().sha256())
            } finally {
                frame.fill(0)
            }
        }

        /** Independent fixture comparison only; never persist or log the frame. */
        internal fun frameBytes(request: ComplaintOwnerDeleteRequest): ByteArray {
            val frame = Buffer()
            frame.ownerDeleteField("kira-complaint-request-fingerprint")
            frame.writeInt(VERSION)
            frame.ownerDeleteField("DELETE")
            frame.ownerDeleteField("/api/v1/complaints/{id}")
            frame.ownerDeleteField("OWNER_DELETE")
            frame.ownerDeleteField(request.dataScopeId)
            frame.writeInt(1)
            frame.ownerDeleteField(request.targetId)
            frame.ownerDeleteField(request.precondition)
            return frame.readByteArray().also { check(it.size <= MAX_FRAME_BYTES) { "Complaint frame exceeds limit." } }
        }
    }
}

private fun Buffer.ownerDeleteField(value: String) {
    val bytes = value.encodeToByteArray()
    try {
        writeInt(bytes.size)
        write(bytes)
    } finally {
        bytes.fill(0)
    }
}
