package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Confirmation
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.platform.storage.PendingComplaintSlot

/**
 * Dormant typed report port, deliberately not the legacy Result<Unit> adapter. The caller allocates
 * and normalizes a live request once; this owner neither invents keys nor persists/reconstructs prose.
 */
internal class BackendFeedbackRepository(
    private val coordinator: InstallationCredentialCoordinator,
    sessions: InstallationSessionManager,
    http: ComplaintMutationHttp,
    private val works: ReportWorkOwner,
) {
    private val execution = ComplaintReportExecution(coordinator, sessions, http)
    private val reconciler = ComplaintOperationReconciler(coordinator, execution)

    suspend fun submit(report: ComplaintReportRequest): AppResult<ReportSubmission> =
        withWork { work ->
            val recovery = reconciler.reconcile(work)
            val stopped = recovery.stopped
            val attempt =
                if (stopped != null) {
                    ReportAttempt.Unresolved(report, stopped)
                } else {
                    when (val admitted = coordinator.beginReportAction(work, ReportStart.New(report))) {
                        is Outcome.Success -> execution.create(admitted.value).attempt
                        else -> ReportAttempt.Unresolved(report, reportLocalFailure(admitted))
                    }
                }
            AppResult.Success(ReportSubmission(attempt, recovery))
        }

    /** Missing metadata is not permission to turn a retry into a new action with any key. */
    suspend fun retry(report: ComplaintReportRequest): AppResult<ReportAttempt> =
        withWork { work ->
            val inventory = coordinator.reportInventory(work)
            if (inventory !is Outcome.Success) {
                return@withWork AppResult.Success(ReportAttempt.Unresolved(report, reportLocalFailure(inventory)))
            }
            val slot =
                inventory.value.snapshot
                    .entries()
                    .singleOrNull { it.id == report.identity.key.value }
            if (slot == null) {
                return@withWork AppResult.Success(ReportAttempt.Unresolved(report, reportUnavailable(Block.MISSING)))
            }
            val attempt =
                when (val admitted = coordinator.beginReportAction(work, ReportStart.Retained(slot, report))) {
                    is Outcome.Success -> execution.status(admitted.value, retryLive = true).attempt
                    else -> ReportAttempt.Unresolved(report, reportLocalFailure(admitted))
                }
            AppResult.Success(attempt)
        }

    /** Startup and manual reads remain usable with sixteen retained records; no CREATE is constructed. */
    suspend fun reconcile(): AppResult<ReportRecovery> = withWork { work -> AppResult.Success(reconciler.reconcile(work)) }

    /** Explicit unsent cancellation alone may delete PREPARED; cancelCurrent/finally never do so. */
    suspend fun cancelPrepared(slot: PendingComplaintSlot): AppResult<Unit> =
        withWork { work ->
            when (val admitted = coordinator.beginReportAction(work, ReportStart.Retained(slot))) {
                is Outcome.Success -> coordinator.cancelPreparedReport(admitted.value).unitResult()
                else -> AppResult.Failure(reportLocalFailure(admitted).error)
            }
        }

    suspend fun cancelRecovery(expected: Confirmation): AppResult<Unit> = withWork { coordinator.cancelRecovery(expected).unitResult() }

    suspend fun confirmRecovery(expected: Confirmation): AppResult<Unit> = withWork { coordinator.confirmRecovery(expected).unitResult() }

    /** The prompt caller survives cancellation of its registered, no-HTTP worker by consent issuance. */
    suspend fun requestRecovery(slot: PendingComplaintSlot): AppResult<Confirmation> =
        try {
            coroutineScope { recoveryPrompt(slot) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppResult.Failure(AppError.Unexpected("complaint_report_failed"))
        }

    private suspend fun recoveryPrompt(slot: PendingComplaintSlot): AppResult<Confirmation> {
        val job = Job(currentCoroutineContext().job)
        val work = works.begin(job)
        if (work == null) {
            job.cancel()
            return AppResult.Failure(reportUnavailable().error)
        }
        return try {
            val admitted = coordinator.beginReportAction(work, ReportStart.Retained(slot))
            if (admitted !is Outcome.Success) {
                AppResult.Failure(reportLocalFailure(admitted).error)
            } else {
                when (val prompt = coordinator.requestReportRecovery(admitted.value)) {
                    is Outcome.Success -> AppResult.Success(prompt.value)
                    else -> AppResult.Failure(reportLocalFailure(prompt).error)
                }
            }
        } finally {
            coordinator.finishReport(work)
            job.cancel()
        }
    }

    fun cancelCurrent() = works.cancelCurrent()

    /** Lifetime serialization only, not an authenticated executor or request-construction callback. */
    private suspend fun <T> withWork(action: suspend (ReportWork) -> AppResult<T>): AppResult<T> =
        try {
            coroutineScope {
                val work =
                    works.begin(currentCoroutineContext().job)
                        ?: return@coroutineScope AppResult.Failure(reportUnavailable(Block.ACTION_IN_PROGRESS).error)
                try {
                    action(work)
                } finally {
                    coordinator.finishReport(work)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppResult.Failure(AppError.Unexpected("complaint_report_failed"))
        }
}

private fun Outcome<*>.unitResult(): AppResult<Unit> =
    when (this) {
        is Outcome.Success -> AppResult.Success(Unit)
        else -> AppResult.Failure(reportLocalFailure(this).error)
    }
