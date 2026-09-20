package me.manga.kira.platform.storage

/** Content-free rejection reasons for checked installation values, not storage or server results. */
enum class InstallationValueIssue {
    SCHEMA,
    INSTALLATION_ID,
    SECRET,
    PLATFORM,
    SCOPE,
    VERSION,
    GENERATION,
    STATE_KEY,
    TRANSITION,
    GENERATION_OVERFLOW,
    MARKER_REASON,
    SLOT_ID,
    SLOT_SIZE,
    SLOT_COUNT,
    DUPLICATE_SLOT,
    TOTAL_SLOT_SIZE,
}

/** A checked immutable value; failures never retain the rejected input. */
sealed interface InstallationValueResult<out T> {
    class Valid<T>(
        val value: T,
    ) : InstallationValueResult<T> {
        override fun toString(): String = "InstallationValueResult.Valid(redacted)"
    }

    data class Invalid(
        val issue: InstallationValueIssue,
    ) : InstallationValueResult<Nothing>
}

/** Retryable storage failures remain distinct from absence and from damaged data. */
enum class InstallationTemporaryFailure {
    LOCKED,
    IO_FAILURE,
    UNCERTAIN,
}

/** Permanent/refused operations require reconciliation, never an implicit new identity. */
enum class InstallationPermanentFailure {
    CORRUPT,
    INVALIDATED,
    UNSUPPORTED,
    TOO_LARGE,
    READ_BACK_MISMATCH,
    MARKER_CONFLICT,
    STATE_CHANGED,
}

/**
 * Shared, content-free failure alternatives of the operation-specific result sets below.
 * Native adapters must classify OS/I/O failures, not return exception messages or raw exceptions.
 */
sealed interface InstallationStorageFailure :
    CredentialReadResult,
    CredentialCreateResult,
    CredentialReplaceResult,
    CredentialDeleteResult,
    CredentialResetResult,
    CleanupMarkerReadResult,
    CleanupMarkerCreateResult,
    CleanupMarkerRemoveResult,
    PendingReadResult,
    PendingCreateResult,
    PendingReplaceResult,
    PendingDeleteResult,
    PendingClearResult {
    data class TemporarilyUnavailable(
        val reason: InstallationTemporaryFailure,
    ) : InstallationStorageFailure

    data class PermanentFailure(
        val reason: InstallationPermanentFailure,
    ) : InstallationStorageFailure
}

/** Missing proves that every fixed credential piece, including any key alias, is absent. */
sealed interface CredentialReadResult {
    data object Missing : CredentialReadResult

    class Present(
        val record: InstallationCredentialRecord,
    ) : CredentialReadResult {
        override fun toString(): String = "CredentialReadResult.Present(redacted)"
    }
}

/** Stored includes durable read-back; AlreadyPresent requires reading the winning record. */
sealed interface CredentialCreateResult {
    data object Stored : CredentialCreateResult

    data object AlreadyPresent : CredentialCreateResult
}

/** A stale or missing replacement must never fall through to creation. */
sealed interface CredentialReplaceResult {
    data object Stored : CredentialReplaceResult

    data object Stale : CredentialReplaceResult

    data object Missing : CredentialReplaceResult
}

/** Deleted/Missing include verified fixed-piece absence; Stale retains all remaining evidence. */
sealed interface CredentialDeleteResult {
    data object Deleted : CredentialDeleteResult

    data object Missing : CredentialDeleteResult

    data object Stale : CredentialDeleteResult
}

/** An explicitly authorized unreadable reset has no unmarked or stale-generation shortcut. */
sealed interface CredentialResetResult {
    data object Deleted : CredentialResetResult

    data object Missing : CredentialResetResult
}

/** A corrupt, conflicting or inaccessible marker is not Missing. */
sealed interface CleanupMarkerReadResult {
    data object Missing : CleanupMarkerReadResult

    class Present(
        val marker: CredentialCleanupMarker,
    ) : CleanupMarkerReadResult {
        override fun toString(): String = "CleanupMarkerReadResult.Present(redacted)"
    }
}

/** Existing markers are never overwritten, including when they are malformed. */
sealed interface CleanupMarkerCreateResult {
    data object Stored : CleanupMarkerCreateResult

    data object AlreadyPresent : CleanupMarkerCreateResult
}

/** Removal is exact-marker checked and verifies absence; it never deletes credential pieces. */
sealed interface CleanupMarkerRemoveResult {
    data object Removed : CleanupMarkerRemoveResult

    data object Missing : CleanupMarkerRemoveResult

    data object Stale : CleanupMarkerRemoveResult
}

/** Verified inventory establishes physical slots only, not their semantic ownership or contents. */
sealed interface PendingReadResult {
    class Verified(
        val snapshot: PendingComplaintSnapshot,
    ) : PendingReadResult {
        override fun toString(): String = "PendingReadResult.Verified(redacted)"
    }
}

/** Creating a pending slot never overwrites or evicts another slot. */
sealed interface PendingCreateResult {
    data object Stored : PendingCreateResult

    data object AlreadyPresent : PendingCreateResult
}

/** Replacement compares the exact old slot, including its opaque bytes, before writing. */
sealed interface PendingReplaceResult {
    data object Stored : PendingReplaceResult

    data object Stale : PendingReplaceResult

    data object Missing : PendingReplaceResult
}

/** Single-slot deletion compares the exact old slot and verifies absence. */
sealed interface PendingDeleteResult {
    data object Deleted : PendingDeleteResult

    data object Stale : PendingDeleteResult

    data object Missing : PendingDeleteResult
}

/** Cleared means the entire dedicated service was enumerated and verified empty. */
sealed interface PendingClearResult {
    data object Cleared : PendingClearResult
}
