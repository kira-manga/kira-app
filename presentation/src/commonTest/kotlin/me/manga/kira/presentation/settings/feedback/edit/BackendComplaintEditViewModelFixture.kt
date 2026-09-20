package me.manga.kira.presentation.settings.feedback.edit

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistoryPlatform
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintOwnerFields
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintEditDraft
import me.manga.kira.domain.model.feedback.ComplaintEditPreparation
import me.manga.kira.domain.model.feedback.ComplaintLiveEdit
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportPending
import me.manga.kira.domain.model.feedback.ComplaintReportPhase
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import me.manga.kira.domain.repository.ComplaintEditRepository
import me.manga.kira.domain.usecase.feedback.ComplaintEditActions
import me.manga.kira.domain.usecase.feedback.PrepareComplaintEditUseCase
import me.manga.kira.domain.usecase.feedback.RetryComplaintEditUseCase
import me.manga.kira.domain.usecase.feedback.SubmitComplaintEditUseCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

internal class BackendComplaintEditViewModelFixture(
    target: ComplaintOwnerRow = editRow(),
    val repository: EditRepositoryFake = EditRepositoryFake(),
) {
    private val store = ViewModelStore()
    private val factory =
        viewModelFactory {
            initializer { BackendComplaintEditViewModel(repository.actions(), target) }
        }
    val model: BackendComplaintEditViewModel =
        ViewModelProvider.create(store, factory)[BackendComplaintEditViewModel::class]

    fun close() = store.clear()
}

internal class EditRepositoryFake : ComplaintEditRepository {
    val live: ComplaintLiveEdit = EditTestLive()
    val pending: ComplaintPendingReport = EditTestPending()
    val drafts = mutableListOf<ComplaintEditDraft>()
    val submitted = mutableListOf<ComplaintLiveEdit>()
    val retried = mutableListOf<ComplaintLiveEdit>()
    var recovery = ComplaintReportRecovery(emptyList())
    var onPrepare: suspend (ComplaintEditDraft) -> AppResult<ComplaintEditPreparation> =
        { AppResult.Success(ComplaintEditPreparation.Ready(live)) }
    var onSubmit: suspend (ComplaintLiveEdit) -> AppResult<ComplaintReportSubmission> =
        { AppResult.Success(submission(unresolved())) }
    var onRetry: suspend (ComplaintLiveEdit) -> AppResult<ComplaintReportAttempt> =
        { AppResult.Success(completedEdit()) }

    override suspend fun prepare(draft: ComplaintEditDraft): AppResult<ComplaintEditPreparation> {
        drafts += draft
        return onPrepare(draft)
    }

    override suspend fun submit(edit: ComplaintLiveEdit): AppResult<ComplaintReportSubmission> {
        submitted += edit
        return onSubmit(edit)
    }

    override suspend fun retry(edit: ComplaintLiveEdit): AppResult<ComplaintReportAttempt> {
        retried += edit
        return onRetry(edit)
    }

    fun actions(): ComplaintEditActions =
        ComplaintEditActions(
            PrepareComplaintEditUseCase(this),
            SubmitComplaintEditUseCase(this),
            RetryComplaintEditUseCase(this),
        )

    fun submission(attempt: ComplaintReportAttempt): ComplaintReportSubmission =
        ComplaintReportSubmission(attempt, recovery)

    fun unresolved(
        application: ComplaintReportApplication? = null,
        error: AppError = AppError.Network.Timeout(),
    ): ComplaintReportAttempt.Unresolved =
        ComplaintReportAttempt.Unresolved(
            ComplaintReportFailure(error),
            application,
            ComplaintReportPending(pending, ComplaintReportPhase.MAY_HAVE_DISPATCHED),
        )
}

/** A dependency that deliberately returns or throws after its caller was canceled. */
internal class LateEditWork<T>(
    private val outcome: () -> AppResult<T>,
) {
    val closing = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    var caller: Job? = null
        private set
    var cleaned = false
        private set

    suspend fun run(): AppResult<T> {
        caller = currentCoroutineContext().job
        return try {
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
}

internal enum class EditRowShape { REPORT, REPLY, NOTICE_REPLY }

internal fun editRow(
    shape: EditRowShape = EditRowShape.REPORT,
    status: ComplaintHistoryStatus = ComplaintHistoryStatus.Known(ComplaintStatus.OPEN),
    type: ComplaintHistoryType = ComplaintHistoryType.Known(ComplaintType.TECHNICAL),
    version: Long = 7,
): ComplaintOwnerRow.Content {
    val fields = editFields(status, version)
    return when (shape) {
        EditRowShape.REPORT -> ComplaintOwnerRow.Report(fields, type, EDIT_ORIGINAL_SUBJECT)
        EditRowShape.REPLY -> ComplaintOwnerRow.Reply(fields, type, EDIT_ORIGINAL_SUBJECT, EDIT_PARENT)
        EditRowShape.NOTICE_REPLY -> ComplaintOwnerRow.NoticeReply(fields, "unaccepted.private.notice.key", EDIT_PARENT)
    }
}

private fun editFields(status: ComplaintHistoryStatus, version: Long) =
    ComplaintOwnerFields(
        id = EDIT_ID,
        body = EDIT_ORIGINAL_BODY,
        status = status,
        createdAt = EDIT_TIME,
        updatedAt = EDIT_TIME,
        version = version,
        actionTag = "\"complaint-$EDIT_ID-v$version\"",
        appVersion = null,
        platform = ComplaintHistoryPlatform.ANDROID,
        osVersion = null,
        manufacturer = null,
        deviceModel = null,
        closureReason = null,
    )

internal fun completedEdit(
    application: ComplaintEditApplication = ComplaintEditApplication.Applied(EDIT_ID, 8),
): ComplaintReportAttempt.Completed = ComplaintReportAttempt.Completed(ComplaintReportApplication.Edit(application))

internal fun BackendComplaintEditViewModel.fillEditDraft() {
    submit(BackendComplaintEditIntent.ChangeSubject(EDIT_PRIVATE_SUBJECT))
    submit(BackendComplaintEditIntent.ChangeBody(EDIT_PRIVATE_BODY))
}

internal fun assertEditRetired(model: BackendComplaintEditViewModel) {
    val state = model.state.value
    assertTrue(state.closed)
    assertFalse(state.editable || state.busy || state.canRetry || state.hasDraft)
    assertEquals(BackendComplaintEditText(), state.draft)
    assertNull(state.result.receipt)
    assertNull(state.result.failure)
}

private class EditTestLive : ComplaintLiveEdit

private class EditTestPending : ComplaintPendingReport

internal const val EDIT_ID = "11111111-1111-4111-8111-111111111111"
internal const val EDIT_PARENT = "22222222-2222-4222-8222-222222222222"
internal const val EDIT_ORIGINAL_SUBJECT = "Synthetic original subject"
internal const val EDIT_ORIGINAL_BODY = "Synthetic original body"
internal const val EDIT_PRIVATE_SUBJECT = "Synthetic private replacement subject"
internal const val EDIT_PRIVATE_BODY = "Synthetic private replacement body"
internal val EDIT_TIME: Instant = Instant.parse("2026-09-18T00:00:00Z")
