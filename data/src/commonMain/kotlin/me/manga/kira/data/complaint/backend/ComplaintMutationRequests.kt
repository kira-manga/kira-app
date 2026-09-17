package me.manga.kira.data.complaint.backend

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Structural binding only. The coordinator must finish MAY commit/readback before calling checked. */
internal class ComplaintCreateHttpRequest private constructor(
    val report: ComplaintReportRequest,
    val pending: PendingComplaintRecord,
) {
    internal fun bodyBytes(): ByteArray =
        buildJsonObject {
            put("id", report.identity.clientId.canonical)
            put("type", report.type.name)
            put("subject", report.subject)
            put("body", report.body)
            put(
                "metadata",
                buildJsonObject {
                    put("appVersion", report.metadata.appVersion)
                    put("osVersion", report.metadata.osVersion)
                    put("manufacturer", report.metadata.manufacturer)
                    put("deviceModel", report.metadata.deviceModel)
                },
            )
        }.toString().encodeToByteArray()

    override fun toString(): String = "ComplaintCreateHttpRequest(redacted)"

    companion object {
        fun checked(
            report: ComplaintReportRequest,
            pending: PendingComplaintRecord,
        ): ComplaintCreateHttpRequest? {
            if (!pending.isDispatchedCreate() ||
                pending.binding.dataScopeId != report.identity.dataScopeId ||
                pending.request.action.targetId != report.identity.clientId.canonical ||
                pending.request.key != report.identity.key.canonical
            ) {
                return null
            }
            val fingerprint = ComplaintReportFingerprint.of(report)
            return if (pending.request.fingerprint.version == fingerprint.version &&
                pending.request.fingerprint.encoded == fingerprint.encoded
            ) {
                ComplaintCreateHttpRequest(report, pending)
            } else {
                null
            }
        }
    }
}

/** Immutable attempt identity, including the full local tuple, without retaining prose or sending local fields. */
internal class ComplaintCreateStatusRequest private constructor(
    val pending: PendingComplaintRecord,
) {
    internal fun bodyBytes(): ByteArray =
        buildJsonObject {
            put("operation", "OWNER_CREATE")
            put("key", pending.request.key)
            put("targetIds", buildJsonArray { add(JsonPrimitive(pending.request.action.targetId)) })
            put("fingerprint", pending.request.fingerprint.encoded)
        }.toString().encodeToByteArray()

    override fun toString(): String = "ComplaintCreateStatusRequest(redacted)"

    companion object {
        fun checked(pending: PendingComplaintRecord): ComplaintCreateStatusRequest? =
            if (pending.isDispatchedCreate()) ComplaintCreateStatusRequest(pending) else null
    }
}

private fun PendingComplaintRecord.isDispatchedCreate(): Boolean =
    state == PendingComplaintState.MAY_HAVE_DISPATCHED &&
        request.action.operation == PendingComplaintOperation.CREATE_REPORT &&
        request.action.parentId == null &&
        request.action.expectedVersion == null &&
        request.fingerprint.version == 1

/** This is equality, not freshness, authenticated authority, or durable-slot admission. */
internal fun PendingComplaintBinding.matchesMutationSession(session: ComplaintSessionResponse): Boolean =
    installationId == session.binding.installationId &&
        credentialVersion == session.binding.credentialVersion &&
        localGeneration == session.binding.localGeneration &&
        dataScopeId == session.binding.dataScopeId
