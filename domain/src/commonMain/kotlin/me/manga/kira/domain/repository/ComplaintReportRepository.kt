package me.manga.kira.domain.repository

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintRecoveryPrompt
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportDraft
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission

/**
 * Live report/recovery aggregate, separate from legacy feedback writes. Handles are issuer-bound and
 * memory-only; every operation is freshly coordinated. Cancellation stops only the caller's work and
 * never authorizes pending deletion, a new key, or a reset. Prose/metadata are never persisted locally.
 */
interface ComplaintReportRepository {
    /** Capture and normalize once against an existing installation, without dispatch or enrollment. */
    suspend fun prepare(draft: ComplaintReportDraft): AppResult<ComplaintReportPreparation>

    /** First dispatch of this exact live action, including bounded metadata-only reconciliation. */
    suspend fun submit(report: ComplaintLiveReport): AppResult<ComplaintReportSubmission>

    /** Manual retry of the same request/key only; missing pending metadata is not CREATE permission. */
    suspend fun retry(report: ComplaintLiveReport): AppResult<ComplaintReportAttempt>

    /** Metadata-only startup/manual recovery; never reconstructs prose or creates a report. */
    suspend fun reconcile(): AppResult<ComplaintReportRecovery>

    /** Explicit cancellation of this exact still-PREPARED record; not coroutine cleanup. */
    suspend fun cancelPrepared(report: ComplaintPendingReport): AppResult<Unit>

    /** Request a warning to reset the whole local installation and its pending state, not server erasure. */
    suspend fun requestRecovery(report: ComplaintPendingReport): AppResult<ComplaintRecoveryPrompt>

    /** Dismiss only this exact outstanding warning; stale prompts cannot cancel a newer warning. */
    suspend fun cancelRecovery(prompt: ComplaintRecoveryPrompt): AppResult<Unit>

    /** Explicitly confirm only this warning; successful local reset makes no remote erasure claim. */
    suspend fun confirmRecovery(prompt: ComplaintRecoveryPrompt): AppResult<Unit>
}
