package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintLiveOwnerDelete
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteDraft
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeletePreparation
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission

/** Backend-only single-target deletion on the same issuer/write lane as [ComplaintReportRepository]. */
interface ComplaintOwnerDeleteRepository {
    /** Validate the original target/tag and capture one key, without durable writes, enrollment or dispatch. */
    suspend fun prepare(draft: ComplaintOwnerDeleteDraft): AppResult<ComplaintOwnerDeletePreparation>

    /** Submit only this original live operation after bounded metadata-only reconciliation. */
    suspend fun submit(deletion: ComplaintLiveOwnerDelete): AppResult<ComplaintReportSubmission>

    /** Explicit status-led same-action retry; never reconstruct, refresh the target tag or allocate a key. */
    suspend fun retry(deletion: ComplaintLiveOwnerDelete): AppResult<ComplaintReportAttempt>
}
