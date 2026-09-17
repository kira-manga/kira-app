package me.manga.kira.domain.usecase.feedback

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportDraft
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import me.manga.kira.domain.repository.ComplaintReportRepository

/** Explicit live-only report actions for the Settings subfeature, assembled at the composition root. */
class ComplaintReportActions(
    val prepare: PrepareComplaintReportUseCase,
    val submit: SubmitComplaintReportUseCase,
    val retry: RetryComplaintReportUseCase,
)

/** Observe and capture one normalized live request without writing or dispatching. */
class PrepareComplaintReportUseCase(
    private val repository: ComplaintReportRepository,
) {
    suspend operator fun invoke(draft: ComplaintReportDraft): AppResult<ComplaintReportPreparation> = repository.prepare(draft)
}

/** Submit the exact already-prepared live request. */
class SubmitComplaintReportUseCase(
    private val repository: ComplaintReportRepository,
) {
    suspend operator fun invoke(report: ComplaintLiveReport): AppResult<ComplaintReportSubmission> = repository.submit(report)
}

/** Retry an ambiguous live action without reallocating IDs or normalizing again. */
class RetryComplaintReportUseCase(
    private val repository: ComplaintReportRepository,
) {
    suspend operator fun invoke(report: ComplaintLiveReport): AppResult<ComplaintReportAttempt> = repository.retry(report)
}
