package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintLiveReply
import me.manga.kira.domain.model.feedback.ComplaintReplyDraft
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission

/**
 * Typed live reply verbs on the same owner/issuer as [ComplaintReportRepository]. Its recovery port
 * reconciles all supported owner operations and owns every pending handle/reset prompt; no second authority exists.
 * Cancellation never authorizes slot deletion, key replacement, or automatic resubmission.
 */
interface ComplaintReplyRepository {
    /** Capture IDs/current diagnostics and normalize once, without persistence or network work. */
    suspend fun prepare(draft: ComplaintReplyDraft): AppResult<ComplaintReplyPreparation>

    /** Submit this exact live reply after bounded metadata-only reconciliation in the shared lane. */
    suspend fun submit(reply: ComplaintLiveReply): AppResult<ComplaintReportSubmission>

    /** Explicit retry retains the original parent, new ID, key, body and metadata. */
    suspend fun retry(reply: ComplaintLiveReply): AppResult<ComplaintReportAttempt>
}
