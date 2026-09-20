package me.manga.kira.data.complaint.backend

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bodyless structural binding only. The coordinator still owns both durable proofs and live authority. */
internal class ComplaintOwnerDeleteHttpRequest private constructor(
    val deletion: ComplaintOwnerDeleteRequest,
    val pending: PendingComplaintRecord,
) {
    override fun toString(): String = "ComplaintOwnerDeleteHttpRequest(redacted)"

    companion object {
        fun checked(
            deletion: ComplaintOwnerDeleteRequest,
            pending: PendingComplaintRecord,
        ): ComplaintOwnerDeleteHttpRequest? {
            if (
                !pending.isDispatchedOwnerDelete() ||
                pending.binding.dataScopeId != deletion.dataScopeId ||
                !pending.request.action.sameAs(deletion.action) ||
                pending.request.key != deletion.key.canonical
            ) {
                return null
            }
            val fingerprint = deletion.pendingFingerprint()
            return if (
                pending.request.fingerprint.version == fingerprint.version &&
                pending.request.fingerprint.encoded == fingerprint.encoded
            ) {
                ComplaintOwnerDeleteHttpRequest(deletion, pending)
            } else {
                null
            }
        }
    }
}

/** Metadata-only status query. It cannot reconstruct a live action or add a mutation key/tag header. */
internal class ComplaintOwnerDeleteStatusRequest private constructor(
    val pending: PendingComplaintRecord,
) {
    internal fun bodyBytes(): ByteArray =
        buildJsonObject {
            put("operation", "OWNER_DELETE")
            put("key", pending.request.key)
            put("targetIds", buildJsonArray { add(JsonPrimitive(pending.request.action.targetId)) })
            put("fingerprint", pending.request.fingerprint.encoded)
        }.toString().encodeToByteArray()

    override fun toString(): String = "ComplaintOwnerDeleteStatusRequest(redacted)"

    companion object {
        fun checked(pending: PendingComplaintRecord): ComplaintOwnerDeleteStatusRequest? =
            if (pending.isDispatchedOwnerDelete()) ComplaintOwnerDeleteStatusRequest(pending) else null
    }
}

private fun PendingComplaintRecord.isDispatchedOwnerDelete(): Boolean =
    state == PendingComplaintState.MAY_HAVE_DISPATCHED &&
        request.action.operation == PendingComplaintOperation.DELETE_OWNED &&
        request.action.parentId == null &&
        request.action.expectedVersion?.let { it > 0 } == true &&
        request.fingerprint.version == 1
