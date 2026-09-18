package me.manga.kira.presentation.settings.feedback.delete

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.feedback.ComplaintLiveOwnerDelete
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteDraft
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeletePreparation
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.usecase.feedback.ComplaintOwnerDeleteActions
import me.manga.kira.presentation.mvi.MviViewModel

/** Inert per-opening confirmation; original target/tag/live handle stay private and memory-only. */
@Suppress("TooManyFunctions") // Small lifecycle and result helpers belong to this single operation owner.
class BackendComplaintDeleteViewModel(
    private val actions: ComplaintOwnerDeleteActions,
    target: ComplaintOwnerRow,
) : MviViewModel<BackendComplaintDeleteState, BackendComplaintDeleteIntent, BackendComplaintDeleteEffect>(
        target.backendDeleteInitialState(),
    ) {
    private var capturedTarget = target.backendDeleteContent()
    private var live: ComplaintLiveOwnerDelete? = null
    private var operation: Job? = null
    private var retired = false

    override suspend fun handle(intent: BackendComplaintDeleteIntent) {
        if (retired) return
        when (intent) {
            BackendComplaintDeleteIntent.Confirm -> confirm()
            BackendComplaintDeleteIntent.Retry -> retry()
            BackendComplaintDeleteIntent.OpenRecovery -> close(BackendComplaintDeleteEffect.OpenRecovery)
            BackendComplaintDeleteIntent.Close -> close(BackendComplaintDeleteEffect.Closed)
        }
    }

    private suspend fun confirm() {
        val target = capturedTarget ?: return
        if (!state.value.canConfirm) return
        work {
            when (val prepared = active(actions.prepare(ComplaintOwnerDeleteDraft(target)))) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(prepared.error))
                is AppResult.Success -> acceptPreparation(prepared.value)
            }
        }
    }

    private suspend fun acceptPreparation(prepared: ComplaintOwnerDeletePreparation) {
        when (prepared) {
            is ComplaintOwnerDeletePreparation.Blocked -> showFailure(prepared.failure)
            is ComplaintOwnerDeletePreparation.Ready -> {
                live = prepared.deletion
                when (val submitted = active(actions.submit(prepared.deletion))) {
                    is AppResult.Failure -> showFailure(ComplaintReportFailure(submitted.error))
                    is AppResult.Success -> {
                        val recovery = submitted.value.recovery
                        val observed = recovery.entries().isNotEmpty() || recovery.stopped != null
                        updateState {
                            it.copy(result = it.result.copy(recoveryObserved = it.result.recoveryObserved || observed))
                        }
                        showAttempt(submitted.value.attempt)
                    }
                }
            }
        }
    }

    private suspend fun retry() {
        val deletion = live ?: return
        if (!state.value.canRetry) return
        work {
            when (val result = active(actions.retry(deletion))) {
                is AppResult.Failure -> showFailure(ComplaintReportFailure(result.error))
                is AppResult.Success -> showAttempt(result.value)
            }
        }
    }

    private fun showAttempt(attempt: ComplaintReportAttempt) {
        if (!retired) updateState { it.copy(result = it.result.afterAttempt(attempt)) }
    }

    private fun showFailure(failure: ComplaintReportFailure) {
        if (!retired) updateState { it.copy(result = it.result.afterFailure(failure)) }
    }

    /** Lock before suspension: rapid confirm/retry intents never create a second deletion. */
    @Suppress("TooGenericExceptionCaught") // Do not log provider errors or revive a retired target.
    private suspend fun work(action: suspend () -> Unit) {
        if (retired || operation?.isCompleted == false) return
        updateState { it.copy(activity = BackendComplaintDeleteActivity.WORKING) }
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
                        it.result.completed -> BackendComplaintDeleteActivity.TERMINAL
                        live != null -> BackendComplaintDeleteActivity.LIVE
                        capturedTarget != null -> BackendComplaintDeleteActivity.CONFIRMING
                        else -> BackendComplaintDeleteActivity.UNAVAILABLE
                    },
            )
        }
    }

    private suspend fun <T> active(result: AppResult<T>): AppResult<T> {
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun close(effect: BackendComplaintDeleteEffect) {
        val owned = retire()
        owned?.join()
        currentCoroutineContext().ensureActive()
        emit(effect)
    }

    private fun retire(): Job? {
        retired = true
        val owned = operation
        operation = null
        capturedTarget = null
        live = null
        updateState { BackendComplaintDeleteState(activity = BackendComplaintDeleteActivity.CLOSED) }
        owned?.cancel()
        return owned
    }

    override fun onCleared() {
        retire()
        super.onCleared()
    }

    override fun onUnhandledError(throwable: Throwable, intent: BackendComplaintDeleteIntent?) {
        if (intent != null) showFailure(ComplaintReportFailure(AppError.Unexpected(FAILURE_CODE)))
    }

    private companion object {
        const val FAILURE_CODE = "complaint_delete_failed"
    }
}
