package me.manga.kira.data.complaint.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy

/** Additional finite owner-mutation facts. Common ApiError failures intentionally carry no new retry fact. */
internal enum class ComplaintMutationProblem {
    OPERATION_NOT_FOUND,
    IDEMPOTENCY_KEY_REUSED,
    IDEMPOTENCY_IN_PROGRESS,
    COMPLAINT_CAPACITY_REACHED,
    COMPLAINT_RESOURCE_ID_REUSED,
    COMPLAINT_PARENT_NOT_FOUND,
    COMPLAINT_DELETION_PENDING,
}

/** Reuses the accepted common ApiError grammar; new mutation codes require one exact bounded error. */
internal object ComplaintMutationProblemReader {
    fun read(
        text: String,
        status: Int,
        operation: PendingComplaintOperation,
    ): ComplaintMutationProblem? {
        val common = ComplaintHistoryProblem.valid(text, status)
        if (common && operation != PendingComplaintOperation.CREATE_REPLY) return null
        val root = ComplaintHistoryJson(text, ComplaintMutationTransportPolicy.MAX_STATUS_OR_PROBLEM_BYTES).read()
        // Deletion-pending was already a generic report error. Only reply adds this typed 409 fact.
        if (common && !isReplyDeletion(root)) return null
        val code = specific(root, status)
        if (operation != PendingComplaintOperation.CREATE_REPLY &&
            code == ComplaintMutationProblem.COMPLAINT_PARENT_NOT_FOUND
        ) {
            invalidHistory()
        }
        return code
    }

    private fun specific(
        root: JsonObject,
        status: Int,
    ): ComplaintMutationProblem {
        if (root.keys != ROOT_FIELDS ||
            root.historyString("type") != "about:blank" ||
            root.number("status") != status.toLong()
        ) {
            invalidHistory()
        }
        val code = code(root)
        val expected =
            if (code == ComplaintMutationProblem.OPERATION_NOT_FOUND ||
                code == ComplaintMutationProblem.COMPLAINT_PARENT_NOT_FOUND
            ) {
                HttpStatusCode.NotFound
            } else {
                HttpStatusCode.Conflict
            }
        if (status != expected.value || root.historyString("title") != expected.description) invalidHistory()
        return code
    }

    private fun isReplyDeletion(root: JsonObject): Boolean {
        val errors = root["errors"] as? JsonArray ?: return false
        val error = errors.singleOrNull() as? JsonObject ?: return false
        return error.historyString("code") == ComplaintMutationProblem.COMPLAINT_DELETION_PENDING.name
    }

    private fun code(root: JsonObject): ComplaintMutationProblem {
        val errors = root["errors"] as? JsonArray ?: invalidHistory()
        if (errors.size != 1) invalidHistory()
        val error = errors.single() as? JsonObject ?: invalidHistory()
        if (error.keys != ERROR_FIELDS) invalidHistory()
        boundedText(error.historyString("message"), 1, MAX_MESSAGE_POINTS, MAX_MESSAGE_BYTES)
        return ComplaintMutationProblem.entries.singleOrNull { it.name == error.historyString("code") }
            ?: invalidHistory()
    }

    private const val MAX_MESSAGE_POINTS = 512
    private const val MAX_MESSAGE_BYTES = 2_048
    private val ROOT_FIELDS = setOf("type", "title", "status", "errors")
    private val ERROR_FIELDS = setOf("code", "message")
}
