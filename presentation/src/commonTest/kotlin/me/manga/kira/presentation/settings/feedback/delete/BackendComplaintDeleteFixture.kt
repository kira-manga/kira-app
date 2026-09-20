package me.manga.kira.presentation.settings.feedback.delete

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.feedback.ComplaintLiveOwnerDelete
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteDraft
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeletePreparation
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import me.manga.kira.domain.repository.ComplaintOwnerDeleteRepository
import me.manga.kira.domain.usecase.feedback.ComplaintOwnerDeleteActions
import me.manga.kira.domain.usecase.feedback.PrepareComplaintOwnerDeleteUseCase
import me.manga.kira.domain.usecase.feedback.RetryComplaintOwnerDeleteUseCase
import me.manga.kira.domain.usecase.feedback.SubmitComplaintOwnerDeleteUseCase
import me.manga.kira.presentation.complaint.ownedDetail
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class BackendComplaintDeleteFixture(
    target: ComplaintOwnerRow = ownedDetail().item,
    val repository: DeleteRepositoryFake = DeleteRepositoryFake(),
) {
    private val store = ViewModelStore()
    private val factory =
        viewModelFactory {
            initializer { BackendComplaintDeleteViewModel(repository.actions(), target) }
        }
    val model = ViewModelProvider.create(store, factory)[BackendComplaintDeleteViewModel::class]

    fun close() = store.clear()
}

internal class DeleteRepositoryFake : ComplaintOwnerDeleteRepository {
    val live = object : ComplaintLiveOwnerDelete {}
    val drafts = mutableListOf<ComplaintOwnerDeleteDraft>()
    val submitted = mutableListOf<ComplaintLiveOwnerDelete>()
    val retried = mutableListOf<ComplaintLiveOwnerDelete>()
    var recovery = ComplaintReportRecovery(emptyList())
    var onPrepare: suspend (ComplaintOwnerDeleteDraft) -> AppResult<ComplaintOwnerDeletePreparation> =
        { AppResult.Success(ComplaintOwnerDeletePreparation.Ready(live)) }
    var onSubmit: suspend (ComplaintLiveOwnerDelete) -> AppResult<ComplaintReportSubmission> =
        { AppResult.Success(submission(unresolved())) }
    var onRetry: suspend (ComplaintLiveOwnerDelete) -> AppResult<ComplaintReportAttempt> =
        { AppResult.Success(deleteCompleted()) }

    override suspend fun prepare(draft: ComplaintOwnerDeleteDraft): AppResult<ComplaintOwnerDeletePreparation> {
        drafts += draft
        return onPrepare(draft)
    }

    override suspend fun submit(deletion: ComplaintLiveOwnerDelete): AppResult<ComplaintReportSubmission> {
        submitted += deletion
        return onSubmit(deletion)
    }

    override suspend fun retry(deletion: ComplaintLiveOwnerDelete): AppResult<ComplaintReportAttempt> {
        retried += deletion
        return onRetry(deletion)
    }

    fun actions() =
        ComplaintOwnerDeleteActions(
            PrepareComplaintOwnerDeleteUseCase(this),
            SubmitComplaintOwnerDeleteUseCase(this),
            RetryComplaintOwnerDeleteUseCase(this),
        )

    fun submission(attempt: ComplaintReportAttempt) = ComplaintReportSubmission(attempt, recovery)
}

internal fun unresolved(application: ComplaintReportApplication? = null) =
    ComplaintReportAttempt.Unresolved(ComplaintReportFailure(AppError.Network.Timeout()), application)

internal fun deleteCompleted(application: ComplaintOwnerDeleteApplication = ComplaintOwnerDeleteApplication.Applied) =
    ComplaintReportAttempt.Completed(ComplaintReportApplication.OwnerDelete(application))

/** Deliberately returns/throws after cancellation, while keeping cleanup under test control. */
internal class LateDeleteWork<T>(private val outcome: () -> AppResult<T>) {
    val closing = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var cleaned = false
        private set

    suspend fun run(): AppResult<T> =
        try {
            awaitCancellation()
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                closing.complete(Unit)
                release.await()
                cleaned = true
                outcome()
            }
        }
}

internal fun assertDeleteClosed(model: BackendComplaintDeleteViewModel) {
    val state = model.state.value
    assertTrue(state.closed)
    assertFalse(state.canConfirm || state.busy || state.canRetry || state.hasTarget)
    assertEquals(BackendComplaintDeletePreview(), state.preview)
    assertNull(state.result.receipt)
    assertNull(state.result.failure)
}

/** Three held phases for the one original operation; every test exit releases all of them. */
internal class DeleteAttemptBarriers(private val repository: DeleteRepositoryFake) {
    val prepared = CompletableDeferred<Unit>()
    val submitted = CompletableDeferred<Unit>()
    val retried = CompletableDeferred<Unit>()

    init {
        repository.onPrepare = {
            prepared.await()
            AppResult.Success(ComplaintOwnerDeletePreparation.Ready(repository.live))
        }
        repository.onSubmit = {
            submitted.await()
            AppResult.Success(repository.submission(unresolved()))
        }
    }

    fun holdRetry() {
        repository.onRetry = {
            retried.await()
            AppResult.Success(deleteCompleted())
        }
    }

    fun release() {
        prepared.complete(Unit)
        submitted.complete(Unit)
        retried.complete(Unit)
    }
}
