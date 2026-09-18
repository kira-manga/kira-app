package me.manga.kira.presentation.settings.feedback.edit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.feedback.ComplaintEditDraft
import me.manga.kira.domain.model.feedback.ComplaintEditPreparation
import me.manga.kira.domain.model.feedback.ComplaintLiveEdit
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.usecase.feedback.ComplaintEditActions
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Closed candidate editor for one original backend row/tag. Construction performs no action.
 * The caller owns its lifecycle store; recovery belongs to the existing same-graph Settings opening.
 */
@Suppress("TooManyFunctions") // Small guarded verbs and lifecycle helpers belong to this one opening.
class BackendComplaintEditViewModel(
    private val actions: ComplaintEditActions,
    target: ComplaintOwnerRow,
) : MviViewModel<BackendComplaintEditState, BackendComplaintEditIntent, BackendComplaintEditEffect>(
        target.backendEditInitialState(),
    ) {
    private var capturedTarget = target.backendEditContent()
    private var live: ComplaintLiveEdit? = null
    private var operation: Job? = null
    private var retired = false

    override suspend fun handle(intent: BackendComplaintEditIntent) {
        if (retired) return
        when (intent) {
            is BackendComplaintEditIntent.ChangeSubject ->
                if (state.value.draft.subject != null) edit { it.copy(subject = intent.subject) }
            is BackendComplaintEditIntent.ChangeBody -> edit { it.copy(body = intent.body) }
            BackendComplaintEditIntent.Submit -> submitEdit()
            BackendComplaintEditIntent.Retry -> retryEdit()
            BackendComplaintEditIntent.OpenRecovery -> close(BackendComplaintEditEffect.OpenRecovery)
            BackendComplaintEditIntent.Close -> close(BackendComplaintEditEffect.Closed)
        }
    }

    private fun edit(change: (BackendComplaintEditText) -> BackendComplaintEditText) {
        if (operation?.isCompleted == false || !state.value.editable) return
        updateState {
            it.copy(draft = change(it.draft), invalidField = null, result = BackendComplaintEditObservation())
        }
    }

    private suspend fun submitEdit() {
        val target = capturedTarget ?: return
        if (!state.value.editable) return
        val text = state.value.draft
        work {
            when (val prepared = active(actions.prepare(ComplaintEditDraft(target, text.subject, text.body)))) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(prepared.error))
                is AppResult.Success -> acceptPreparation(prepared.value)
            }
        }
    }

    private suspend fun acceptPreparation(prepared: ComplaintEditPreparation) {
        when (prepared) {
            is ComplaintEditPreparation.Invalid ->
                updateState { it.copy(invalidField = prepared.field, result = BackendComplaintEditObservation()) }
            is ComplaintEditPreparation.Blocked -> showFailure(prepared.failure)
            is ComplaintEditPreparation.Ready -> {
                live = prepared.edit
                when (val submitted = active(actions.submit(prepared.edit))) {
                    is AppResult.Failure -> showFailure(ComplaintReportFailure(submitted.error))
                    is AppResult.Success -> {
                        rememberRecovery(submitted.value.recovery)
                        showAttempt(submitted.value.attempt)
                    }
                }
            }
        }
    }

    private suspend fun retryEdit() {
        val edit = live ?: return
        if (!state.value.canRetry) return
        work {
            when (val result = active(actions.retry(edit))) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
                is AppResult.Success -> showAttempt(result.value)
            }
        }
    }

    private fun rememberRecovery(recovery: ComplaintReportRecovery) {
        val observed = recovery.entries().isNotEmpty() || recovery.stopped != null
        updateState { it.copy(result = it.result.copy(recoveryObserved = it.result.recoveryObserved || observed)) }
    }

    private fun showAttempt(attempt: ComplaintReportAttempt) {
        if (!retired) updateState { it.copy(result = it.result.afterAttempt(attempt), invalidField = null) }
    }

    private fun showFailure(failure: ComplaintReportFailure) {
        if (!retired) updateState { it.copy(result = it.result.afterFailure(failure), invalidField = null) }
    }

    /** Before the first suspension, lock the draft and reject concurrent submits/retries rather than queueing. */
    @Suppress("TooGenericExceptionCaught") // Keep every thrown failure inside this opening's owned operation.
    private suspend fun work(action: suspend () -> Unit) {
        if (retired || operation?.isCompleted == false) return
        updateState { it.copy(activity = BackendComplaintEditActivity.WORKING, invalidField = null) }
        val caller = currentCoroutineContext().job
        operation = caller
        try {
            action()
        } catch (cancelled: CancellationException) {
            showFailure(ComplaintReportFailure(AppError.Cancelled()))
            throw cancelled
        } catch (_: Throwable) {
            showFailure(ComplaintReportFailure(AppError.Unexpected(FAILURE_CODE)))
        } finally {
            // Keep the caller until Job completion, including any cancellation-safe child cleanup.
            if (!retired) finishWork()
        }
    }

    private fun finishWork() {
        updateState {
            it.copy(
                activity =
                    when {
                        it.result.completed -> BackendComplaintEditActivity.TERMINAL
                        live != null -> BackendComplaintEditActivity.LIVE
                        capturedTarget != null -> BackendComplaintEditActivity.EDITING
                        else -> BackendComplaintEditActivity.UNAVAILABLE
                    },
            )
        }
    }

    private suspend fun <T> active(result: AppResult<T>): AppResult<T> {
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun close(effect: BackendComplaintEditEffect) {
        val owned = retire()
        owned?.join()
        currentCoroutineContext().ensureActive()
        emit(effect)
    }

    /** Erase visible/private memory immediately; canceled coroutine locals disappear when its work drains. */
    private fun retire(): Job? {
        retired = true
        val owned = operation
        operation = null
        capturedTarget = null
        live = null
        updateState { BackendComplaintEditState(activity = BackendComplaintEditActivity.CLOSED) }
        owned?.cancel()
        return owned
    }

    override fun onCleared() {
        retire()
        super.onCleared()
    }

    override fun onUnhandledError(
        throwable: Throwable,
        intent: BackendComplaintEditIntent?,
    ) {
        // Work catches asynchronous failures above; no raw exception, target or prose reaches logging/UI.
        if (intent != null) showFailure(ComplaintReportFailure(AppError.Unexpected(FAILURE_CODE)))
    }

    private companion object {
        const val FAILURE_CODE = "complaint_edit_failed"
    }
}
