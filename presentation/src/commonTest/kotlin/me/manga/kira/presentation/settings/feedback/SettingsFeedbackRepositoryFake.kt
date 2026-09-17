package me.manga.kira.presentation.settings.feedback

import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintRecoveryPrompt
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportDraft
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportObservation
import me.manga.kira.domain.model.feedback.ComplaintReportPending
import me.manga.kira.domain.model.feedback.ComplaintReportPhase
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import me.manga.kira.domain.repository.ComplaintInstallationRecoveryRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.repository.ComplaintReportRepository
import me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase
import me.manga.kira.domain.usecase.feedback.CancelComplaintReportRecoveryUseCase
import me.manga.kira.domain.usecase.feedback.CancelPreparedComplaintReportUseCase
import me.manga.kira.domain.usecase.feedback.ComplaintInstallationRecoveryActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportRecoveryActions
import me.manga.kira.domain.usecase.feedback.ConfirmComplaintReportRecoveryUseCase
import me.manga.kira.domain.usecase.feedback.PrepareComplaintReportUseCase
import me.manga.kira.domain.usecase.feedback.ReconcileComplaintReportsUseCase
import me.manga.kira.domain.usecase.feedback.RequestComplaintDeletionAbandonmentUseCase
import me.manga.kira.domain.usecase.feedback.RequestComplaintReportRecoveryUseCase
import me.manga.kira.domain.usecase.feedback.RequestUnreadableComplaintRecoveryUseCase
import me.manga.kira.domain.usecase.feedback.ResumeComplaintInstallationCleanupUseCase
import me.manga.kira.domain.usecase.feedback.RetryComplaintReportUseCase
import me.manga.kira.domain.usecase.feedback.SubmitComplaintReportUseCase

/** Ordinary domain-port fake; both recovery request sources deliberately share the same prompt fixture. */
@Suppress("TooManyFunctions")
internal class SettingsFeedbackRepositoryFake :
    ComplaintReportRepository,
    ComplaintInstallationRecoveryRepository {
    val live = SettingsTestLiveReport()
    val pending = SettingsTestPendingReport()
    var nextPrompt: ComplaintRecoveryPrompt = SettingsTestRecoveryPrompt()
    var recovery = ComplaintReportRecovery(emptyList())
    val drafts = mutableListOf<ComplaintReportDraft>()
    val submitted = mutableListOf<ComplaintLiveReport>()
    val retried = mutableListOf<ComplaintLiveReport>()
    val preparedCancelled = mutableListOf<ComplaintPendingReport>()
    val requested = mutableListOf<ComplaintPendingReport>()
    val dismissed = mutableListOf<ComplaintRecoveryPrompt>()
    val confirmed = mutableListOf<ComplaintRecoveryPrompt>()
    var reconciliations = 0
    var unreadableRequests = 0
    var abandonmentRequests = 0
    var cleanupChecks = 0
    var onPrepare: suspend (ComplaintReportDraft) -> AppResult<ComplaintReportPreparation> = {
        AppResult.Success(ComplaintReportPreparation.Ready(live))
    }
    var onSubmit: suspend (ComplaintLiveReport) -> AppResult<ComplaintReportSubmission> = {
        AppResult.Success(ComplaintReportSubmission(unresolved(), recovery))
    }
    var onRetry: suspend (ComplaintLiveReport) -> AppResult<ComplaintReportAttempt> = {
        AppResult.Success(ComplaintReportAttempt.Completed(ComplaintReportApplication.Applied("synthetic-id", 1)))
    }
    var onRequest: suspend (ComplaintPendingReport) -> AppResult<ComplaintRecoveryPrompt> = {
        AppResult.Success(nextPrompt)
    }
    var onUnreadable: suspend () -> AppResult<ComplaintRecoveryPrompt> = { AppResult.Success(nextPrompt) }
    var onAbandonment: suspend () -> AppResult<ComplaintRecoveryPrompt> = { AppResult.Success(nextPrompt) }
    var onConfirm: suspend (ComplaintRecoveryPrompt) -> AppResult<Unit> = { AppResult.Success(Unit) }
    var onCleanup: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }

    override suspend fun prepare(draft: ComplaintReportDraft): AppResult<ComplaintReportPreparation> {
        drafts += draft
        return onPrepare(draft)
    }

    override suspend fun submit(report: ComplaintLiveReport): AppResult<ComplaintReportSubmission> {
        submitted += report
        return onSubmit(report)
    }

    override suspend fun retry(report: ComplaintLiveReport): AppResult<ComplaintReportAttempt> {
        retried += report
        return onRetry(report)
    }

    override suspend fun reconcile(): AppResult<ComplaintReportRecovery> {
        reconciliations++
        return AppResult.Success(recovery)
    }

    override suspend fun cancelPrepared(report: ComplaintPendingReport): AppResult<Unit> {
        preparedCancelled += report
        return AppResult.Success(Unit)
    }

    override suspend fun requestRecovery(report: ComplaintPendingReport): AppResult<ComplaintRecoveryPrompt> {
        requested += report
        return onRequest(report)
    }

    override suspend fun cancelRecovery(prompt: ComplaintRecoveryPrompt): AppResult<Unit> {
        dismissed += prompt
        return AppResult.Success(Unit)
    }

    override suspend fun confirmRecovery(prompt: ComplaintRecoveryPrompt): AppResult<Unit> {
        confirmed += prompt
        return onConfirm(prompt)
    }

    override suspend fun requestUnreadableRecovery(): AppResult<ComplaintRecoveryPrompt> {
        unreadableRequests++
        return onUnreadable()
    }

    override suspend fun requestDeletionAbandonment(): AppResult<ComplaintRecoveryPrompt> {
        abandonmentRequests++
        return onAbandonment()
    }

    override suspend fun resumeCleanup(): AppResult<Unit> {
        cleanupChecks++
        return onCleanup()
    }

    fun unresolved(known: ComplaintReportApplication? = null): ComplaintReportAttempt.Unresolved =
        ComplaintReportAttempt.Unresolved(
            ComplaintReportFailure(AppError.Storage.Io()),
            known,
            ComplaintReportPending(pending, ComplaintReportPhase.MAY_HAVE_DISPATCHED),
        )

    fun preparedRecovery(): ComplaintReportRecovery =
        ComplaintReportRecovery(
            listOf(
                ComplaintReportObservation(
                    ComplaintReportPending(pending, ComplaintReportPhase.PREPARED),
                    ComplaintReportAttempt.Unresolved(
                        ComplaintReportFailure(
                            AppError.Platform.FeatureUnavailable("complaint_report"),
                            ComplaintReportBlock.LIVE_REQUEST_REQUIRED,
                        ),
                    ),
                ),
            ),
        )
}

internal fun SettingsFeedbackRepositoryFake.viewModel(
    entry: SettingsFeedbackEntry = SettingsFeedbackEntry.General,
    history: ComplaintListRepository = SettingsFeedbackHistoryFake(),
): SettingsFeedbackViewModel =
    SettingsFeedbackViewModel(
        ComplaintReportActions(
            PrepareComplaintReportUseCase(this),
            SubmitComplaintReportUseCase(this),
            RetryComplaintReportUseCase(this),
        ),
        ComplaintReportRecoveryActions(
            ReconcileComplaintReportsUseCase(this),
            CancelPreparedComplaintReportUseCase(this),
            RequestComplaintReportRecoveryUseCase(this),
            CancelComplaintReportRecoveryUseCase(this),
            ConfirmComplaintReportRecoveryUseCase(this),
        ),
        ObserveUserComplaintsUseCase(history),
        ComplaintInstallationRecoveryActions(
            RequestUnreadableComplaintRecoveryUseCase(this),
            RequestComplaintDeletionAbandonmentUseCase(this),
            ResumeComplaintInstallationCleanupUseCase(this),
        ),
        entry,
    )

/** Only the existing domain history port is replaced; the actual one-shot use case is used by the VM. */
internal class SettingsFeedbackHistoryFake : ComplaintListRepository {
    var calls = 0
    var result: AppResult<ComplaintHistory> = AppResult.Success(ComplaintHistory.Backend(emptyList(), emptyList()))
    var onLoad: suspend () -> AppResult<ComplaintHistory> = { result }

    override suspend fun loadUserComplaints(): AppResult<ComplaintHistory> {
        calls++
        return onLoad()
    }
}

internal class SettingsTestLiveReport : ComplaintLiveReport {
    override fun toString(): String = "TestLiveReport(redacted)"
}

internal class SettingsTestPendingReport : ComplaintPendingReport {
    override fun toString(): String = "TestPendingReport(redacted)"
}

internal class SettingsTestRecoveryPrompt : ComplaintRecoveryPrompt {
    override fun toString(): String = "TestRecoveryPrompt(redacted)"
}
