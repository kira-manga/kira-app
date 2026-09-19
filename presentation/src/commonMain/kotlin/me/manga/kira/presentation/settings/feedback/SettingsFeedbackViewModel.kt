package me.manga.kira.presentation.settings.feedback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintLiveReport
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintRecoveryPrompt
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportDraft
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import me.manga.kira.domain.repository.ComplaintInstallationDeletionOutcome
import me.manga.kira.domain.repository.ComplaintInstallationDeletionPrompt
import me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase
import me.manga.kira.domain.usecase.feedback.ComplaintInstallationActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportRecoveryActions
import me.manga.kira.presentation.mvi.MviViewModel

/** This VM owns report/recovery work; retry and history/cleanup checks never submit a replacement report. */
@Suppress("TooManyFunctions")
class SettingsFeedbackViewModel(
    private val actions: ComplaintReportActions,
    private val recoveryActions: ComplaintReportRecoveryActions,
    private val observeUserComplaints: ObserveUserComplaintsUseCase,
    private val installationActions: ComplaintInstallationActions,
    private val entry: SettingsFeedbackEntry = SettingsFeedbackEntry.General,
) : MviViewModel<SettingsFeedbackState, SettingsFeedbackIntent, SettingsFeedbackEffect>(entry.initialState()) {
    private var live: ComplaintLiveReport? = null
    private var latestAttempt: ComplaintReportAttempt? = null
    private var prompt: SettingsFeedbackPrompt? = null
    private var operation: Job? = null
    private var terminal = false
    private var closed = false

    init {
        launchSafely {
            try {
                awaitCancellation()
            } finally {
                closed = true
                withContext(NonCancellable) {
                    operation?.join()
                    dismissOwnedPrompt()
                    live = null
                    latestAttempt = null
                    updateState { entry.initialState() }
                }
            }
        }
        launchSafely { checkInstallation() }
    }

    @Suppress("CyclomaticComplexMethod") // Flat exhaustive intent dispatch; each action retains its own guard.
    override suspend fun handle(intent: SettingsFeedbackIntent) {
        when (intent) {
            is SettingsFeedbackIntent.ChangeCategory ->
                edit(fixedFields = true) { it.copy(type = intent.type, subject = intent.subject) }
            is SettingsFeedbackIntent.ChangeSubject -> edit(fixedFields = true) { it.copy(subject = intent.subject) }
            is SettingsFeedbackIntent.ChangeBody -> edit { it.copy(body = intent.body) }
            SettingsFeedbackIntent.Submit -> submitReport()
            SettingsFeedbackIntent.Retry -> retryReport()
            SettingsFeedbackIntent.RefreshRecovery -> refreshRecovery()
            SettingsFeedbackIntent.SetupHistory -> setupHistory()
            SettingsFeedbackIntent.ResumeCleanup -> resumeCleanup()
            SettingsFeedbackIntent.CheckInstallation -> checkInstallation()
            SettingsFeedbackIntent.RequestRemoteDeletion -> requestRemoteDeletion()
            SettingsFeedbackIntent.CancelRemoteDeletion -> resolveRemotePrompt(confirm = false)
            SettingsFeedbackIntent.ConfirmRemoteDeletion -> resolveRemotePrompt(confirm = true)
            SettingsFeedbackIntent.ContinueRemoteDeletion -> continueRemoteDeletion()
            is SettingsFeedbackIntent.CancelPrepared -> cancelPrepared(intent.report)
            is SettingsFeedbackIntent.RequestRecovery ->
                requestRecovery(SettingsFeedbackRecoveryKind.REPORT, intent.report)
            SettingsFeedbackIntent.RequestUnreadableRecovery -> requestRecovery(SettingsFeedbackRecoveryKind.UNREADABLE)
            SettingsFeedbackIntent.RequestDeletionAbandonment ->
                requestRecovery(SettingsFeedbackRecoveryKind.ABANDON_DELETION)
            SettingsFeedbackIntent.CancelRecovery -> resolvePrompt(confirm = false)
            SettingsFeedbackIntent.ConfirmRecovery -> resolvePrompt(confirm = true)
            SettingsFeedbackIntent.NewDraft -> newDraft()
            SettingsFeedbackIntent.Close -> closeReport()
        }
    }

    private fun edit(
        fixedFields: Boolean = false,
        change: (ComplaintReportDraft) -> ComplaintReportDraft,
    ) {
        if (closed || operation?.isCompleted == false || !state.value.editable) return
        if (fixedFields && entry != SettingsFeedbackEntry.General) return
        updateState { it.copy(draft = change(it.draft), result = null) }
    }

    private suspend fun submitReport() {
        if (!state.value.editable || !state.value.normalActionsAllowed) return
        work {
            val prepared = active(actions.prepare(state.value.draft)).preparationResult()
            updateState { it.withPreparation(prepared) }
            if (prepared is ComplaintReportPreparation.Ready) {
                live = prepared.report
                submitLive(prepared.report)
            }
        }
    }

    private suspend fun submitLive(report: ComplaintLiveReport) {
        when (val submitted = active(actions.submit(report))) {
            is AppResult.Failure -> showFailure(ComplaintReportFailure(submitted.error))
            is AppResult.Success -> {
                updateState { it.copy(recovery = submitted.value.recovery) }
                showAttempt(submitted.value.attempt)
            }
        }
    }

    private suspend fun retryReport() {
        val report = live ?: return
        if (!state.value.canRetry) return
        work {
            when (val result = active(actions.retry(report))) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
                is AppResult.Success -> showAttempt(result.value)
            }
        }
    }

    private suspend fun refreshRecovery() {
        if (live != null || terminal || !state.value.normalActionsAllowed) return
        work {
            reconcileReports()
        }
    }

    private suspend fun reconcileReports() {
        val result = active(recoveryActions.reconcile())
        updateState { it.withRecovery(result) }
    }

    /** Every opening begins with local reads, before any report reconciliation or history enrollment. */
    private suspend fun checkInstallation() {
        work { observeInstallation() }
    }

    private suspend fun observeInstallation(reconcile: Boolean = true) {
        updateState { it.withDeletion(SettingsFeedbackDeletionState.Checking) }
        when (val result = active(installationActions.deletion.observe())) {
            is AppResult.Failure -> {
                updateState { it.withDeletion(SettingsFeedbackDeletionState.Uncertain) }
                showFailure(ComplaintReportFailure(result.error))
            }
            is AppResult.Success -> {
                val observed = result.value.presentationState()
                if (observed != SettingsFeedbackDeletionState.Active) retireLiveReport()
                updateState { it.withDeletion(observed) }
                val activeReconciliationRequested = reconcile && observed == SettingsFeedbackDeletionState.Active
                if (activeReconciliationRequested && live == null && !terminal) reconcileReports()
            }
        }
    }

    /** Existing safe history flow only. Report IDs and submission remain behind a later explicit Submit. */
    private suspend fun setupHistory() {
        if (live != null || prompt != null || !state.value.canSetupHistory) return
        work {
            updateState { it.withDeletion(SettingsFeedbackDeletionState.Uncertain) }
            when (val result = active(observeUserComplaints())) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
                is AppResult.Success -> {
                    updateState { it.afterHistorySetup() }
                    observeInstallation(reconcile = false)
                }
            }
        }
    }

    private suspend fun resumeCleanup() {
        work {
            when (val result = active(installationActions.recovery.resumeCleanup())) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
                is AppResult.Success -> updateState { it.copy(result = SettingsFeedbackResult.CleanupCheckCompleted) }
            }
        }
    }

    private suspend fun cancelPrepared(report: ComplaintPendingReport) {
        if (!state.value.canUsePendingActions) return
        if (!isPreparedReport(report, live, latestAttempt, state.value.recovery)) return
        work {
            when (val result = active(recoveryActions.cancelPrepared(report))) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
                is AppResult.Success -> {
                    terminal = live != null
                    live = null
                    latestAttempt = null
                    updateState { it.afterPreparedCancellation(report) }
                }
            }
        }
    }

    private suspend fun requestRecovery(
        kind: SettingsFeedbackRecoveryKind,
        report: ComplaintPendingReport? = null,
    ) {
        if (kind == SettingsFeedbackRecoveryKind.REPORT && !state.value.canUsePendingActions) return
        if (report != null && findReportObservation(report, live, latestAttempt, state.value.recovery) == null) return
        work {
            var displayed = false
            try {
                val result =
                    when (kind) {
                        SettingsFeedbackRecoveryKind.REPORT -> recoveryActions.requestRecovery(report ?: return@work)
                        SettingsFeedbackRecoveryKind.UNREADABLE -> installationActions.recovery.requestUnreadable()
                        SettingsFeedbackRecoveryKind.ABANDON_DELETION ->
                            installationActions.recovery.requestDeletionAbandonment()
                    }
                // Capture before cancellation so a late/undelivered prompt is dismissed exactly once.
                if (result is AppResult.Success) prompt = SettingsFeedbackPrompt.Local(result.value, kind)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
                    is AppResult.Success -> {
                        updateState { it.withRecoveryKind(kind) }
                        displayed = true
                        emit(SettingsFeedbackEffect.ConfirmLocalReset)
                    }
                }
            } finally {
                if (!displayed) withContext(NonCancellable) { dismissOwnedPrompt() }
            }
        }
    }

    private suspend fun resolvePrompt(confirm: Boolean) {
        val expected = prompt as? SettingsFeedbackPrompt.Local ?: return
        work(allowPrompt = true) {
            try {
                val result =
                    if (confirm) {
                        recoveryActions.confirmRecovery(expected.token)
                    } else {
                        recoveryActions.cancelRecovery(expected.token)
                    }
                if (result is AppResult.Success) prompt = null
                currentCoroutineContext().ensureActive()
                when (result) {
                    is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
                    is AppResult.Success -> if (confirm) localResetCompleted(expected.kind)
                }
            } finally {
                // Confirmation may have been consumed before cleanup failed; never offer this token again.
                withContext(NonCancellable) { dismissOwnedPrompt() }
                if (!closed) updateState { it.withRecoveryKind(null) }
            }
        }
    }

    private suspend fun requestRemoteDeletion() {
        if (!state.value.canRequestRemoteDeletion) return
        work {
            var displayed = false
            try {
                val result = installationActions.deletion.request()
                if (result is AppResult.Success) prompt = SettingsFeedbackPrompt.Remote(result.value)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is AppResult.Failure -> {
                        updateState { it.withDeletion(SettingsFeedbackDeletionState.Uncertain) }
                        showFailure(ComplaintReportFailure(result.error))
                    }
                    is AppResult.Success -> {
                        updateState { it.withRemotePrompt() }
                        displayed = true
                    }
                }
            } finally {
                if (!displayed) withContext(NonCancellable) { dismissOwnedPrompt() }
            }
        }
    }

    /** Never route a remote warning through local reset, or dismiss it then start an unbound deletion. */
    private suspend fun resolveRemotePrompt(confirm: Boolean) {
        val expected = prompt as? SettingsFeedbackPrompt.Remote ?: return
        work(allowPrompt = true) {
            if (confirm) beginRemoteAttempt()
            try {
                if (confirm) {
                    showDeletionOutcome(active(installationActions.deletion.confirm(expected.token)))
                } else {
                    when (val result = active(installationActions.deletion.cancel(expected.token))) {
                        is AppResult.Failure -> {
                            updateState { it.withDeletion(SettingsFeedbackDeletionState.Uncertain) }
                            showFailure(ComplaintReportFailure(result.error))
                        }
                        is AppResult.Success -> prompt = null
                    }
                }
            } finally {
                // Only dismiss the warning; cancellation/close must not abandon durable deletion.
                withContext(NonCancellable) { dismissOwnedPrompt() }
                if (!closed) updateState { it.withRecoveryKind(null) }
            }
        }
    }

    private suspend fun continueRemoteDeletion() {
        if (!state.value.canContinueRemoteDeletion) return
        work {
            beginRemoteAttempt()
            showDeletionOutcome(active(installationActions.deletion.continueDeletion()))
        }
    }

    /** Retire old epoch-bound actions before the first suspension, including a failed/canceled confirm. */
    private fun beginRemoteAttempt() {
        retireLiveReport()
        terminal = false
        updateState { it.withDeletion(SettingsFeedbackDeletionState.Uncertain).copy(recovery = null, result = null) }
    }

    private fun showDeletionOutcome(result: AppResult<ComplaintInstallationDeletionOutcome>) {
        when (result) {
            is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
            is AppResult.Success ->
                when (val outcome = result.value) {
                    ComplaintInstallationDeletionOutcome.Completed -> {
                        terminal = true
                        updateState {
                            it.withDeletion(SettingsFeedbackDeletionState.Completed).copy(draft = entry.initialDraft())
                        }
                    }
                    is ComplaintInstallationDeletionOutcome.Pending ->
                        updateState {
                            it.withDeletion(
                                SettingsFeedbackDeletionState.Pending(outcome.retryAfterSeconds, outcome.error),
                            )
                        }
                }
        }
    }

    private fun retireLiveReport() {
        live = null
        latestAttempt = null
    }

    private fun localResetCompleted(kind: SettingsFeedbackRecoveryKind) {
        live = null
        latestAttempt = null
        terminal = true
        updateState { it.afterLocalReset(kind) }
    }

    private fun showAttempt(attempt: ComplaintReportAttempt) {
        val retained = retainReportApplication(attempt, latestAttempt)
        latestAttempt = retained
        terminal = retained is ComplaintReportAttempt.Completed
        updateState { it.copy(result = SettingsFeedbackResult.Attempt(retained)) }
    }

    private fun showFailure(failure: ComplaintReportFailure) {
        if (state.value.deletion == SettingsFeedbackDeletionState.Checking) {
            updateState { it.withDeletion(SettingsFeedbackDeletionState.Uncertain) }
        }
        if (live == null) {
            updateState { it.copy(result = SettingsFeedbackResult.Failure(failure)) }
        } else {
            showAttempt(failure.afterAttempt(latestAttempt))
        }
    }

    /** State is guarded before the first suspension: concurrent intents cannot replace the live request. */
    private suspend fun work(
        allowPrompt: Boolean = false,
        action: suspend () -> Unit,
    ) {
        if (closed || operation?.isCompleted == false || state.value.busy) return
        if (!allowPrompt && prompt != null) return
        updateState { it.copy(activity = SettingsFeedbackActivity.WORKING) }
        val caller = currentCoroutineContext().job
        operation = caller
        try {
            action()
        } catch (cancelled: CancellationException) {
            if (!closed) showFailure(ComplaintReportFailure(AppError.Cancelled()))
            throw cancelled
        } finally {
            // Retain the caller until its Job, including attached child cleanup, actually completes.
            if (!closed && operation === caller) updateState { it.afterWork(terminal, live != null) }
        }
    }

    private suspend fun <T> active(result: AppResult<T>): AppResult<T> {
        currentCoroutineContext().ensureActive()
        return result
    }

    private fun newDraft() {
        if (closed || operation?.isCompleted == false || !state.value.canStartNewDraft) return
        live = null
        latestAttempt = null
        terminal = false
        updateState { entry.initialState() }
        launchSafely { checkInstallation() }
    }

    private suspend fun closeReport() {
        if (closed) return
        closed = true
        operation?.cancelAndJoin()
        operation = null
        withContext(NonCancellable) { dismissOwnedPrompt() }
        live = null
        latestAttempt = null
        updateState { entry.initialState() }
        emit(SettingsFeedbackEffect.Closed)
    }

    private suspend fun dismissOwnedPrompt() {
        val expected = prompt ?: return
        try {
            when (expected) {
                is SettingsFeedbackPrompt.Local -> recoveryActions.cancelRecovery(expected.token)
                is SettingsFeedbackPrompt.Remote -> installationActions.deletion.cancel(expected.token)
            }
        } finally {
            if (prompt === expected) prompt = null
        }
    }

    override fun onUnhandledError(
        throwable: Throwable,
        intent: SettingsFeedbackIntent?,
    ) {
        if (!closed) showFailure(ComplaintReportFailure(AppError.Unexpected("complaint_report_failed")))
    }
}

/** One per-opening prompt slot; a remote prompt can never become a local reset capability. */
private sealed interface SettingsFeedbackPrompt {
    class Local(
        val token: ComplaintRecoveryPrompt,
        val kind: SettingsFeedbackRecoveryKind,
    ) : SettingsFeedbackPrompt

    class Remote(
        val token: ComplaintInstallationDeletionPrompt,
    ) : SettingsFeedbackPrompt
}
