package me.manga.kira.data.complaint.backend

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy

/** No generic 410, session outcome, normal-operation receipt, or caller Boolean is terminal authority. */
internal object InstallationDeletionResponse {
    fun read(
        document: InstallationDeletionDocument,
        request: InstallationDeletionRequest,
    ): InstallationDeletionHttpResult =
        when (document.status) {
            HttpStatusCode.NoContent.value -> {
                if (document.text.isNotEmpty()) invalidHistory()
                InstallationDeletionHttpResult.Terminal(request)
            }
            HttpStatusCode.Accepted.value -> {
                if (document.text.isNotEmpty()) invalidHistory()
                InstallationDeletionHttpResult.Accepted(request, document.retryAfterSeconds ?: invalidHistory())
            }
            else -> problem(document, request)
        }

    private fun problem(
        document: InstallationDeletionDocument,
        request: InstallationDeletionRequest,
    ): InstallationDeletionHttpResult {
        if (!ComplaintHistoryProblem.valid(document.text, document.status)) invalidHistory()
        return if (document.status == HttpStatusCode.Gone.value && terminalProblem(document.text)) {
            InstallationDeletionHttpResult.Terminal(request)
        } else {
            InstallationDeletionHttpResult.HttpFailure(request, document.status)
        }
    }

    private fun terminalProblem(text: String): Boolean {
        val root = ComplaintHistoryJson(text, ComplaintDeletionTransportPolicy.MAX_PROBLEM_BYTES).read()
        if (root.keys != ROOT_FIELDS || root.historyString("title") != HttpStatusCode.Gone.description) return false
        val errors = root["errors"] as? JsonArray ?: return false
        if (errors.size != 1) return false
        val error = errors.single() as? JsonObject ?: return false
        return error.keys == ERROR_FIELDS && error.historyString("code") in TERMINAL_CODES
    }

    private val ROOT_FIELDS = setOf("type", "title", "status", "errors")
    private val ERROR_FIELDS = setOf("code", "message")
    private val TERMINAL_CODES = setOf("INSTALLATION_DELETED", "INSTALLATION_RETIRED", "INSTALLATION_SCOPE_RETIRED")
}
