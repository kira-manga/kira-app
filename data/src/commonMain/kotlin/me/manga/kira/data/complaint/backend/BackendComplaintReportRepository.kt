package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.result.map
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Confirmation
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.domain.model.feedback.ComplaintEditDraft
import me.manga.kira.domain.model.feedback.ComplaintEditPreparation
import me.manga.kira.domain.model.feedback.ComplaintLiveEdit
import me.manga.kira.domain.model.feedback.ComplaintLiveOwnerDelete
import me.manga.kira.domain.model.feedback.ComplaintLiveReply
import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteDraft
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeletePreparation
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintRecoveryPrompt
import me.manga.kira.domain.model.feedback.ComplaintReplyDraft
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportDraft
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import me.manga.kira.domain.repository.ComplaintEditRepository
import me.manga.kira.domain.repository.ComplaintInstallationRecoveryRepository
import me.manga.kira.domain.repository.ComplaintOwnerDeleteRepository
import me.manga.kira.domain.repository.ComplaintReplyRepository
import me.manga.kira.domain.repository.ComplaintReportRepository

/**
 * Thin owner-write consumer of the existing producer, not a second transport or action registry.
 * All verbs and their guards share one issuer/coordinator; splitting them would fragment handle ownership.
 */
@Suppress("TooManyFunctions")
internal class BackendComplaintReportRepository(
    private val coordinator: InstallationCredentialCoordinator,
    private val backend: BackendFeedbackRepository,
    inputs: ComplaintReportInputs,
) : ComplaintReportRepository,
    ComplaintReplyRepository,
    ComplaintEditRepository,
    ComplaintOwnerDeleteRepository,
    ComplaintInstallationRecoveryRepository {
    private val issuer = ReportConsumerIssuer()
    private val preparations = ComplaintReportPreparations(coordinator, inputs, issuer)

    override suspend fun prepare(draft: ComplaintReportDraft): AppResult<ComplaintReportPreparation> =
        access { preparations.prepare(draft) }

    override suspend fun submit(report: ComplaintLiveReport): AppResult<ComplaintReportSubmission> = submitLive(
        liveHandle(report),
    )

    override suspend fun retry(report: ComplaintLiveReport): AppResult<ComplaintReportAttempt> = retryLive(
        liveHandle(report),
    )

    override suspend fun prepare(draft: ComplaintReplyDraft): AppResult<ComplaintReplyPreparation> =
        access { preparations.prepare(draft) }

    override suspend fun submit(reply: ComplaintLiveReply): AppResult<ComplaintReportSubmission> = submitLive(
        replyHandle(reply),
    )

    override suspend fun retry(reply: ComplaintLiveReply): AppResult<ComplaintReportAttempt> = retryLive(
        replyHandle(reply),
    )

    override suspend fun prepare(draft: ComplaintEditDraft): AppResult<ComplaintEditPreparation> =
        access { preparations.prepare(draft) }

    override suspend fun submit(edit: ComplaintLiveEdit): AppResult<ComplaintReportSubmission> = submitLive(editHandle(edit))

    override suspend fun retry(edit: ComplaintLiveEdit): AppResult<ComplaintReportAttempt> = retryLive(editHandle(edit))

    override suspend fun prepare(draft: ComplaintOwnerDeleteDraft): AppResult<ComplaintOwnerDeletePreparation> =
        access { preparations.prepare(draft) }

    override suspend fun submit(deletion: ComplaintLiveOwnerDelete): AppResult<ComplaintReportSubmission> =
        submitLive(ownerDeleteHandle(deletion))

    override suspend fun retry(deletion: ComplaintLiveOwnerDelete): AppResult<ComplaintReportAttempt> =
        retryLive(ownerDeleteHandle(deletion))

    private suspend fun submitLive(candidate: ComplaintOwnerLiveHandle?): AppResult<ComplaintReportSubmission> =
        access {
            val live = candidate ?: return@access invalidHandle()
            if (!live.claimSubmission()) return@access invalidHandle()
            backend.submit(live.request, live.origin).map {
                ComplaintReportSubmission(
                    live.remember(it.attempt.consumerResult(issuer)),
                    it.recovery.consumerResult(issuer),
                )
            }
        }

    private suspend fun retryLive(candidate: ComplaintOwnerLiveHandle?): AppResult<ComplaintReportAttempt> =
        access {
            val live = candidate ?: return@access invalidHandle()
            if (!live.canRetry()) return@access invalidHandle()
            backend.retry(live.request, live.origin).map { live.remember(it.consumerResult(issuer)) }
        }

    override suspend fun reconcile(): AppResult<ComplaintReportRecovery> =
        access {
            backend.reconcile().map { it.consumerResult(issuer) }
        }

    override suspend fun cancelPrepared(report: ComplaintPendingReport): AppResult<Unit> =
        access {
            val pending = pendingHandle(report) ?: return@access invalidHandle()
            backend.cancelPrepared(pending.observation.slot, pending.observation.permit)
        }

    override suspend fun requestRecovery(report: ComplaintPendingReport): AppResult<ComplaintRecoveryPrompt> =
        requestPrompt {
            val pending = pendingHandle(report) ?: return@requestPrompt invalidHandle()
            backend.requestRecovery(pending.observation.slot, pending.observation.permit)
        }

    override suspend fun requestUnreadableRecovery(): AppResult<ComplaintRecoveryPrompt> =
        requestPrompt {
            coordinator.requestRecovery(RecoveryIntent.Unreadable).localRecoveryResult()
        }

    override suspend fun requestDeletionAbandonment(): AppResult<ComplaintRecoveryPrompt> =
        requestPrompt {
            when (val deletion = coordinator.pendingDeletion()) {
                is Outcome.Success ->
                    coordinator.requestRecovery(RecoveryIntent.Abandon(deletion.value)).localRecoveryResult()
                else -> AppResult.Failure(reportLocalFailure(deletion).error)
            }
        }

    override suspend fun resumeCleanup(): AppResult<Unit> =
        access {
            coordinator.resumeCleanup().localRecoveryResult()
        }

    private suspend fun requestPrompt(call: suspend () -> AppResult<Confirmation>): AppResult<ComplaintRecoveryPrompt> {
        var issued: ReportPromptHandle? = null
        var delivered = false
        return try {
            val result =
                access {
                    call().map {
                        ReportPromptHandle(issuer, it).also { prompt -> issued = prompt }
                    }
                }
            delivered = result is AppResult.Success
            result
        } finally {
            if (!delivered) issued?.let { withContext(NonCancellable) { backend.cancelRecovery(it.confirmation) } }
        }
    }

    override suspend fun cancelRecovery(prompt: ComplaintRecoveryPrompt): AppResult<Unit> =
        access {
            val expected = promptHandle(prompt) ?: return@access invalidHandle()
            backend.cancelRecovery(expected.confirmation)
        }

    override suspend fun confirmRecovery(prompt: ComplaintRecoveryPrompt): AppResult<Unit> =
        access {
            val expected = promptHandle(prompt) ?: return@access invalidHandle()
            backend.confirmRecovery(expected.confirmation)
        }

    /** The owner closes its work lane separately. This fence never cancels another caller or clears storage. */
    fun close() = issuer.close()

    private fun liveHandle(report: ComplaintLiveReport): ReportLiveHandle? =
        (report as? ReportLiveHandle)?.takeIf {
            it.issuer === issuer
        }

    private fun replyHandle(reply: ComplaintLiveReply): ReplyLiveHandle? =
        (reply as? ReplyLiveHandle)?.takeIf {
            it.issuer === issuer
        }

    private fun editHandle(edit: ComplaintLiveEdit): EditLiveHandle? =
        (edit as? EditLiveHandle)?.takeIf { it.issuer === issuer }

    private fun ownerDeleteHandle(deletion: ComplaintLiveOwnerDelete): OwnerDeleteLiveHandle? =
        (deletion as? OwnerDeleteLiveHandle)?.takeIf { it.issuer === issuer }

    private fun pendingHandle(report: ComplaintPendingReport): ReportPendingHandle? =
        (report as? ReportPendingHandle)?.takeIf { it.issuer === issuer }

    private fun promptHandle(prompt: ComplaintRecoveryPrompt): ReportPromptHandle? =
        (prompt as? ReportPromptHandle)?.takeIf { it.issuer === issuer }

    private suspend fun <T> access(action: suspend () -> AppResult<T>): AppResult<T> =
        try {
            currentCoroutineContext().ensureActive()
            if (!issuer.isOpen()) {
                invalidHandle()
            } else {
                val result = action()
                currentCoroutineContext().ensureActive()
                if (issuer.isOpen()) result else invalidHandle()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AppResult.Failure(AppError.Unexpected("complaint_report_failed"))
        }
}

private fun <T> invalidHandle(): AppResult<T> = AppResult.Failure(AppError.Auth.Forbidden())

private fun <T> Outcome<T>.localRecoveryResult(): AppResult<T> =
    when (this) {
        is Outcome.Success -> AppResult.Success(value)
        else -> AppResult.Failure(reportLocalFailure(this).error)
    }
