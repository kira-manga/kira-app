package me.manga.kira.domain.usecase.feedback

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintLiveReply
import me.manga.kira.domain.model.feedback.ComplaintReplyDraft
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import me.manga.kira.domain.repository.ComplaintReplyRepository

/** Backend-only live reply verbs. Recovery uses the same owner's [ComplaintReportRecoveryActions]. */
class ComplaintReplyActions(
    val prepare: PrepareComplaintReplyUseCase,
    val submit: SubmitComplaintReplyUseCase,
    val retry: RetryComplaintReplyUseCase,
)

/** Validate/capture one reply without persistence, enrollment or sending. */
class PrepareComplaintReplyUseCase(
    private val repository: ComplaintReplyRepository,
) {
    suspend operator fun invoke(
        draft: ComplaintReplyDraft,
    ): AppResult<ComplaintReplyPreparation> = repository.prepare(draft)
}

/** Send only the exact live reply; ambiguous outcomes remain explicit and retain pending metadata. */
class SubmitComplaintReplyUseCase(
    private val repository: ComplaintReplyRepository,
) {
    suspend operator fun invoke(
        reply: ComplaintLiveReply,
    ): AppResult<ComplaintReportSubmission> = repository.submit(reply)
}

/** Retry an existing live reply through status; never manufacture a new operation. */
class RetryComplaintReplyUseCase(
    private val repository: ComplaintReplyRepository,
) {
    suspend operator fun invoke(reply: ComplaintLiveReply): AppResult<ComplaintReportAttempt> = repository.retry(reply)
}
