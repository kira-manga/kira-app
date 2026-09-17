package me.manga.kira.domain.repository

import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult

/**
 * Explicit delete-all intent only, never an observation or graph-construction side effect.
 * A failure never authorizes a new identity, local reset, or abandonment of an existing deletion.
 */
interface ComplaintInstallationDeletionRepository {
    /** Starts from an existing active identity, after a real fresh session and durable local intent. */
    suspend fun startDeletion(): AppResult<ComplaintInstallationDeletionOutcome>

    /** Retries only the already durable request; never enrolls, refreshes a session or allocates a key. */
    suspend fun continueDeletion(): AppResult<ComplaintInstallationDeletionOutcome>
}

/** Content-free observations, not input capabilities for credential or pending-store deletion. */
sealed interface ComplaintInstallationDeletionOutcome {
    /** Qualified terminal response and checked local cleanup; not independent remote journal proof. */
    data object Completed : ComplaintInstallationDeletionOutcome

    /** The same durable intent remains. A 202 delay is server supplied; errors do not imply no dispatch. */
    data class Pending(
        val retryAfterSeconds: Int? = null,
        val error: AppError? = null,
    ) : ComplaintInstallationDeletionOutcome
}
