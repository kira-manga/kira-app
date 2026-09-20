package me.manga.kira.domain.usecase.feedback

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintEditDraft
import me.manga.kira.domain.model.feedback.ComplaintEditPreparation
import me.manga.kira.domain.model.feedback.ComplaintLiveEdit
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import me.manga.kira.domain.repository.ComplaintEditRepository

/** Dormant edit verbs; recovery belongs to the same owner's [ComplaintReportRecoveryActions]. */
class ComplaintEditActions(
    val prepare: PrepareComplaintEditUseCase,
    val submit: SubmitComplaintEditUseCase,
    val retry: RetryComplaintEditUseCase,
)

/** Validate and capture a live edit without storage, enrollment or dispatch. */
class PrepareComplaintEditUseCase(
    private val repository: ComplaintEditRepository,
) {
    suspend operator fun invoke(draft: ComplaintEditDraft): AppResult<ComplaintEditPreparation> = repository.prepare(draft)
}

/** Send the exact edit; an uncertain result retains its no-prose pending evidence. */
class SubmitComplaintEditUseCase(
    private val repository: ComplaintEditRepository,
) {
    suspend operator fun invoke(edit: ComplaintLiveEdit): AppResult<ComplaintReportSubmission> = repository.submit(edit)
}

/** Reconcile the same live operation before any explicitly permitted same-key retry. */
class RetryComplaintEditUseCase(
    private val repository: ComplaintEditRepository,
) {
    suspend operator fun invoke(edit: ComplaintLiveEdit): AppResult<ComplaintReportAttempt> = repository.retry(edit)
}
