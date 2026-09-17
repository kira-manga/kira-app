package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.core.result.map
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Confirmation
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintRecoveryPrompt
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportDraft
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import me.manga.kira.domain.repository.ComplaintInstallationRecoveryRepository
import me.manga.kira.domain.repository.ComplaintReportRepository

/**
 * Thin in-memory consumer of the existing report producer, not a second transport or action registry.
 * All verbs and their guards share one issuer/coordinator; splitting them would fragment handle ownership.
 */
@Suppress("TooManyFunctions")
internal class BackendComplaintReportRepository(
    private val coordinator: InstallationCredentialCoordinator,
    private val backend: BackendFeedbackRepository,
    private val inputs: ComplaintReportInputs,
) : ComplaintReportRepository,
    ComplaintInstallationRecoveryRepository {
    private val issuer = ReportConsumerIssuer()

    override suspend fun prepare(draft: ComplaintReportDraft): AppResult<ComplaintReportPreparation> =
        access {
            val observed = coordinator.beginReconciliation()
            if (observed !is Outcome.Success) {
                return@access AppResult.Success(
                    ComplaintReportPreparation.Blocked(reportLocalFailure(observed).consumerResult()),
                )
            }
            when (val captured = capture(draft, observed.value)) {
                null ->
                    AppResult.Success(
                        ComplaintReportPreparation.Blocked(reportUnavailable(Block.INVALID_CANDIDATE).consumerResult()),
                    )
                is ComplaintReportRequestResult.Rejected -> AppResult.Success(captured.consumerResult())
                is ComplaintReportRequestResult.Accepted -> publishPrepared(captured.request, observed.value)
            }
        }

    override suspend fun submit(report: ComplaintLiveReport): AppResult<ComplaintReportSubmission> =
        access {
            val live = liveHandle(report) ?: return@access invalidHandle()
            if (!live.claimSubmission()) return@access invalidHandle()
            backend.submit(live.request, live.origin).map {
                ComplaintReportSubmission(
                    live.remember(it.attempt.consumerResult(issuer)),
                    it.recovery.consumerResult(issuer),
                )
            }
        }

    override suspend fun retry(report: ComplaintLiveReport): AppResult<ComplaintReportAttempt> =
        access {
            val live = liveHandle(report) ?: return@access invalidHandle()
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

    private suspend fun requestPrompt(request: suspend () -> AppResult<Confirmation>): AppResult<ComplaintRecoveryPrompt> {
        var issued: ReportPromptHandle? = null
        var delivered = false
        return try {
            val result =
                access {
                    request().map {
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

    /** Both platform suppliers and all existing text normalization run outside coordinator serialization. */
    private fun capture(
        draft: ComplaintReportDraft,
        origin: ReconciliationPermit,
    ): ComplaintReportRequestResult? {
        val ids = inputs.identifiers()
        if (ids.clientId == ids.idempotencyKey) return null
        val identity =
            ComplaintReportIdentity.checked(ids.clientId, ids.idempotencyKey, origin.record.material.dataScopeId)
        return identity?.let {
            ComplaintReportRequest.normalize(it, draft.type, draft.subject, draft.body, inputs.metadata())
        }
    }

    private suspend fun publishPrepared(
        request: ComplaintReportRequest,
        origin: ReconciliationPermit,
    ): AppResult<ComplaintReportPreparation> =
        when (val checked = coordinator.applyReconciliationIfCurrent(origin) {}) {
            is Outcome.Success ->
                AppResult.Success(ComplaintReportPreparation.Ready(ReportLiveHandle(issuer, request, origin)))
            else -> AppResult.Success(ComplaintReportPreparation.Blocked(reportLocalFailure(checked).consumerResult()))
        }

    private fun liveHandle(report: ComplaintLiveReport): ReportLiveHandle? =
        (report as? ReportLiveHandle)?.takeIf {
            it.issuer === issuer
        }

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
