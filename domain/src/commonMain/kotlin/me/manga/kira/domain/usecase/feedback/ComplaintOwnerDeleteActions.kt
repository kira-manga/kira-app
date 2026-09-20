package me.manga.kira.domain.usecase.feedback

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintLiveOwnerDelete
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteDraft
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeletePreparation
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import me.manga.kira.domain.repository.ComplaintOwnerDeleteRepository

/** Dormant single-delete verbs; mixed recovery and cancellation use [ComplaintReportRecoveryActions]. */
class ComplaintOwnerDeleteActions(
    val prepare: PrepareComplaintOwnerDeleteUseCase,
    val submit: SubmitComplaintOwnerDeleteUseCase,
    val retry: RetryComplaintOwnerDeleteUseCase,
)

/** Capture one live target/tag/key without enrollment, durable writes or dispatch. */
class PrepareComplaintOwnerDeleteUseCase(
    private val repository: ComplaintOwnerDeleteRepository,
) {
    suspend operator fun invoke(draft: ComplaintOwnerDeleteDraft): AppResult<ComplaintOwnerDeletePreparation> =
        repository.prepare(draft)
}

/** Submit the exact original live operation; ambiguity retains metadata-only pending evidence. */
class SubmitComplaintOwnerDeleteUseCase(
    private val repository: ComplaintOwnerDeleteRepository,
) {
    suspend operator fun invoke(deletion: ComplaintLiveOwnerDelete): AppResult<ComplaintReportSubmission> =
        repository.submit(deletion)
}

/** Reconcile before an explicitly permitted same-key retry, never a replacement deletion. */
class RetryComplaintOwnerDeleteUseCase(
    private val repository: ComplaintOwnerDeleteRepository,
) {
    suspend operator fun invoke(deletion: ComplaintLiveOwnerDelete): AppResult<ComplaintReportAttempt> =
        repository.retry(deletion)
}
