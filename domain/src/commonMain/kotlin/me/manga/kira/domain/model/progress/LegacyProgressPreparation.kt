package me.manga.kira.domain.model.progress

/** Outcome of explicit, scoped legacy preparation, separate from opening a Reader session. */
data class LegacyProgressPreparation(
    val disposition: LegacyProgressDisposition,
    val cleanup: LegacyProgressCleanup = LegacyProgressCleanup.NOT_ATTEMPTED,
)

/** Unknown or ambiguous ownership is RETAINED, never attributed to the next Reader caller. */
enum class LegacyProgressDisposition {
    ABSENT,
    RETAINED,
    COPIED,
    SUPERSEDED,
    CLEANUP_ONLY,
}

/** ACKNOWLEDGED describes a returned cleanup attempt, not a durable platform-storage flush. */
enum class LegacyProgressCleanup {
    NOT_ATTEMPTED,
    OWNERSHIP_UNAVAILABLE,
    ACKNOWLEDGED,
    CONFLICT,
}

/** One bounded pass over all recorded transfers, including previous acknowledgements/conflicts. */
data class LegacyProgressReconciliation(
    val acknowledged: Int,
    val conflicts: Int,
    val deferred: Int,
)
