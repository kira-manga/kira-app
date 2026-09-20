package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintEditDraft
import me.manga.kira.domain.model.feedback.ComplaintEditPreparation
import me.manga.kira.domain.model.feedback.ComplaintLiveEdit
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission

/**
 * Backend-only owner-content edits on the same issuer/write lane as [ComplaintReportRepository].
 * That port owns mixed metadata recovery and every pending/reset handle. Cancellation grants no retry.
 */
interface ComplaintEditRepository {
    /** Capture one key and normalized replacements; never allocate a content ID or read diagnostics. */
    suspend fun prepare(draft: ComplaintEditDraft): AppResult<ComplaintEditPreparation>

    /** Submit only this exact live target/tag/key after bounded metadata-only reconciliation. */
    suspend fun submit(edit: ComplaintLiveEdit): AppResult<ComplaintReportSubmission>

    /** Explicit status-led retry only; never refresh the target tag, rebase the prose or allocate another key. */
    suspend fun retry(edit: ComplaintLiveEdit): AppResult<ComplaintReportAttempt>
}
