package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.domain.repository.ComplaintInstallationDeletionOutcome
import me.manga.kira.domain.repository.ComplaintInstallationDeletionRepository
import kotlin.time.TimeSource

/** Explicit producer only. Construction is inert; continuation cannot reach session or key suppliers. */
internal class BackendInstallationDeletionRepository(
    private val coordinator: InstallationCredentialCoordinator,
    private val sessions: InstallationSessionManager,
    private val http: InstallationDeletionHttp,
    private val inputs: InstallationDeletionInputs,
    private val works: InstallationDeletionWorks,
) : ComplaintInstallationDeletionRepository {
    private var retry: InstallationDeletionRetry? = null

    override suspend fun startDeletion(): AppResult<ComplaintInstallationDeletionOutcome> =
        withWork { work ->
            val admitted = coordinator.beginDeletionStart(work)
            if (admitted !is Outcome.Success) return@withWork AppResult.Failure(deletionLocalError(admitted))
            val start = admitted.value
            val ticket =
                when (val fresh = sessions.freshDeletionSession(start)) {
                    is InstallationDeletionSessionResult.Ready -> fresh.ticket
                    is InstallationDeletionSessionResult.Failed ->
                        return@withWork AppResult.Failure(deletionSessionError(fresh.result))
                }
            currentCoroutineContext().ensureActive()
            val key = inputs.nextKey()
            when (val committed = coordinator.commitDeletionStart(start, ticket, sessions, key)) {
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
                    action(work)
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

/** Key supplier runs once only after an explicit successful fresh-session start; no identity generation. */
internal class InstallationDeletionInputs(
    val nextKey: () -> String,
    val clock: TimeSource = TimeSource.Monotonic,
)

private fun deletionUnexpected(): AppError = AppError.Unexpected("complaint_installation_deletion_failed")
