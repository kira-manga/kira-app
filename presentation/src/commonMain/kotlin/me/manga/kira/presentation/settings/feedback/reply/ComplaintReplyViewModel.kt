package me.manga.kira.presentation.settings.feedback.reply

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintLiveReply
import me.manga.kira.domain.model.feedback.ComplaintReplyDraft
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.usecase.feedback.ComplaintReplyActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportRecoveryActions
import me.manga.kira.domain.usecase.feedback.ObserveComplaintInstallationDeletionUseCase
import me.manga.kira.presentation.mvi.MviViewModel
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackDeletionState
import me.manga.kira.presentation.settings.feedback.presentationState

/**
 * Unselected, memory-only opening for one ordinary backend reply. All ports must use the same
 * candidate owner. The captured parent is not ownership proof; the producer rechecks authority.
 * Close drains this opening only, never deleting durable pending work or closing the shared owner.
 */
@Suppress("TooManyFunctions")
class ComplaintReplyViewModel(
    private val actions: ComplaintReplyActions,
    private val recoveryActions: ComplaintReportRecoveryActions,
    private val observeInstallation: ObserveComplaintInstallationDeletionUseCase,
    target: ComplaintReplyTarget?,
) : MviViewModel<ComplaintReplyState, ComplaintReplyIntent, ComplaintReplyEffect>(initialReplyState(target != null)) {
    private var parentId: String? = target?.parentId
    private var live: ComplaintLiveReply? = null
    private var latestAttempt: ComplaintReportAttempt? = null
    private var operation: Job? = null
    private var closed = false

    init {
        if (target != null) launchSafely { work { observeAndReconcile() } }
    }

    override suspend fun handle(intent: ComplaintReplyIntent) {
        when (intent) {
            is ComplaintReplyIntent.ChangeBody -> changeBody(intent.body)
            ComplaintReplyIntent.Submit -> submitReply()
            ComplaintReplyIntent.Retry -> retryReply()
            ComplaintReplyIntent.RefreshRecovery -> refreshRecovery()
            ComplaintReplyIntent.OpenRecovery -> close(ComplaintReplyEffect.OpenSettingsRecovery)
            ComplaintReplyIntent.Close -> close(ComplaintReplyEffect.Closed)
        }
    }

    private fun changeBody(body: String) {
        if (closed || operation?.isCompleted == false || !state.value.editable) return
        updateState { it.copy(body = body, result = null) }
    }

    private suspend fun submitReply() {
        val parent = parentId ?: return
        if (!state.value.canSubmit || live != null) return
        val draft = ComplaintReplyDraft(parent, state.value.body)
        work { prepareAndSubmit(draft) }
    }

    private suspend fun prepareAndSubmit(draft: ComplaintReplyDraft) {
        when (val result = active(actions.prepare(draft))) {
            is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
            is AppResult.Success ->
                when (val prepared = result.value) {
                    is ComplaintReplyPreparation.Ready -> {
                        live = prepared.reply
                        submitLive(prepared.reply)
                    }
                    is ComplaintReplyPreparation.Invalid ->
                        updateState {
                            it.copy(result = ComplaintReplyResult.Invalid(prepared.field, prepared.reason))
                        }
                    is ComplaintReplyPreparation.Blocked -> showFailure(prepared.failure)
                }
        }
    }

    private suspend fun submitLive(reply: ComplaintLiveReply) {
        when (val result = active(actions.submit(reply))) {
            is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
            is AppResult.Success -> {
                updateState { it.copy(recovery = result.value.recovery) }
                showAttempt(result.value.attempt)
            }
        }
    }

    private suspend fun retryReply() {
        val reply = live ?: return
        if (!state.value.canRetry) return
        work {
            when (val result = active(actions.retry(reply))) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
                is AppResult.Success -> showAttempt(result.value)
            }
        }
    }

    private suspend fun refreshRecovery() {
        if (live != null || !state.value.canRefreshRecovery) return
        work { observeAndReconcile() }
    }

    /** Local checked reads precede metadata recovery. Neither operation enrolls or sends reply prose. */
    private suspend fun observeAndReconcile() {
        updateState { it.copy(context = it.context.copy(installation = SettingsFeedbackDeletionState.Checking)) }
        when (val result = active(observeInstallation())) {
            is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
            is AppResult.Success -> {
                val observed = result.value.presentationState()
                updateState { it.copy(context = it.context.copy(installation = observed), result = null) }
                if (observed == SettingsFeedbackDeletionState.Active) reconcile()
            }
        }
    }

    private suspend fun reconcile() {
        when (val result = active(recoveryActions.reconcile())) {
            is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
            is AppResult.Success ->
                updateState {
                    it.copy(recovery = result.value, result = null).withReplyFailureObservation(result.value.stopped)
                }
        }
    }

    private fun showAttempt(attempt: ComplaintReportAttempt) {
        val retained = attempt.retainingReplyReceipt(latestAttempt)
        latestAttempt = retained
        val failure = (retained as? ComplaintReportAttempt.Unresolved)?.failure
        updateState { it.withReplyFailureObservation(failure).copy(result = ComplaintReplyResult.Attempt(retained)) }
    }

    private fun showFailure(failure: ComplaintReportFailure) {
        if (live == null) {
            updateState {
                it.withReplyFailureObservation(failure).copy(result = ComplaintReplyResult.Failure(failure))
            }
        } else {
            showAttempt(failure.afterReplyAttempt(latestAttempt))
        }
    }

    /** A canceled caller still owns the slot until its Job (including child cleanup) is completed. */
    private suspend fun work(action: suspend () -> Unit) {
        if (closed || operation?.isCompleted == false) return
        val caller = currentCoroutineContext().job
        operation = caller
        updateState { it.copy(activity = ComplaintReplyActivity.WORKING) }
        try {
            action()
        } catch (cancelled: CancellationException) {
            if (!closed && operation === caller) showFailure(ComplaintReportFailure(AppError.Cancelled()))
            throw cancelled
        } catch (_: Throwable) {
            if (!closed && operation === caller) showFailure(unexpectedReplyFailure())
            currentCoroutineContext().ensureActive()
        } finally {
            if (!closed && operation === caller) {
                updateState { it.afterReplyWork(live != null, latestAttempt is ComplaintReportAttempt.Completed) }
            }
            // Do not clear operation here: its coroutine/children may still be unwinding.
        }
    }

    private suspend fun <T> active(result: AppResult<T>): AppResult<T> {
        val context = currentCoroutineContext()
        context.ensureActive()
        if (closed || operation !== context.job) throw CancellationException()
        return result
    }

    private suspend fun close(effect: ComplaintReplyEffect) {
        if (closed) return
        closed = true
        val ownedOperation = operation
        clearMemory()
        withContext(NonCancellable) { ownedOperation?.cancelAndJoin() }
        operation = null
        currentCoroutineContext().ensureActive()
        emit(effect)
    }

    private fun clearMemory() {
        parentId = null
        live = null
        latestAttempt = null
        updateState { closedReplyState() }
    }

    override fun onCleared() {
        closed = true
        operation?.cancel()
        clearMemory()
        super.onCleared()
    }

    override fun onUnhandledError(
        throwable: Throwable,
        intent: ComplaintReplyIntent?,
    ) {
        // Scoped work consumes late Throwables itself. Never publish/log their text from this hook.
        if (closed || operation?.isCompleted == false || state.value.activity == ComplaintReplyActivity.TERMINAL) return
        showFailure(unexpectedReplyFailure())
        updateState { it.afterReplyWork(live != null, latestAttempt is ComplaintReportAttempt.Completed) }
    }
}

private fun unexpectedReplyFailure(): ComplaintReportFailure =
    ComplaintReportFailure(AppError.Unexpected(REPLY_FAILURE_MARKER))

private const val REPLY_FAILURE_MARKER = "complaint_reply_failed"
