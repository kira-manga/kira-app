package me.manga.kira.platform.storage

/** Bounded opaque bytes with defensive ownership. This type does not validate ownership or prose. */
class PendingComplaintSlot private constructor(
    val id: String,
    private val content: ByteArray,
) {
    val size: Int get() = content.size

    /** Returns a fresh copy; neither callers nor native buffers can mutate a retained slot. */
    fun bytes(): ByteArray = content.copyOf()

    /** Exact compare-and-set input, not a semantic request fingerprint. */
    fun sameAs(other: PendingComplaintSlot): Boolean = id == other.id && content.contentEquals(other.content)

    override fun toString(): String = "PendingComplaintSlot(redacted)"

    companion object {
        const val MAX_BYTES: Int = 2 * 1024

        /** Only canonical slot identity and physical byte length are checked here. */
        fun checked(
            id: String,
            bytes: ByteArray,
        ): InstallationValueResult<PendingComplaintSlot> =
            when {
                !canonicalInstallationUuid(id) -> invalid(InstallationValueIssue.SLOT_ID)
                bytes.size > MAX_BYTES -> invalid(InstallationValueIssue.SLOT_SIZE)
                else -> InstallationValueResult.Valid(PendingComplaintSlot(id, bytes.copyOf()))
            }
    }
}

/** Immutable bounded inventory. Nonempty does not imply tuple-valid or safe-to-replay. */
class PendingComplaintSnapshot private constructor(
    private val slots: List<PendingComplaintSlot>,
) {
    val isEmpty: Boolean get() = slots.isEmpty()
    val size: Int get() = slots.size
    val logicalBytes: Int get() = slots.sumOf { it.size }

    /** The list and every slot own their contents independently of callers. */
    fun entries(): List<PendingComplaintSlot> = slots.toList()

    override fun toString(): String = "PendingComplaintSnapshot(redacted)"

    companion object {
        const val MAX_SLOTS: Int = 16
        const val MAX_LOGICAL_BYTES: Int = 32 * 1024
        const val MAX_ANDROID_PHYSICAL_BYTES: Int = 64 * 1024

        /** Native adapters must also reject oversized/malformed enumeration before buffering it. */
        fun checked(slots: List<PendingComplaintSlot>): InstallationValueResult<PendingComplaintSnapshot> =
            when {
                slots.size > MAX_SLOTS -> invalid(InstallationValueIssue.SLOT_COUNT)
                slots.map { it.id }.distinct().size != slots.size -> invalid(InstallationValueIssue.DUPLICATE_SLOT)
                slots.sumOf { it.size } > MAX_LOGICAL_BYTES -> invalid(InstallationValueIssue.TOTAL_SLOT_SIZE)
                else -> InstallationValueResult.Valid(PendingComplaintSnapshot(slots.toList()))
            }
    }
}

/**
 * Dedicated backup-excluded slot service, serialized by the installation coordinator.
 * No preferences, data-layer DTOs, automatic eviction or multi-item Keychain atomicity is implied.
 * Successful mutation results include checked read-back/absence; cancellation must propagate.
 */
interface PendingComplaintActionStore {
    /** Enumerates and validates the complete physical inventory without interpreting opaque contents. */
    suspend fun read(): PendingReadResult

    /** Creates one absent canonical slot without eviction; Stored includes exact read-back. */
    suspend fun createIfMissing(slot: PendingComplaintSlot): PendingCreateResult

    /** Replaces only the exact old slot at the same identity; Stored includes exact read-back. */
    suspend fun replace(
        expected: PendingComplaintSlot,
        replacement: PendingComplaintSlot,
    ): PendingReplaceResult

    /** Deletes only the exact old slot and proves its absence; other slots remain untouched. */
    suspend fun delete(expected: PendingComplaintSlot): PendingDeleteResult

    /**
     * Clears only this fixed service under the coordinator's durable reset/abandonment authorization.
     * Partial failure preserves its typed reason; the coordinator must still verify empty inventory.
     * Ordinary admission and unqualified server-terminal cleanup must never call this operation.
     */
    suspend fun clearForConfirmedRecovery(): PendingClearResult
}
