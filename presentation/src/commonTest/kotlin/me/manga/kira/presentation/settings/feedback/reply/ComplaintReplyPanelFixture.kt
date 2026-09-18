package me.manga.kira.presentation.settings.feedback.reply

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
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintHistoryPlatform
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintNotice
import me.manga.kira.domain.model.complaint.ComplaintOwnerFields
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintLiveReply
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintReplyDraft
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.model.feedback.ComplaintReportSubmission
import me.manga.kira.domain.repository.ComplaintInstallationDeletionObservation
import me.manga.kira.domain.repository.ComplaintInstallationDeletionRepository
import me.manga.kira.domain.repository.ComplaintReplyRepository
import me.manga.kira.domain.repository.ComplaintReportRepository
import me.manga.kira.domain.usecase.feedback.CancelComplaintReportRecoveryUseCase
import me.manga.kira.domain.usecase.feedback.CancelPreparedComplaintReportUseCase
import me.manga.kira.domain.usecase.feedback.ComplaintReplyActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportRecoveryActions
import me.manga.kira.domain.usecase.feedback.ConfirmComplaintReportRecoveryUseCase
import me.manga.kira.domain.usecase.feedback.ObserveComplaintInstallationDeletionUseCase
import me.manga.kira.domain.usecase.feedback.PrepareComplaintReplyUseCase
import me.manga.kira.domain.usecase.feedback.ReconcileComplaintReportsUseCase
import me.manga.kira.domain.usecase.feedback.RequestComplaintReportRecoveryUseCase
import me.manga.kira.domain.usecase.feedback.RetryComplaintReplyUseCase
import me.manga.kira.domain.usecase.feedback.SubmitComplaintReplyUseCase
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackDeletionState
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackRepositoryFake
import me.manga.kira.presentation.settings.feedback.SettingsInstallationDeletionFake
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Ordinary domain-port fakes plus real use cases; no protocol/store/queue implementation. */
internal class ComplaintReplyPanelFixture : ComplaintReplyRepository {
    val reports = SettingsFeedbackRepositoryFake()
    val deletion = SettingsInstallationDeletionFake()
    val calls = mutableListOf<String>()
    val drafts = mutableListOf<ComplaintReplyDraft>()
    val submitted = mutableListOf<ComplaintLiveReply>()
    val retried = mutableListOf<ComplaintLiveReply>()
    val live: ComplaintLiveReply = TestLiveReply()
    private val stores = mutableMapOf<ComplaintReplyViewModel, ViewModelStore>()
    var onPrepare: suspend (ComplaintReplyDraft) -> AppResult<ComplaintReplyPreparation> = {
        AppResult.Success(ComplaintReplyPreparation.Ready(live))
    }
    var onSubmit: suspend (ComplaintLiveReply) -> AppResult<ComplaintReportSubmission> = {
        AppResult.Success(ComplaintReportSubmission(reports.unresolved(), reports.recovery))
    }
    var onRetry: suspend (ComplaintLiveReply) -> AppResult<ComplaintReportAttempt> = {
        AppResult.Success(ComplaintReportAttempt.Completed(ComplaintReportApplication.Applied(REPLY_ID, 1)))
    }
    var onReconcile: suspend () -> AppResult<ComplaintReportRecovery> = { reports.reconcile() }
    var onObserve: suspend () -> AppResult<ComplaintInstallationDeletionObservation> = { deletion.observeDeletion() }
    private val recoveryRepository =
        object : ComplaintReportRepository by reports {
            override suspend fun reconcile(): AppResult<ComplaintReportRecovery> {
                calls += "reconcile"
                return onReconcile()
            }
        }
    private val installationRepository =
        object : ComplaintInstallationDeletionRepository by deletion {
            override suspend fun observeDeletion(): AppResult<ComplaintInstallationDeletionObservation> {
                calls += "observe"
                return onObserve()
            }
        }

    fun model(target: ComplaintReplyTarget? = ComplaintReplyTarget.capture(replyParent())): ComplaintReplyViewModel {
        val store = ViewModelStore()
        val factory = viewModelFactory { initializer { createModel(target) } }
        val model = ViewModelProvider.create(store, factory)[ComplaintReplyViewModel::class]
        stores[model] = store
        return model
    }

    private fun createModel(target: ComplaintReplyTarget?): ComplaintReplyViewModel =
        ComplaintReplyViewModel(
            ComplaintReplyActions(
                PrepareComplaintReplyUseCase(this),
                SubmitComplaintReplyUseCase(this),
                RetryComplaintReplyUseCase(this),
            ),
            ComplaintReportRecoveryActions(
                ReconcileComplaintReportsUseCase(recoveryRepository),
                CancelPreparedComplaintReportUseCase(recoveryRepository),
                RequestComplaintReportRecoveryUseCase(recoveryRepository),
                CancelComplaintReportRecoveryUseCase(recoveryRepository),
                ConfirmComplaintReportRecoveryUseCase(recoveryRepository),
            ),
            ObserveComplaintInstallationDeletionUseCase(installationRepository),
            target,
        )

    fun clear(model: ComplaintReplyViewModel) {
        stores.remove(model)?.clear()
    }

    fun clearAll() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }

    override suspend fun prepare(draft: ComplaintReplyDraft): AppResult<ComplaintReplyPreparation> {
        calls += "prepare"
        drafts += draft
        return onPrepare(draft)
    }

    override suspend fun submit(reply: ComplaintLiveReply): AppResult<ComplaintReportSubmission> {
        calls += "submit"
        submitted += reply
        return onSubmit(reply)
    }

    override suspend fun retry(reply: ComplaintLiveReply): AppResult<ComplaintReportAttempt> {
        calls += "retry"
        retried += reply
        return onRetry(reply)
    }

    fun assertNoRecoveryMutation() {
        assertTrue(reports.preparedCancelled.isEmpty() && reports.requested.isEmpty())
        assertTrue(reports.dismissed.isEmpty() && reports.confirmed.isEmpty())
        assertTrue(reports.drafts.isEmpty() && reports.submitted.isEmpty() && reports.retried.isEmpty())
        assertEquals(0, reports.unreadableRequests + reports.abandonmentRequests + reports.cleanupChecks)
        assertEquals(0, deletion.requests + deletion.continuations)
        assertTrue(deletion.confirmed.isEmpty() && deletion.dismissed.isEmpty())
    }
}

/** Models a port returning (or throwing) only after cancellation cleanup is explicitly released. */
internal class LateReplyWork {
    val caller = CompletableDeferred<Job>()
    val closing = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    suspend fun <T> finish(outcome: () -> AppResult<T>): AppResult<T> {
        caller.complete(currentCoroutineContext().job)
        return try {
            awaitCancellation()
        } catch (_: CancellationException) {
            withContext(NonCancellable) {
                closing.complete(Unit)
                release.await()
                outcome()
            }
        }
    }
}

private class TestLiveReply : ComplaintLiveReply {
    override fun toString(): String = "TestLiveReply(redacted)"
}

internal fun replyParent(
    type: ComplaintHistoryType = ComplaintHistoryType.Known(ComplaintType.TECHNICAL),
    status: ComplaintHistoryStatus = ComplaintHistoryStatus.Known(ComplaintStatus.OPEN),
): ComplaintDetail.Owned =
    ComplaintDetail.Owned(ComplaintOwnerRow.Report(replyFields(status), type, "synthetic private subject"))

internal fun replyFields(
    status: ComplaintHistoryStatus = ComplaintHistoryStatus.Known(ComplaintStatus.OPEN),
): ComplaintOwnerFields =
    ComplaintOwnerFields(
        id = PARENT_ID,
        body = "synthetic private parent body",
        status = status,
        createdAt = REPLY_TIME,
        updatedAt = REPLY_TIME,
        version = 1,
        actionTag = "synthetic private read tag",
        appVersion = null,
        platform = ComplaintHistoryPlatform.ANDROID,
        osVersion = null,
        manufacturer = null,
        deviceModel = null,
        closureReason = null,
    )

internal const val PARENT_ID = "11111111-1111-4111-8111-111111111111"
internal const val REPLY_ID = "22222222-2222-4222-8222-222222222222"
internal const val REPLY_BODY = "  Synthetic private reply e\u0301\nsecond line  "
internal val REPLY_TIME: Instant = Instant.parse("2026-09-18T00:00:00Z")

internal fun blockedReplyDetails(): List<ComplaintDetail> =
    listOf(
        ComplaintDetail.Unavailable,
        ComplaintDetail.Notice(ComplaintNotice(PARENT_ID, "synthetic_private_notice", REPLY_TIME, REPLY_TIME, 1)),
        ComplaintDetail.Owned(ComplaintOwnerRow.NoticeReply(replyFields(), "synthetic_private_notice", REPLY_ID)),
        ComplaintDetail.Owned(UnknownComplaintItem(PARENT_ID, "synthetic_private_kind", REPLY_TIME, REPLY_TIME)),
        replyParent(type = ComplaintHistoryType.Unrecognized),
        replyParent(status = ComplaintHistoryStatus.Unrecognized),
        ordinaryReplyDetail(ComplaintHistoryType.Unrecognized),
        ordinaryReplyDetail(status = ComplaintHistoryStatus.Unrecognized),
    )

internal fun ordinaryReplyDetail(
    type: ComplaintHistoryType = ComplaintHistoryType.Known(ComplaintType.TECHNICAL),
    status: ComplaintHistoryStatus = ComplaintHistoryStatus.Known(ComplaintStatus.OPEN),
): ComplaintDetail.Owned =
    ComplaintDetail.Owned(ComplaintOwnerRow.Reply(replyFields(status), type, "synthetic private subject", REPLY_ID))

internal fun failedReplyRetries(): List<suspend () -> AppResult<ComplaintReportAttempt>> =
    buildList {
        add { AppResult.Failure(AppError.Network.Http(404)) }
        add { AppResult.Failure(AppError.Network.Http(401)) }
        add { throw CancellationException("synthetic private retry cancellation") }
        add { throw AssertionError("synthetic private retry failure") }
        val other = SettingsFeedbackRepositoryFake()
        val foreign =
            listOf(
                ComplaintReportApplication.Edit(ComplaintEditApplication.Applied(REPLY_ID, 2)),
                ComplaintReportApplication.OwnerDelete(ComplaintOwnerDeleteApplication.Applied),
            )
        for (application in foreign) {
            add { AppResult.Success(ComplaintReportAttempt.Completed(application)) }
            add { AppResult.Success(other.unresolved(application)) }
        }
    }

internal fun blockedReplyFailure(block: ComplaintReportBlock): ComplaintReportFailure =
    ComplaintReportFailure(AppError.Platform.FeatureUnavailable("synthetic reply block"), block)

internal data class ReplyInstallationCase(
    val observation: AppResult<ComplaintInstallationDeletionObservation>,
    val expected: SettingsFeedbackDeletionState,
)

internal fun blockedInstallations(): List<ReplyInstallationCase> =
    listOf(
        ReplyInstallationCase(
            AppResult.Success(ComplaintInstallationDeletionObservation.Missing),
            SettingsFeedbackDeletionState.Missing,
        ),
        ReplyInstallationCase(
            AppResult.Success(ComplaintInstallationDeletionObservation.LocalCleanupRequired),
            SettingsFeedbackDeletionState.LocalCleanupRequired,
        ),
        ReplyInstallationCase(
            AppResult.Success(ComplaintInstallationDeletionObservation.RemoteDeletionPending),
            SettingsFeedbackDeletionState.Pending(),
        ),
        ReplyInstallationCase(AppResult.Failure(AppError.Storage.Io()), SettingsFeedbackDeletionState.Uncertain),
    )

internal fun readyReplyDetails(): List<ComplaintDetail> =
    listOf(
        replyParent(),
        ordinaryReplyDetail(),
        replyParent(status = ComplaintHistoryStatus.Known(ComplaintStatus.UNKNOWN)),
    )

internal fun holdOpeningReads(
    fixture: ComplaintReplyPanelFixture,
    local: CompletableDeferred<Unit>,
    metadata: CompletableDeferred<Unit>,
) {
    fixture.onObserve = {
        local.await()
        fixture.deletion.observeDeletion()
    }
    fixture.onReconcile = {
        metadata.await()
        fixture.reports.reconcile()
    }
}
