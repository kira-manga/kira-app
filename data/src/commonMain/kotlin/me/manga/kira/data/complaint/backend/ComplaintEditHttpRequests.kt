package me.manga.kira.data.complaint.backend

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Structural binding only. Construction cannot substitute for the coordinator's two durable proofs. */
internal class ComplaintEditHttpRequest private constructor(
    val edit: ComplaintEditRequest,
    val pending: PendingComplaintRecord,
) {
    internal fun bodyBytes(): ByteArray =
        buildJsonObject {
            edit.subject?.let { put("subject", it) }
            put("body", edit.body)
        }.toString().encodeToByteArray()

    override fun toString(): String = "ComplaintEditHttpRequest(redacted)"

    companion object {
        fun checked(
            edit: ComplaintEditRequest,
            pending: PendingComplaintRecord,
        ): ComplaintEditHttpRequest? {
            if (!pending.isDispatchedEdit() ||
                pending.binding.dataScopeId != edit.dataScopeId ||
                !pending.request.action.sameAs(edit.target.action) ||
                pending.request.key != edit.key.canonical
            ) {
                return null
            }
            val fingerprint = edit.pendingFingerprint()
            return if (pending.request.fingerprint.version == fingerprint.version &&
                pending.request.fingerprint.encoded == fingerprint.encoded
            ) {
                ComplaintEditHttpRequest(edit, pending)
            } else {
                null
            }
        }
    }
}

/** Metadata-only status query, bound to the complete local tuple. No precondition or mutation-key header. */
internal class ComplaintEditStatusRequest private constructor(
    val pending: PendingComplaintRecord,
) {
    internal fun bodyBytes(): ByteArray =
        buildJsonObject {
            put("operation", "OWNER_EDIT")
            put("key", pending.request.key)
            put("targetIds", buildJsonArray { add(JsonPrimitive(pending.request.action.targetId)) })
            put("fingerprint", pending.request.fingerprint.encoded)
        }.toString().encodeToByteArray()

    override fun toString(): String = "ComplaintEditStatusRequest(redacted)"

    companion object {
        fun checked(pending: PendingComplaintRecord): ComplaintEditStatusRequest? =
            if (pending.isDispatchedEdit()) ComplaintEditStatusRequest(pending) else null
    }
}

private fun PendingComplaintRecord.isDispatchedEdit(): Boolean =
    state == PendingComplaintState.MAY_HAVE_DISPATCHED &&
        request.action.operation == PendingComplaintOperation.EDIT_CONTENT &&
        request.action.parentId == null &&
        request.action.expectedVersion?.let { it > 0 } == true &&
        request.fingerprint.version == 1
