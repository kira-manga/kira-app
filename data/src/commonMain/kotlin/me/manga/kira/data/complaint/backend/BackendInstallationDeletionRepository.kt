package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.domain.repository.ComplaintInstallationDeletionObservation
import me.manga.kira.domain.repository.ComplaintInstallationDeletionOutcome
import me.manga.kira.domain.repository.ComplaintInstallationDeletionPrompt
import me.manga.kira.domain.repository.ComplaintInstallationDeletionRepository
import kotlin.time.TimeSource

/** One owner-bound consumer of the explicit producer. Continuation cannot reach session/key suppliers. */
internal class BackendInstallationDeletionRepository(
    private val coordinator: InstallationCredentialCoordinator,
    private val sessions: InstallationSessionManager,
    private val http: InstallationDeletionHttp,
    private val inputs: InstallationDeletionInputs,
    private val works: InstallationDeletionWorks,
) : ComplaintInstallationDeletionRepository {
    private var retry: InstallationDeletionRetry? = null

    override suspend fun observeDeletion(): AppResult<ComplaintInstallationDeletionObservation> =
        access { coordinator.observeDeletion().deletionResult() }

    override suspend fun requestDeletion(): AppResult<ComplaintInstallationDeletionPrompt> {
        var issued: DeletionPromptHandle? = null
        var delivered = false
        return try {
            val result =
                access {
                    when (val requested = coordinator.requestDeletion()) {
                        is Outcome.Success ->
                            AppResult.Success(
                                DeletionPromptHandle(this, requested.value).also { issued = it },
                            )
                        else -> AppResult.Failure(deletionLocalError(requested))
                    }
                }
            delivered = result is AppResult.Success
            result
        } finally {
            if (!delivered) {
                issued?.let { withContext(NonCancellable) { coordinator.cancelDeletion(it.confirmation) } }
            }
        }
    }

    /** Dismissal remains safe after owner close; it can only clear this exact still-outstanding slot. */
    override suspend fun cancelDeletion(prompt: ComplaintInstallationDeletionPrompt): AppResult<Unit> =
        try {
            val expected = ownedPrompt(prompt)
            if (expected == null) invalidDeletionPrompt() else coordinator.cancelDeletion(expected.confirmation).deletionResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppResult.Failure(deletionUnexpected())
        }

    override suspend fun confirmDeletion(prompt: ComplaintInstallationDeletionPrompt): AppResult<ComplaintInstallationDeletionOutcome> {
        val expected = ownedPrompt(prompt) ?: return invalidDeletionPrompt()
        return withWork { work -> start(coordinator.confirmDeletion(expected.confirmation, work)) }
    }

    /** Internal producer-fixture entry only. Consumers have no unbound start on the domain port. */
    internal suspend fun startDeletion(): AppResult<ComplaintInstallationDeletionOutcome> =
        withWork { work ->
            start(coordinator.beginDeletionStart(work))
        }

    /** Both paths use their already-bound start, never reread arbitrary state to create another binding. */
    private suspend fun start(admitted: Outcome<InstallationDeletionStart>): AppResult<ComplaintInstallationDeletionOutcome> {
        if (admitted !is Outcome.Success) return AppResult.Failure(deletionLocalError(admitted))
        val start = admitted.value
        val ticket =
            when (val fresh = sessions.freshDeletionSession(start)) {
                is InstallationDeletionSessionResult.Ready -> fresh.ticket
                is InstallationDeletionSessionResult.Failed ->
                    return AppResult.Failure(deletionSessionError(fresh.result))
            }
        currentCoroutineContext().ensureActive()
        val key = inputs.nextKey()
        return when (val committed = coordinator.commitDeletionStart(start, ticket, sessions, key)) {
            is Outcome.Success -> send(committed.value)
            else -> AppResult.Failure(deletionLocalError(committed))
        }
    }

    override suspend fun continueDeletion(): AppResult<ComplaintInstallationDeletionOutcome> =
        withWork { work ->
            when (val cleanup = coordinator.resumeDeletionCleanup(work)) {
                is Outcome.Success ->
                    if (cleanup.value == InstallationDeletionCleanup.COMPLETED) {
                        retry = null
                        return@withWork AppResult.Success(ComplaintInstallationDeletionOutcome.Completed)
                    }
                else -> return@withWork AppResult.Failure(deletionLocalError(cleanup))
            }
            when (val continued = coordinator.continueDeletion(work)) {
                is Outcome.Success -> send(continued.value)
                else -> AppResult.Failure(deletionLocalError(continued))
            }
        }

    private suspend fun send(binding: InstallationDeletionBinding): AppResult<ComplaintInstallationDeletionOutcome> {
        retry?.remaining(binding)?.let { seconds ->
            return AppResult.Success(ComplaintInstallationDeletionOutcome.Pending(retryAfterSeconds = seconds))
        }
        return try {
            when (val outcome = coordinator.dispatchDeletion(binding, http)) {
                is Outcome.Success -> {
                    val pending = outcome.value as? ComplaintInstallationDeletionOutcome.Pending
                    retry =
                        pending?.retryAfterSeconds?.let { InstallationDeletionRetry(binding.record, it, inputs.clock) }
                    AppResult.Success(outcome.value)
                }
                else ->
                    AppResult.Success(ComplaintInstallationDeletionOutcome.Pending(error = deletionLocalError(outcome)))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppResult.Success(ComplaintInstallationDeletionOutcome.Pending(error = deletionUnexpected()))
        }
    }

    private fun ownedPrompt(prompt: ComplaintInstallationDeletionPrompt): DeletionPromptHandle? =
        (prompt as? DeletionPromptHandle)?.takeIf { it.owner === this }

    /** Observation/warning reads have no work lane of their own, but retain the existing owner's close fence. */
    private suspend fun <T> access(action: suspend () -> AppResult<T>): AppResult<T> =
        try {
            currentCoroutineContext().ensureActive()
            if (works.isClosed || http.isClosed) {
                AppResult.Failure(deletionUnavailable())
            } else {
                val result = action()
                currentCoroutineContext().ensureActive()
                if (works.isClosed || http.isClosed) AppResult.Failure(deletionUnavailable()) else result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppResult.Failure(deletionUnexpected())
        }

    /** Lifetime serialization only; never a public authenticated callback or destructive finally block. */
    private suspend fun withWork(
        action: suspend (InstallationDeletionWork) -> AppResult<ComplaintInstallationDeletionOutcome>,
    ): AppResult<ComplaintInstallationDeletionOutcome> =
        try {
            coroutineScope {
                val work =
                    works.begin(currentCoroutineContext().job)
                        ?: return@coroutineScope AppResult.Failure(deletionUnavailable())
                try {
                    if (http.isClosed) AppResult.Failure(deletionUnavailable()) else action(work)
                } finally {
                    coordinator.finishDeletion(work)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppResult.Failure(deletionUnexpected())
        }
}

/** The concrete repository supplies provenance; the coordinator's single slot supplies exact consent. */
private class DeletionPromptHandle(
    val owner: BackendInstallationDeletionRepository,
    val confirmation: InstallationDeletionConfirmation,
) : ComplaintInstallationDeletionPrompt {
    override fun toString(): String = "ComplaintInstallationDeletionPrompt(redacted)"
}

private fun <T> Outcome<T>.deletionResult(): AppResult<T> =
    when (this) {
        is Outcome.Success -> AppResult.Success(value)
        else -> AppResult.Failure(deletionLocalError(this))
    }

private fun <T> invalidDeletionPrompt(): AppResult<T> = AppResult.Failure(AppError.Auth.Forbidden())

/** Key supplier runs once only after an explicit successful fresh-session start; no identity generation. */
internal class InstallationDeletionInputs(
    val nextKey: () -> String,
    val clock: TimeSource = TimeSource.Monotonic,
)

private fun deletionUnexpected(): AppError = AppError.Unexpected("complaint_installation_deletion_failed")
