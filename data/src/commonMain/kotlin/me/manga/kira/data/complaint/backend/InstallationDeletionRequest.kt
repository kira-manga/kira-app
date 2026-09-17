package me.manga.kira.data.complaint.backend

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Exact durable tuple, never caller-supplied body text, a URL, or a normal mutation receipt. */
internal class InstallationDeletionRequest(
    val binding: InstallationDeletionBinding,
) {
    val key: String get() = checkNotNull(binding.record.pendingDeletionKey)

    fun bodyBytes(): ByteArray =
        buildJsonObject {
            val record = binding.record
            put("installationId", record.material.installationId)
            put("secret", record.material.secret)
            put("credentialVersion", record.credentialVersion)
            put("dataScopeId", record.material.dataScopeId)
        }.toString().encodeToByteArray()

    override fun toString(): String = "InstallationDeletionRequest(redacted)"
}

/** Transport observations only. No API accepts one of these from a caller to perform local cleanup. */
internal sealed interface InstallationDeletionHttpResult {
    val request: InstallationDeletionRequest

    class Terminal(override val request: InstallationDeletionRequest) : InstallationDeletionHttpResult

    class Accepted(
        override val request: InstallationDeletionRequest,
        val retryAfterSeconds: Int,
    ) : InstallationDeletionHttpResult

    class HttpFailure(
        override val request: InstallationDeletionRequest,
        val status: Int,
    ) : InstallationDeletionHttpResult

    class Failed(
        override val request: InstallationDeletionRequest,
        val reason: ComplaintSessionFailure,
    ) : InstallationDeletionHttpResult
}
