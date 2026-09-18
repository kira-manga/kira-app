package me.manga.kira.data.complaint.backend

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Separate edit200/status matrices. Decoding cannot authorize application or pending-slot deletion. */
internal object ComplaintEditResponse {
    fun edit(
        document: ComplaintMutationDocument,
        request: ComplaintEditHttpRequest,
    ): ComplaintEditHttpResult =
        try {
            if (document.location != null) invalidHistory()
            if (document.status == OK) {
                directApplied(document, request)
            } else {
                errorHeaders(document)
                ComplaintEditHttpResult.HttpFailure(
                    request, document.status, ComplaintEditProblemReader.read(document.text, document.status),
                )
            }
        } catch (_: InvalidComplaintHistory) {
            ComplaintEditHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        } catch (_: SerializationException) {
            ComplaintEditHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        } catch (_: IllegalArgumentException) {
            ComplaintEditHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        }

    private fun directApplied(
        document: ComplaintMutationDocument,
        request: ComplaintEditHttpRequest,
    ): ComplaintEditHttpResult.Applied =
        ComplaintEditHttpResult.Applied(
            request,
            acknowledgement(
                ComplaintHistoryJson(document.text, Policy.MAX_EDIT_ACKNOWLEDGEMENT_BYTES).read(),
                request.pending,
                document.etag ?: invalidHistory(),
            ),
        )

    fun status(
        document: ComplaintMutationDocument,
        request: ComplaintEditStatusRequest,
    ): ComplaintEditStatusHttpResult =
        try {
            if (document.location != null || document.etag != null) invalidHistory()
            if (document.status == OK) {
                statusOutcome(ComplaintHistoryJson(document.text, Policy.MAX_STATUS_OR_PROBLEM_BYTES).read(), request)
            } else {
                errorHeaders(document)
                ComplaintEditStatusHttpResult.HttpFailure(
                    request, document.status, ComplaintEditProblemReader.read(document.text, document.status),
                )
            }
        } catch (_: InvalidComplaintHistory) {
            ComplaintEditStatusHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        } catch (_: SerializationException) {
            ComplaintEditStatusHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        } catch (_: IllegalArgumentException) {
            ComplaintEditStatusHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        }

    private fun statusOutcome(
        root: JsonObject,
        request: ComplaintEditStatusRequest,
    ): ComplaintEditStatusHttpResult =
        when (root.historyString("outcome")) {
            "APPLIED" -> appliedStatus(root, request)
            "REJECTED" -> rejectedStatus(root, request)
            else -> invalidHistory()
        }

    private fun appliedStatus(
        root: JsonObject,
        request: ComplaintEditStatusRequest,
    ): ComplaintEditStatusHttpResult.Applied {
        if (root.keys != APPLIED_FIELDS || root.number("originalStatus") != OK.toLong()) invalidHistory()
        return ComplaintEditStatusHttpResult.Applied(
            request,
            acknowledgement(root["body"] as? JsonObject ?: invalidHistory(), request.pending, root.historyString("etag")),
        )
    }

    private fun rejectedStatus(
        root: JsonObject,
        request: ComplaintEditStatusRequest,
    ): ComplaintEditStatusHttpResult.Rejected {
        if (root.keys != REJECTED_FIELDS) invalidHistory()
        val code = ComplaintEditRejection.entries.singleOrNull { it.name == root.historyString("problemCode") }
            ?: invalidHistory()
        if (root.number("originalStatus") != code.status.toLong()) invalidHistory()
        return ComplaintEditStatusHttpResult.Rejected(request, code)
    }

    private fun acknowledgement(
        root: JsonObject,
        pending: PendingComplaintRecord,
        etag: String,
    ): ComplaintEditAcknowledgement {
        val expected = pending.request.action
        val before = expected.expectedVersion ?: invalidHistory()
        if (root.keys != ACK_FIELDS || root.historyString("id") != expected.targetId) invalidHistory()
        val version = root.number("version")
        if (before == Long.MAX_VALUE || version != before + 1 ||
            etag != "\"complaint-${expected.targetId}-v$version\""
        ) {
            invalidHistory()
        }
        return ComplaintEditAcknowledgement(expected.targetId, version, etag)
    }

    private fun errorHeaders(document: ComplaintMutationDocument) {
        if (document.status !in MIN_ERROR_STATUS..MAX_ERROR_STATUS || document.etag != null) invalidHistory()
    }

    private const val OK = 200
    private const val MIN_ERROR_STATUS = 400
    private const val MAX_ERROR_STATUS = 599
    private val ACK_FIELDS = setOf("id", "version")
    private val APPLIED_FIELDS = setOf("outcome", "originalStatus", "etag", "body")
    private val REJECTED_FIELDS = setOf("outcome", "originalStatus", "problemCode")
}
