package me.manga.kira.domain.usecase.feedback

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintRecoveryPrompt
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.repository.ComplaintReportRepository

/** Explicit metadata recovery and local-reset consent verbs; no destructive cleanup callback. */
class ComplaintReportRecoveryActions(
    val reconcile: ReconcileComplaintReportsUseCase,
    val cancelPrepared: CancelPreparedComplaintReportUseCase,
    val requestRecovery: RequestComplaintReportRecoveryUseCase,
    val cancelRecovery: CancelComplaintReportRecoveryUseCase,
    val confirmRecovery: ConfirmComplaintReportRecoveryUseCase,
)

/** Observe retained metadata without reconstructing or sending prose. */
class ReconcileComplaintReportsUseCase(
    private val repository: ComplaintReportRepository,
) {
    suspend operator fun invoke(): AppResult<ComplaintReportRecovery> = repository.reconcile()
}

/** Cancel only a freshly checked exact unsent record at the user's request. */
class CancelPreparedComplaintReportUseCase(
    private val repository: ComplaintReportRepository,
) {
    suspend operator fun invoke(report: ComplaintPendingReport): AppResult<Unit> = repository.cancelPrepared(report)
}

/** Request explicit warning for local installation reset, never remote erasure. */
class RequestComplaintReportRecoveryUseCase(
    private val repository: ComplaintReportRepository,
) {
    suspend operator fun invoke(report: ComplaintPendingReport): AppResult<ComplaintRecoveryPrompt> =
        repository.requestRecovery(
            report,
        )
}

/** Dismiss this exact process-local warning, without resetting anything. */
class CancelComplaintReportRecoveryUseCase(
    private val repository: ComplaintReportRepository,
) {
    suspend operator fun invoke(prompt: ComplaintRecoveryPrompt): AppResult<Unit> = repository.cancelRecovery(prompt)
}

/** Confirm the user's warned local reset, making no server-erasure claim. */
class ConfirmComplaintReportRecoveryUseCase(
    private val repository: ComplaintReportRepository,
) {
    suspend operator fun invoke(prompt: ComplaintRecoveryPrompt): AppResult<Unit> = repository.confirmRecovery(prompt)
}
