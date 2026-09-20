package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintRecoveryPrompt

/**
 * Explicit local installation recovery, using the report owner's existing opaque consent tokens.
 * These requests never infer eligibility from a prior error, enroll, submit or initiate server deletion.
 * The same owner's [ComplaintReportRepository] confirms/cancels each exact returned prompt.
 */
interface ComplaintInstallationRecoveryRepository {
    /** Freshly check unreadable local evidence and request a warning; do not erase anything yet. */
    suspend fun requestUnreadableRecovery(): AppResult<ComplaintRecoveryPrompt>

    /** Request a warning for the exact existing deletion-pending identity; never begin a deletion. */
    suspend fun requestDeletionAbandonment(): AppResult<ComplaintRecoveryPrompt>

    /**
     * Continue only existing durable cleanup authority. Success can be an ACTIVE no-op, not evidence
     * that identity or server data was erased. This action never enrolls a replacement identity.
     */
    suspend fun resumeCleanup(): AppResult<Unit>
}
