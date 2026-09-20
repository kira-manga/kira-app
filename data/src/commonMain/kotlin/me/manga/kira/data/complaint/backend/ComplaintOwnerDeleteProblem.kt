package me.manga.kira.data.complaint.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Observational errors only; exact status404 OPERATION_NOT_FOUND can qualify an original live retry. */
internal enum class ComplaintOwnerDeleteProblem(
    val status: Int,
) {
    OPERATION_NOT_FOUND(NOT_FOUND),
    IDEMPOTENCY_KEY_REUSED(CONFLICT),
    IDEMPOTENCY_IN_PROGRESS(CONFLICT),
    COMPLAINT_NOT_FOUND(NOT_FOUND),
    COMPLAINT_DELETION_PENDING(CONFLICT),
    PRECONDITION_FAILED(PRECONDITION_FAILED_STATUS),
    PRECONDITION_REQUIRED(PRECONDITION_REQUIRED_STATUS),
}

internal object ComplaintOwnerDeleteProblemReader {
    fun read(
        text: String,
        status: Int,
    ): ComplaintOwnerDeleteProblem? {
        if (ComplaintHistoryProblem.valid(text, status)) return null
        val root = ComplaintHistoryJson(text, Policy.MAX_STATUS_OR_PROBLEM_BYTES).read()
        if (
            root.keys != ROOT_FIELDS || root.historyString("type") != "about:blank" ||
            root.number("status") != status.toLong()
        ) {
            invalidHistory()
        }
        val errors = root["errors"] as? JsonArray ?: invalidHistory()
        val error = errors.singleOrNull() as? JsonObject ?: invalidHistory()
        if (error.keys != ERROR_FIELDS) invalidHistory()
        boundedText(error.historyString("message"), 1, MAX_MESSAGE_POINTS, MAX_MESSAGE_BYTES)
        val code =
            ComplaintOwnerDeleteProblem.entries.singleOrNull { it.name == error.historyString("code") }
                ?: invalidHistory()
        if (code.status != status || root.historyString("title") != HttpStatusCode.fromValue(status).description) {
            invalidHistory()
        }
        return code
    }

    private const val MAX_MESSAGE_POINTS = 512
    private const val MAX_MESSAGE_BYTES = 2_048
    private val ROOT_FIELDS = setOf("type", "title", "status", "errors")
    private val ERROR_FIELDS = setOf("code", "message")
}

private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val PRECONDITION_FAILED_STATUS = 412
private const val PRECONDITION_REQUIRED_STATUS = 428
