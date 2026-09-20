package me.manga.kira.data.complaint.backend

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Existing backend common/ApiError.kt shape, not an invented complaint-specific problem DTO. */
internal object ComplaintHistoryProblem {
    fun valid(
        text: String,
        status: Int,
    ): Boolean = matches(text, status) { true }

    /** A status alone, disabled response, or mixed error list is never same-identity enrollment evidence. */
    fun installationNotFound(text: String): Boolean =
        matches(text, NOT_FOUND_STATUS) { root ->
            val errors = root["errors"] as? JsonArray ?: return@matches false
            root.historyString("title") == "Not Found" &&
                errors.size == 1 &&
                (errors.single() as JsonObject).historyString("code") == "INSTALLATION_NOT_FOUND"
        }

    /** Only the detail route's exact resource-not-found fact; disabled/session404 is not parent evidence. */
    fun detailNotFound(text: String): Boolean =
        matches(text, NOT_FOUND_STATUS) { root ->
            val errors = root["errors"] as? JsonArray ?: return@matches false
            root.historyString("title") == "Not Found" &&
                errors.size == 1 &&
                (errors.single() as JsonObject).historyString("code") == "NOT_FOUND"
        }

    private fun matches(
        text: String,
        status: Int,
        predicate: (JsonObject) -> Boolean,
    ): Boolean =
        try {
            predicate(read(text, status))
        } catch (_: InvalidComplaintHistory) {
            false
        } catch (_: SerializationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun read(
        text: String,
        status: Int,
    ): JsonObject {
        val root = ComplaintHistoryJson(text, maximumBytes = MAX_PROBLEM_BYTES).read()
        if (root.keys.any { it !in FIELDS } || !root.keys.containsAll(REQUIRED)) invalidHistory()
        if (root.number("status") != status.toLong() || root.historyString("type") != "about:blank") invalidHistory()
        boundedText(root.historyString("title"), 1, MAX_TITLE_POINTS, MAX_TITLE_BYTES)
        if ("detail" in root) {
            boundedText(root.historyString("detail"), 1, MAX_DETAIL_POINTS, MAX_DETAIL_BYTES)
        }
        if ("errors" in root) {
            val errors = root["errors"] as? JsonArray ?: invalidHistory()
            if (errors.size !in 1..MAX_ERRORS) invalidHistory()
            errors.forEach { error(it as? JsonObject ?: invalidHistory()) }
        }
        return root
    }

    private fun error(item: JsonObject) {
        if (item.keys.any { it !in ERROR_FIELDS } || !item.keys.containsAll(setOf("code", "message"))) invalidHistory()
        if (item.historyString("code") !in CODES) invalidHistory()
        boundedText(item.historyString("message"), 1, MAX_MESSAGE_POINTS, MAX_MESSAGE_BYTES)
        if ("path" in item) boundedText(item.historyString("path"), 1, MAX_PATH_POINTS, MAX_PATH_BYTES)
    }

    private const val NOT_FOUND_STATUS = 404
    private const val MAX_PROBLEM_BYTES = 16 * 1_024
    private const val MAX_ERRORS = 16
    private const val MAX_TITLE_POINTS = 128
    private const val MAX_TITLE_BYTES = 512
    private const val MAX_DETAIL_POINTS = 1_024
    private const val MAX_DETAIL_BYTES = 4_096
    private const val MAX_MESSAGE_POINTS = 512
    private const val MAX_MESSAGE_BYTES = 2_048
    private const val MAX_PATH_POINTS = 256
    private const val MAX_PATH_BYTES = 1_024
    private val REQUIRED = setOf("type", "title", "status")
    private val FIELDS = REQUIRED + setOf("detail", "errors")
    private val ERROR_FIELDS = setOf("code", "path", "message")
    private val CODES =
        setOf(
            "NOT_FOUND",
            "VALIDATION_FAILED",
            "PAYLOAD_TOO_LARGE",
            "UNSUPPORTED_MEDIA_TYPE",
            "UNAUTHORIZED",
            "FORBIDDEN",
            "RATE_LIMITED",
            "SERVICE_UNAVAILABLE",
            "INTERNAL_ERROR",
            "INSTALLATION_NOT_FOUND",
            "INSTALLATION_RETIRED",
            "INSTALLATION_SCOPE_RETIRED",
            "INSTALLATION_CREDENTIAL_REJECTED",
            "INSTALLATION_DELETED",
            "INSTALLATION_SCOPE_MISMATCH",
            "INSTALLATION_DELETION_PENDING",
            "COMPLAINT_DELETION_PENDING",
            "INVALID_CURSOR",
        )
}
