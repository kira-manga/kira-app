package me.manga.kira.data.complaint.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Closed report/reply matrices; decoded facts never replace coordinator-owned slot/lifetime checks. */
internal object ComplaintMutationResponse {
    fun create(
        document: ComplaintMutationDocument,
        request: ComplaintCreateHttpRequest,
    ): ComplaintCreateHttpResult =
        try {
            if (document.status == HttpStatusCode.Created.value) {
                directAcknowledgement(document, request)
            } else {
                if (document.status !in MIN_ERROR_STATUS..MAX_ERROR_STATUS) invalidHistory()
                ComplaintCreateHttpResult.HttpFailure(
                    request,
                    document.status,
                    problem(document, request.pending.request.action.operation),
                )
            }
        } catch (_: InvalidComplaintHistory) {
            ComplaintCreateHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        } catch (_: SerializationException) {
            ComplaintCreateHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        } catch (_: IllegalArgumentException) {
            ComplaintCreateHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        }

    fun status(
        document: ComplaintMutationDocument,
        request: ComplaintCreateStatusRequest,
    ): ComplaintCreateStatusHttpResult =
        try {
            if (document.status == HttpStatusCode.OK.value) {
                val root = ComplaintHistoryJson(document.text, Policy.MAX_STATUS_OR_PROBLEM_BYTES).read()
                statusOutcome(root, request)
            } else {
                if (document.status !in MIN_ERROR_STATUS..MAX_ERROR_STATUS) invalidHistory()
                ComplaintCreateStatusHttpResult.HttpFailure(
                    request,
                    document.status,
                    problem(document, request.pending.request.action.operation),
                )
            }
        } catch (_: InvalidComplaintHistory) {
            ComplaintCreateStatusHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        } catch (_: SerializationException) {
            ComplaintCreateStatusHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        } catch (_: IllegalArgumentException) {
            ComplaintCreateStatusHttpResult.Failed(request, ComplaintMutationFailure.RESPONSE)
        }

    private fun statusOutcome(
        root: JsonObject,
        request: ComplaintCreateStatusRequest,
    ): ComplaintCreateStatusHttpResult =
        when (root.historyString("outcome")) {
            "APPLIED" -> appliedStatus(root, request)
            "REJECTED" -> rejectedStatus(root, request)
            else -> invalidHistory()
        }

    private fun directAcknowledgement(
        document: ComplaintMutationDocument,
        request: ComplaintCreateHttpRequest,
    ): ComplaintCreateHttpResult.Applied =
        ComplaintCreateHttpResult.Applied(
            request,
            acknowledgement(
                ComplaintHistoryJson(document.text, Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES).read(),
                request.pending.request.action.targetId,
                document.location ?: invalidHistory(),
                document.etag ?: invalidHistory(),
                request.pending.request.action.operation,
            ),
        )

    private fun appliedStatus(
        root: JsonObject,
        request: ComplaintCreateStatusRequest,
    ): ComplaintCreateStatusHttpResult.Applied {
        if (root.keys != APPLIED_FIELDS || root.number("originalStatus") != HttpStatusCode.Created.value.toLong()) {
            invalidHistory()
        }
        return ComplaintCreateStatusHttpResult.Applied(
            request,
            acknowledgement(
                root["body"] as? JsonObject ?: invalidHistory(),
                request.pending.request.action.targetId,
                root.historyString("location"),
                root.historyString("etag"),
                request.pending.request.action.operation,
            ),
        )
    }

    private fun rejectedStatus(
        root: JsonObject,
        request: ComplaintCreateStatusRequest,
    ): ComplaintCreateStatusHttpResult.Rejected {
        if (root.keys != REJECTED_FIELDS) invalidHistory()
        val token = root.historyString("problemCode")
        val code: ComplaintCreationRejection =
            ComplaintCreateRejection.entries.singleOrNull { it.wireCode == token }
                ?: ComplaintReplyRejection.entries.singleOrNull {
                    request.pending.request.action.operation == PendingComplaintOperation.CREATE_REPLY &&
                        it.wireCode == token
                }
                ?: invalidHistory()
        if (root.number("originalStatus") != code.status.toLong()) invalidHistory()
        return ComplaintCreateStatusHttpResult.Rejected(request, code)
    }

    private fun problem(
        document: ComplaintMutationDocument,
        operation: PendingComplaintOperation,
    ): ComplaintMutationProblem? = ComplaintMutationProblemReader.read(document.text, document.status, operation)

    private fun acknowledgement(
        root: JsonObject,
        expectedId: String,
        location: String,
        etag: String,
        operation: PendingComplaintOperation,
    ): ComplaintCreateAcknowledgement {
        if (root.keys != ACKNOWLEDGEMENT_FIELDS || root.historyString("id") != expectedId) invalidHistory()
        val version = root.number("version")
        if (version < 1 ||
            (operation == PendingComplaintOperation.CREATE_REPLY && version != 1L) ||
            location != "${Policy.CREATE_PATH}/$expectedId" ||
            etag != "\"complaint-$expectedId-v$version\""
        ) {
            invalidHistory()
        }
        return ComplaintCreateAcknowledgement(expectedId, version, location, etag)
    }

    private const val MIN_ERROR_STATUS = 400
    private const val MAX_ERROR_STATUS = 599
    private val ACKNOWLEDGEMENT_FIELDS = setOf("id", "version")
    private val APPLIED_FIELDS = setOf("outcome", "originalStatus", "location", "etag", "body")
    private val REJECTED_FIELDS = setOf("outcome", "originalStatus", "problemCode")
}
