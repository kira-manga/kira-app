package me.manga.kira.data.complaint.backend

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Structural binding only. The coordinator must finish MAY commit/readback before calling checked. */
internal class ComplaintCreateHttpRequest private constructor(
    val report: ComplaintCreationRequest,
    val pending: PendingComplaintRecord,
) {
    val route: ComplaintMutationRoute
        get() =
            when (report) {
                is ComplaintReportRequest -> ComplaintMutationRoute.CREATE
                is ComplaintReplyRequest -> ComplaintMutationRoute.REPLY
            }

    internal fun bodyBytes(): ByteArray =
        buildJsonObject {
            put("id", report.identity.clientId.canonical)
            when (report) {
                is ComplaintReportRequest -> {
                    put("type", report.type.name)
                    put("subject", report.subject)
                }
                is ComplaintReplyRequest -> Unit
            }
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
            report: ComplaintCreationRequest,
            pending: PendingComplaintRecord,
        ): ComplaintCreateHttpRequest? {
            if (!pending.isDispatchedCreation() || !pending.matchesCreationIdentity(report)) return null
            val fingerprint = report.pendingFingerprint()
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
            put("operation", checkNotNull(pending.request.action.creationOperation()).name)
            put("key", pending.request.key)
            put(
                "targetIds",
                buildJsonArray { pending.request.action.orderedTargetIds().forEach { add(JsonPrimitive(it)) } },
            )
            put("fingerprint", pending.request.fingerprint.encoded)
        }.toString().encodeToByteArray()

    override fun toString(): String = "ComplaintCreateStatusRequest(redacted)"

    companion object {
        fun checked(pending: PendingComplaintRecord): ComplaintCreateStatusRequest? =
            if (pending.isDispatchedCreation()) ComplaintCreateStatusRequest(pending) else null
    }
}

private fun PendingComplaintRecord.isDispatchedCreation(): Boolean =
    state == PendingComplaintState.MAY_HAVE_DISPATCHED &&
        request.action.creationOperation() != null &&
        request.action.expectedVersion == null &&
        request.fingerprint.version == 1

private fun PendingComplaintRecord.matchesCreationIdentity(report: ComplaintCreationRequest): Boolean =
    binding.dataScopeId == report.identity.dataScopeId &&
        request.action.creationOperation() == report.operation &&
        request.action.targetId == report.identity.clientId.canonical &&
        request.action.parentId == (report as? ComplaintReplyRequest)?.parentId &&
        request.key == report.identity.key.canonical

internal fun PendingComplaintAction.creationOperation(): ComplaintReportOperation? =
    when (operation) {
        PendingComplaintOperation.CREATE_REPORT -> ComplaintReportOperation.OWNER_CREATE
        PendingComplaintOperation.CREATE_REPLY -> ComplaintReportOperation.OWNER_REPLY
        PendingComplaintOperation.EDIT_CONTENT, PendingComplaintOperation.DELETE_OWNED -> null
    }

/** This is equality, not freshness, authenticated authority, or durable-slot admission. */
internal fun PendingComplaintBinding.matchesMutationSession(session: ComplaintSessionResponse): Boolean =
    installationId == session.binding.installationId &&
        credentialVersion == session.binding.credentialVersion &&
        localGeneration == session.binding.localGeneration &&
        dataScopeId == session.binding.dataScopeId
