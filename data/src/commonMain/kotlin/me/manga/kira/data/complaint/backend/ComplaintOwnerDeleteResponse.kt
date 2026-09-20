package me.manga.kira.data.complaint.backend

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Separate bodyless204/status matrices; decoding does not grant pending-slot removal authority. */
internal object ComplaintOwnerDeleteResponse {
    fun ownerDelete(
        document: ComplaintMutationDocument,
        request: ComplaintOwnerDeleteHttpRequest,
    ): ComplaintOwnerDeleteHttpResult =
        try {
            if (document.location != null || document.etag != null) invalidHistory()
            if (document.status == NO_CONTENT) {
                if (document.text.isNotEmpty()) invalidHistory()
                ComplaintOwnerDeleteHttpResult.Applied(request)
            } else {
                errorStatus(document)
                ComplaintOwnerDeleteHttpResult.HttpFailure(
                    request,
                    document.status,
                    ComplaintOwnerDeleteProblemReader.read(document.text, document.status),
                )
            }
        } catch (_: InvalidComplaintHistory) {
            ComplaintOwnerDeleteHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        } catch (_: SerializationException) {
            ComplaintOwnerDeleteHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        } catch (_: IllegalArgumentException) {
            ComplaintOwnerDeleteHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        }

    fun status(
        document: ComplaintMutationDocument,
        request: ComplaintOwnerDeleteStatusRequest,
    ): ComplaintOwnerDeleteStatusHttpResult =
        try {
            if (document.location != null || document.etag != null) invalidHistory()
            if (document.status == OK) {
                statusOutcome(ComplaintHistoryJson(document.text, Policy.MAX_STATUS_OR_PROBLEM_BYTES).read(), request)
            } else {
                errorStatus(document)
                ComplaintOwnerDeleteStatusHttpResult.HttpFailure(
                    request,
                    document.status,
                    ComplaintOwnerDeleteProblemReader.read(document.text, document.status),
                )
            }
        } catch (_: InvalidComplaintHistory) {
            ComplaintOwnerDeleteStatusHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        } catch (_: SerializationException) {
            ComplaintOwnerDeleteStatusHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        } catch (_: IllegalArgumentException) {
            ComplaintOwnerDeleteStatusHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        }

    private fun statusOutcome(
        root: JsonObject,
        request: ComplaintOwnerDeleteStatusRequest,
    ): ComplaintOwnerDeleteStatusHttpResult =
        when (root.historyString("outcome")) {
            "APPLIED" -> {
                if (root.keys != APPLIED_FIELDS || root.number("originalStatus") != NO_CONTENT.toLong()) invalidHistory()
                ComplaintOwnerDeleteStatusHttpResult.Applied(request)
            }
            "REJECTED" -> rejectedStatus(root, request)
            else -> invalidHistory()
        }

    private fun rejectedStatus(
        root: JsonObject,
        request: ComplaintOwnerDeleteStatusRequest,
    ): ComplaintOwnerDeleteStatusHttpResult.Rejected {
        if (root.keys != REJECTED_FIELDS) invalidHistory()
        val code =
            ComplaintOwnerDeleteRejection.entries.singleOrNull { it.name == root.historyString("problemCode") }
                ?: invalidHistory()
        if (root.number("originalStatus") != code.status.toLong()) invalidHistory()
        return ComplaintOwnerDeleteStatusHttpResult.Rejected(request, code)
    }

    private fun errorStatus(document: ComplaintMutationDocument) {
        if (document.status !in MIN_ERROR_STATUS..MAX_ERROR_STATUS) invalidHistory()
    }

    private const val OK = 200
    private const val NO_CONTENT = 204
    private const val MIN_ERROR_STATUS = 400
    private const val MAX_ERROR_STATUS = 599
    private val APPLIED_FIELDS = setOf("outcome", "originalStatus")
    private val REJECTED_FIELDS = setOf("outcome", "originalStatus", "problemCode")
}
