package me.manga.kira.presentation.complaint

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.usecase.complaint.LoadComplaintDetailUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/** One selected read, never enrollment or mutation. A new selection cannot inherit another row's content. */
class ComplaintDetailViewModel(
    private val loadDetail: LoadComplaintDetailUseCase,
) : MviViewModel<ComplaintDetailState, ComplaintDetailIntent, ComplaintDetailEffect>(
        initialState = ComplaintDetailState(),
    ) {
    private val loadSerial = Mutex()
    private var loadJob: Job? = null
    private var generation = 0L
    private var cleared = false

    override suspend fun handle(intent: ComplaintDetailIntent) {
        if (cleared) return
        when (intent) {
            is ComplaintDetailIntent.Select -> {
                if (state.value.selectedId != intent.id) load(intent.id)
            }
            ComplaintDetailIntent.Retry -> state.value.selectedId?.let(::load)
            ComplaintDetailIntent.Close -> clearSelection()
        }
    }

    private fun load(id: String) {
        val previous = loadJob
        val selectedGeneration = ++generation
        previous?.cancel()
        updateState {
            ComplaintDetailState(
                selectedId = id,
                detail = if (it.selectedId == id) it.detail else null,
                isLoading = true,
            )
        }
        loadJob = launchSafely { readSelected(previous, id, selectedGeneration) }
    }

    // Match MVI's last-resort safety boundary here, where every failure still has its selection token.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun readSelected(previous: Job?, id: String, selectedGeneration: Long) {
        try {
            loadSerial.withLock {
                // A canceled intermediate waiter cannot let a later selection overtake oldest cleanup.
                previous?.join()
                currentCoroutineContext().ensureActive()
                val result = loadDetail(id)
                currentCoroutineContext().ensureActive()
                publish(id, selectedGeneration, result)
            }
        } catch (cancelled: CancellationException) {
            publish(id, selectedGeneration, AppResult.Failure(AppError.Cancelled()))
            throw cancelled
        } catch (_: Throwable) {
            publish(id, selectedGeneration, AppResult.Failure(AppError.Unexpected(FAILURE_CODE)))
        }
    }

    private fun publish(
        id: String,
        selectedGeneration: Long,
        result: AppResult<ComplaintDetail>,
    ) {
        if (cleared || generation != selectedGeneration || state.value.selectedId != id) return
        updateState {
            when (result) {
                is AppResult.Success -> it.copy(detail = result.value, isLoading = false, error = null)
                is AppResult.Failure -> it.copy(isLoading = false, error = result.error)
            }
        }
    }

    private fun clearSelection() {
        ++generation
        loadJob?.cancel()
        updateState { ComplaintDetailState() }
    }

    override fun onCleared() {
        cleared = true
        clearSelection()
        super.onCleared()
    }

    override fun onUnhandledError(
        throwable: Throwable,
        intent: ComplaintDetailIntent?,
    ) {
        // Async errors are handled with a generation above; this unscoped hook must not retarget them.
        if (intent != null && !cleared && state.value.selectedId != null) {
            updateState { it.copy(isLoading = false, error = AppError.Unexpected(FAILURE_CODE)) }
        }
    }

    private companion object {
        const val FAILURE_CODE = "complaint_detail_failed"
    }
}
