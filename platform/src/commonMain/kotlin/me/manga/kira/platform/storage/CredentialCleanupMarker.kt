package me.manga.kira.platform.storage

/** Reasons are durable continuations of distinct authorizations, never a generic force switch. */
enum class CredentialCleanupReason {
    SERVER_TERMINAL_CONFIRMED,
    USER_RESET_CONFIRMED,
    UNREADABLE_RESET_CONFIRMED,
    REMOTE_DELETE_ABANDON_CONFIRMED,
}

/**
 * Identifier-free authorization for cleanup of the dedicated fixed credential slots only.
 * Creating this value is not consent or durable authorization; the coordinator must establish both.
 * Native encoding is capped at [MAX_ENCODED_BYTES] before allocation/decoding.
 */
class CredentialCleanupMarker private constructor(
    val schemaVersion: Int,
    val expectedGeneration: Long?,
    val reason: CredentialCleanupReason,
) {
    /** Exact comparison; a different durable marker cannot be replaced by a new request. */
    fun sameAs(other: CredentialCleanupMarker): Boolean =
        schemaVersion == other.schemaVersion && expectedGeneration == other.expectedGeneration && reason == other.reason

    override fun toString(): String = "CredentialCleanupMarker(redacted)"

    companion object {
        const val MAX_ENCODED_BYTES: Int = 512

        /** Null generation is exclusively for confirmed unreadable/missing-record recovery. */
        fun checked(
            schemaVersion: Int,
            expectedGeneration: Long?,
            reason: CredentialCleanupReason,
        ): InstallationValueResult<CredentialCleanupMarker> {
            if (schemaVersion != InstallationCredentialRecord.SCHEMA_VERSION) {
                return invalid(InstallationValueIssue.SCHEMA)
            }
            val unreadable = reason == CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED
            return when {
                unreadable != (expectedGeneration == null) -> invalid(InstallationValueIssue.MARKER_REASON)
                expectedGeneration != null && expectedGeneration <= 0 -> invalid(InstallationValueIssue.GENERATION)
                else ->
                    InstallationValueResult.Valid(CredentialCleanupMarker(schemaVersion, expectedGeneration, reason))
            }
        }
    }
}
