package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Confirmation
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
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

    suspend fun submit(
        report: ComplaintReportRequest,
        expected: ReconciliationPermit? = null,
    ): AppResult<ReportSubmission> =
        withWork { work ->
            val recovery = reconciler.reconcile(work, expected)
            val stopped = recovery.stopped
            val attempt =
                if (stopped != null) {
                    ReportAttempt.Unresolved(report, stopped)
                } else {
                    when (val admitted = coordinator.beginReportAction(work, ReportStart.New(report), expected)) {
                        is Outcome.Success -> execution.create(admitted.value).attempt
                        else -> ReportAttempt.Unresolved(report, reportLocalFailure(admitted))
                    }
                }
            AppResult.Success(ReportSubmission(attempt, recovery))
        }

    /** Missing metadata is not permission to turn a retry into a new action with any key. */
    suspend fun retry(
        report: ComplaintReportRequest,
        expected: ReconciliationPermit? = null,
    ): AppResult<ReportAttempt> =
        withWork { work ->
            val inventory = coordinator.reportInventory(work, expected)
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
                when (val admitted = coordinator.beginReportAction(work, ReportStart.Retained(slot, report), expected)) {
                    is Outcome.Success -> execution.status(admitted.value, retryLive = true).attempt
                    else -> ReportAttempt.Unresolved(report, reportLocalFailure(admitted))
                }
            AppResult.Success(attempt)
        }

    /** Startup and manual reads remain usable with sixteen retained records; no CREATE is constructed. */
    suspend fun reconcile(): AppResult<ReportRecovery> = withWork { work -> AppResult.Success(reconciler.reconcile(work)) }

    /** Explicit unsent cancellation alone may delete PREPARED; cancelCurrent/finally never do so. */
    suspend fun cancelPrepared(
        slot: PendingComplaintSlot,
        expected: ReconciliationPermit? = null,
    ): AppResult<Unit> =
        withWork { work ->
            when (val admitted = coordinator.beginReportAction(work, ReportStart.Retained(slot), expected)) {
                is Outcome.Success -> coordinator.cancelPreparedReport(admitted.value).unitResult()
                else -> AppResult.Failure(reportLocalFailure(admitted).error)
            }
        }

    /** Exact, non-destructive consent dismissal must not be blocked by another caller's report lane. */
    suspend fun cancelRecovery(expected: Confirmation): AppResult<Unit> =
        try {
            coordinator.cancelRecovery(expected).unitResult()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppResult.Failure(AppError.Unexpected("complaint_report_failed"))
        }

    suspend fun confirmRecovery(expected: Confirmation): AppResult<Unit> = withWork { coordinator.confirmRecovery(expected).unitResult() }

    /** The prompt caller survives cancellation of its registered, no-HTTP worker by consent issuance. */
    suspend fun requestRecovery(
        slot: PendingComplaintSlot,
        expected: ReconciliationPermit? = null,
    ): AppResult<Confirmation> {
        val delivery = ReportRecoveryDelivery()
        return try {
            coroutineScope { recoveryPrompt(slot, expected, delivery) }
        } catch (cancelled: CancellationException) {
            delivery.cancel(coordinator)
            throw cancelled
        } catch (_: Exception) {
            delivery.cancel(coordinator)
            AppResult.Failure(AppError.Unexpected("complaint_report_failed"))
        }
    }

    private suspend fun recoveryPrompt(
        slot: PendingComplaintSlot,
        expected: ReconciliationPermit?,
        delivery: ReportRecoveryDelivery,
    ): AppResult<Confirmation> {
        val job = Job(currentCoroutineContext().job)
        val work = works.begin(job)
        if (work == null) {
            job.cancel()
            return AppResult.Failure(reportUnavailable().error)
        }
        return try {
            val admitted = coordinator.beginReportAction(work, ReportStart.Retained(slot), expected)
            if (admitted !is Outcome.Success) {
                AppResult.Failure(reportLocalFailure(admitted).error)
            } else {
                issueRecovery(admitted.value, delivery)
            }
        } finally {
            coordinator.finishReport(work)
            job.cancel()
        }
    }

    private suspend fun issueRecovery(
        binding: ReportActionBinding,
        delivery: ReportRecoveryDelivery,
    ): AppResult<Confirmation> =
        when (val prompt = coordinator.requestReportRecovery(binding)) {
            is Outcome.Success -> {
                delivery.confirmation = prompt.value
                AppResult.Success(prompt.value)
            }
            else -> AppResult.Failure(reportLocalFailure(prompt).error)
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

/** Cancellation may race prompt delivery. Dismiss only the exact undelivered consent, never reset. */
private class ReportRecoveryDelivery {
    var confirmation: Confirmation? = null

    suspend fun cancel(coordinator: InstallationCredentialCoordinator) {
        confirmation?.let { withContext(NonCancellable) { coordinator.cancelRecovery(it) } }
    }
}

private fun Outcome<*>.unitResult(): AppResult<Unit> =
    when (this) {
        is Outcome.Success -> AppResult.Success(Unit)
        else -> AppResult.Failure(reportLocalFailure(this).error)
    }
