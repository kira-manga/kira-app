package me.manga.kira.domain.repository

import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.ComplaintInstallationDeletionOutcome as DeletionOutcome

/**
 * Local observation and explicit, warned delete-all intent; construction is inert.
 * A failure never authorizes a new identity, local reset, or abandonment of an existing deletion.
 */
interface ComplaintInstallationDeletionRepository {
    /** Checked local reads only: never HTTP, enrollment, cleanup, key generation or a completion receipt. */
    suspend fun observeDeletion(): AppResult<ComplaintInstallationDeletionObservation>

    /** Capture one exact ACTIVE identity and pending inventory for a read-only, process-local warning. */
    suspend fun requestDeletion(): AppResult<ComplaintInstallationDeletionPrompt>

    /** Dismiss only this owner's exact outstanding warning; never cancel or remove a durable deletion. */
    suspend fun cancelDeletion(prompt: ComplaintInstallationDeletionPrompt): AppResult<Unit>

    /** Consume exact consent before a real fresh session, durable intent and the fixed delete-all request. */
    suspend fun confirmDeletion(prompt: ComplaintInstallationDeletionPrompt): AppResult<DeletionOutcome>

    /** Retries only the already durable request; never enrolls, refreshes a session or allocates a key. */
    suspend fun continueDeletion(): AppResult<DeletionOutcome>
}

/** Opaque, owner-bound, process-local consent. Implementing this interface does not create authority. */
interface ComplaintInstallationDeletionPrompt

/** Content-free local state, not cleanup authority or evidence of server erasure. Failure means unknown. */
sealed interface ComplaintInstallationDeletionObservation {
    data object Active : ComplaintInstallationDeletionObservation

    /** Both the credential and pending inventory were checked empty; not a remote-completion result. */
    data object Missing : ComplaintInstallationDeletionObservation

    /** Durable intent or a server-terminal cleanup marker remains; explicit continuation is required. */
    data object RemoteDeletionPending : ComplaintInstallationDeletionObservation

    data object LocalCleanupRequired : ComplaintInstallationDeletionObservation
}

/** Content-free observations, not input capabilities for credential or pending-store deletion. */
sealed interface ComplaintInstallationDeletionOutcome {
    /** Qualified terminal response and checked local cleanup; not independent remote journal proof. */
    data object Completed : DeletionOutcome

    /** The same durable intent remains. A 202 delay is server supplied; errors do not imply no dispatch. */
    data class Pending(
        val retryAfterSeconds: Int? = null,
        val error: AppError? = null,
    ) : DeletionOutcome
}
