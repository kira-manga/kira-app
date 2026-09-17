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
import me.manga.kira.domain.model.feedback.ComplaintReportPhase
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase
import me.manga.kira.domain.usecase.feedback.ComplaintReportActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportRecoveryActions
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Candidate Settings report subfeature. Entry/input/handles live only in this VM; one guarded action
 * at a time, exact manual retry and explicit existing-history setup that never prepares/submits reports.
 * Reducer transitions deliberately share this screen's caller/live/prompt state and teardown ownership.
 */
@Suppress("TooManyFunctions")
class SettingsFeedbackViewModel(
    private val actions: ComplaintReportActions,
    private val recoveryActions: ComplaintReportRecoveryActions,
    private val observeUserComplaints: ObserveUserComplaintsUseCase,
    private val entry: SettingsFeedbackEntry = SettingsFeedbackEntry.General,
) : MviViewModel<SettingsFeedbackState, SettingsFeedbackIntent, SettingsFeedbackEffect>(entry.initialState()) {
    private var live: ComplaintLiveReport? = null
    private var latestAttempt: ComplaintReportAttempt? = null
    private var prompt: ComplaintRecoveryPrompt? = null
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
        launchSafely { refreshRecovery() }
    }

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
            is SettingsFeedbackIntent.CancelPrepared -> cancelPrepared(intent.report)
            is SettingsFeedbackIntent.RequestRecovery -> requestRecovery(intent.report)
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
        if (closed || !state.value.editable) return
        if (fixedFields && entry != SettingsFeedbackEntry.General) return
        updateState { it.copy(draft = change(it.draft), result = null) }
    }

    private suspend fun submitReport() {
        if (!state.value.editable) return
        work {
            val prepared = active(actions.prepare(state.value.draft))
            updateState { it.withMissingObservation(prepared.isMissingPreparation()) }
            when (prepared) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(prepared.error))
                is AppResult.Success ->
                    when (val value = prepared.value) {
                        is ComplaintReportPreparation.Ready -> {
                            live = value.report
                            submitLive(value.report)
                        }
                        is ComplaintReportPreparation.Invalid ->
                            updateState { it.copy(result = SettingsFeedbackResult.Invalid(value.field, value.reason)) }
                        is ComplaintReportPreparation.Blocked -> showFailure(value.failure)
                    }
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
        if (live != null || terminal) return
        work {
            when (val result = active(recoveryActions.reconcile())) {
                is AppResult.Failure -> {
                    updateState { it.withMissingObservation(false) }
                    showFailure(ComplaintReportFailure(result.error))
                }
                is AppResult.Success -> updateState { it.withRecovery(result.value) }
            }
        }
    }

    /** Existing safe history flow only. Report IDs and submission remain behind a later explicit Submit. */
    private suspend fun setupHistory() {
        if (live != null || prompt != null || !state.value.canSetupHistory) return
        work {
            when (val result = active(observeUserComplaints())) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
                is AppResult.Success -> updateState { it.afterHistorySetup() }
            }
        }
    }

    private suspend fun cancelPrepared(report: ComplaintPendingReport) {
        if (
            findReportObservation(report, live, latestAttempt, state.value.recovery)?.phase !=
            ComplaintReportPhase.PREPARED
        ) {
            return
        }
        work {
            when (val result = active(recoveryActions.cancelPrepared(report))) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
                is AppResult.Success -> {
                    terminal = live != null
                    live = null
                    latestAttempt = null
                    updateState {
                        it.copy(
                            result = SettingsFeedbackResult.PreparedCancelled,
                            recovery = it.recovery?.without(report),
                        )
                    }
                }
            }
        }
    }

    private suspend fun requestRecovery(report: ComplaintPendingReport) {
        if (findReportObservation(report, live, latestAttempt, state.value.recovery) == null) return
        work {
            val result = recoveryActions.requestRecovery(report)
            // Capture before checking cancellation so close/VM teardown can dismiss this exact prompt.
            if (result is AppResult.Success) prompt = result.value
            currentCoroutineContext().ensureActive()
            when (result) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
                is AppResult.Success -> {
                    updateState { it.withConfirmation(true) }
                    emit(SettingsFeedbackEffect.ConfirmLocalReset)
                }
            }
        }
    }

    private suspend fun resolvePrompt(confirm: Boolean) {
        val expected = prompt ?: return
        work(allowPrompt = true) {
            val result =
                if (confirm) recoveryActions.confirmRecovery(expected) else recoveryActions.cancelRecovery(expected)
            currentCoroutineContext().ensureActive()
            when (result) {
                is AppResult.Failure -> {
                    showFailure(ComplaintReportFailure(result.error))
                    emit(SettingsFeedbackEffect.ConfirmLocalReset)
                }
                is AppResult.Success -> {
                    prompt = null
                    if (confirm) localResetCompleted() else updateState { it.withConfirmation(false) }
                }
            }
        }
    }

    private fun localResetCompleted() {
        live = null
        latestAttempt = null
        terminal = true
        updateState { it.afterLocalReset() }
    }

    private fun showAttempt(attempt: ComplaintReportAttempt) {
        val retained = retainReportApplication(attempt, latestAttempt)
        latestAttempt = retained
        terminal = retained is ComplaintReportAttempt.Completed
        updateState { it.copy(result = SettingsFeedbackResult.Attempt(retained)) }
    }

    private fun showFailure(failure: ComplaintReportFailure) {
        if (live == null) {
            updateState { it.copy(result = SettingsFeedbackResult.Failure(failure)) }
        } else {
            val previous = latestAttempt as? ComplaintReportAttempt.Unresolved
            showAttempt(ComplaintReportAttempt.Unresolved(failure, previous?.knownApplication, previous?.pending))
        }
    }

    /** State is guarded before the first suspension: concurrent intents cannot replace the live request. */
    private suspend fun work(
        allowPrompt: Boolean = false,
        action: suspend () -> Unit,
    ) {
        if (closed || state.value.busy) return
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
            if (operation === caller) operation = null
            if (!closed) updateState { it.afterWork(terminal, live != null) }
        }
    }

    private suspend fun <T> active(result: AppResult<T>): AppResult<T> {
        currentCoroutineContext().ensureActive()
        return result
    }

    private fun newDraft() {
        if (closed || !state.value.canStartNewDraft) return
        live = null
        latestAttempt = null
        terminal = false
        updateState { entry.initialState() }
        launchSafely { refreshRecovery() }
    }

    private suspend fun closeReport() {
        if (closed) return
        closed = true
        operation?.cancelAndJoin()
        withContext(NonCancellable) { dismissOwnedPrompt() }
        live = null
        latestAttempt = null
        updateState { entry.initialState() }
        emit(SettingsFeedbackEffect.Closed)
    }

    private suspend fun dismissOwnedPrompt() {
        val expected = prompt ?: return
        recoveryActions.cancelRecovery(expected)
        if (prompt === expected) prompt = null
    }

    override fun onUnhandledError(
        throwable: Throwable,
        intent: SettingsFeedbackIntent?,
    ) {
        if (!closed) showFailure(ComplaintReportFailure(AppError.Unexpected("complaint_report_failed")))
    }
}
