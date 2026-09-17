package me.manga.kira.data.complaint.backend

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Existing backend common/ApiError.kt shape, not an invented complaint-specific problem DTO. */
internal object ComplaintHistoryProblem {
    fun valid(text: String, status: Int): Boolean = matches(text, status) { true }

    /** A status alone, disabled response, or mixed error list is never same-identity enrollment evidence. */
    fun installationNotFound(text: String): Boolean = matches(text, 404) { root ->
        val errors = root["errors"] as? JsonArray ?: return@matches false
        root.string("title") == "Not Found" && errors.size == 1 &&
            (errors.single() as JsonObject).string("code") == "INSTALLATION_NOT_FOUND"
    }

    private fun matches(text: String, status: Int, predicate: (JsonObject) -> Boolean): Boolean = try {
        predicate(read(text, status))
    } catch (_: InvalidComplaintHistory) {
        false
    } catch (_: SerializationException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun read(text: String, status: Int): JsonObject {
        val root = ComplaintHistoryJson(text, maximumBytes = 16 * 1_024).read()
        if (root.keys.any { it !in FIELDS } || !root.keys.containsAll(REQUIRED)) invalidHistory()
        if (root.number("status") != status.toLong() || root.string("type") != "about:blank") invalidHistory()
        boundedText(root.string("title"), 1, 128, 512)
        if ("detail" in root) boundedText(root.string("detail"), 1, 1_024, 4_096)
        if ("errors" in root) {
            val errors = root["errors"] as? JsonArray ?: invalidHistory()
            if (errors.size !in 1..MAX_ERRORS) invalidHistory()
            errors.forEach { error(it as? JsonObject ?: invalidHistory()) }
        }
        return root
    }

    private fun error(item: JsonObject) {
        if (item.keys.any { it !in ERROR_FIELDS } || !item.keys.containsAll(setOf("code", "message"))) invalidHistory()
        if (item.string("code") !in CODES) invalidHistory()
        boundedText(item.string("message"), 1, 512, 2_048)
        if ("path" in item) boundedText(item.string("path"), 1, 256, 1_024)
    }

    private const val MAX_ERRORS = 16
    private val REQUIRED = setOf("type", "title", "status")
    private val FIELDS = REQUIRED + setOf("detail", "errors")
    private val ERROR_FIELDS = setOf("code", "path", "message")
    private val CODES = setOf(
        "NOT_FOUND", "VALIDATION_FAILED", "PAYLOAD_TOO_LARGE", "UNSUPPORTED_MEDIA_TYPE",
        "UNAUTHORIZED", "FORBIDDEN", "RATE_LIMITED", "SERVICE_UNAVAILABLE", "INTERNAL_ERROR",
        "INSTALLATION_NOT_FOUND", "INSTALLATION_RETIRED", "INSTALLATION_SCOPE_RETIRED",
        "INSTALLATION_CREDENTIAL_REJECTED", "INSTALLATION_DELETED", "INSTALLATION_SCOPE_MISMATCH",
        "INSTALLATION_DELETION_PENDING", "COMPLAINT_DELETION_PENDING", "INVALID_CURSOR",
    )
}
