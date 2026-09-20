package me.manga.kira.platform.storage

/**
 * Dedicated single-process credential SPI; it deliberately does not extend nullable SecureStorage.
 * All successes include durable read-back or verified absence, and all failures are typed.
 * Native implementations must preserve cancellation and enforce the fixed-piece/size/sync contract.
 * No implementation or production consumer is installed by the common-only W07-A slice.
 */
interface InstallationCredentialStore {
    /** Missing requires every fixed credential/key piece absent, not merely an unreadable payload. */
    suspend fun read(): CredentialReadResult

    /** Accepts only an initial candidate and only after every credential piece and marker is absent. */
    suspend fun createIfMissing(record: InstallationCredentialRecord): CredentialCreateResult

    /**
     * CAS with marker absent, immutable material/version, and exactly expectedGeneration+1.
     * Only ACTIVE -> one of the two pending states is allowed; missing/stale never creates a record.
     */
    suspend fun replace(
        expectedGeneration: Long,
        record: InstallationCredentialRecord,
    ): CredentialReplaceResult

    /** Positive generation deletion requires this exact durable marker; never deletes unmarked state. */
    suspend fun delete(
        expectedGeneration: Long,
        expectedMarker: CredentialCleanupMarker,
    ): CredentialDeleteResult

    /** Requires the exact durable null-generation UNREADABLE_RESET_CONFIRMED marker, not a Boolean. */
    suspend fun resetUnreadableAfterConfirmation(expectedMarker: CredentialCleanupMarker): CredentialResetResult

    /**
     * Resumes fixed credential/key/atomic-variant cleanup from the exact durable marker, leaving it.
     * Readable state must match its generation and reason; a null marker must not target readable state.
     * A positive marker remains authority after key deletion makes its payload unreadable. Do not
     * require decrypting that generation again, re-prompt, overwrite a conflict, or delete the marker.
     */
    suspend fun finishMarkedCleanup(expectedMarker: CredentialCleanupMarker): CredentialDeleteResult

    /** Reads the bounded fixed marker independently of credential decryption; malformed is not missing. */
    suspend fun readCleanupMarker(): CleanupMarkerReadResult

    /** Create-if-absent/read-back; an exact existing marker resumes and a different one is preserved. */
    suspend fun createCleanupMarkerIfMissing(marker: CredentialCleanupMarker): CleanupMarkerCreateResult

    /**
     * Removes only the exact marker after all fixed credential pieces are proven absent.
     * The coordinator additionally proves pending-service absence before calling and verifies again.
     */
    suspend fun removeCleanupMarker(expectedMarker: CredentialCleanupMarker): CleanupMarkerRemoveResult
}
