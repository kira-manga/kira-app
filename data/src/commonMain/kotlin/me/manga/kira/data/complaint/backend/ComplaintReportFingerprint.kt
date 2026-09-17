package me.manga.kira.data.complaint.backend

import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** An exact normalized request digest, not authentication, a receipt or permission to dispatch. */
internal class ComplaintReportFingerprint private constructor(
    private val digest: ByteString,
) {
    val version: Int get() = VERSION
    val encoded: String get() = digest.base64Url().trimEnd('=')

    fun bytes(): ByteArray = digest.toByteArray()

    override fun toString(): String = "ComplaintReportFingerprint(redacted)"

    companion object {
        private const val VERSION = 1
        private const val MAX_FRAME_BYTES = 4_806

        fun of(request: ComplaintReportRequest): ComplaintReportFingerprint {
            val frame = frameBytes(request)
            return try {
                ComplaintReportFingerprint(frame.toByteString().sha256())
            } finally {
                frame.fill(0)
            }
        }

        /**
         * Owned transient prose-bearing bytes for the producer and byte-identical contract fixtures; never persist/log.
         */
        internal fun frameBytes(request: ComplaintReportRequest): ByteArray {
            val frame = Buffer()
            frame.field("kira-complaint-request-fingerprint")
            frame.writeInt(VERSION)
            frame.field("POST")
            frame.field("/api/v1/complaints")
            frame.field(request.operation.name)
            frame.field(request.identity.dataScopeId)
            frame.writeInt(1)
            frame.field(request.identity.clientId.canonical)
            frame.field(request.type.name)
            frame.field(request.subject)
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

/** Okio writeInt writes four-byte big-endian words; -1 is the unsigned null sentinel FFFFFFFF. */
private fun Buffer.field(value: String?) {
    if (value == null) {
        writeInt(-1)
    } else {
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        write(bytes)
    }
}
