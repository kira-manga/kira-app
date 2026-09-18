package me.manga.kira.presentation.settings.feedback.reply

import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackDeletionState

internal fun initialReplyState(targetAvailable: Boolean): ComplaintReplyState =
    ComplaintReplyState(
        context = ComplaintReplyContext(targetAvailable),
        activity = if (targetAvailable) ComplaintReplyActivity.CHECKING else ComplaintReplyActivity.BLOCKED,
    )

internal fun ComplaintReplyState.afterReplyWork(
    hasLiveReply: Boolean,
    completed: Boolean,
): ComplaintReplyState =
    copy(
        activity =
            when {
                isClosed -> ComplaintReplyActivity.CLOSED
                completed -> ComplaintReplyActivity.TERMINAL
                !context.targetAvailable || context.installation != SettingsFeedbackDeletionState.Active ->
                    ComplaintReplyActivity.BLOCKED
                hasLiveReply -> ComplaintReplyActivity.LIVE
                else -> ComplaintReplyActivity.EDITING
            },
    )

/** Preserve receipt facts, not permission to delete a record or repeat the mutation. */
internal fun ComplaintReportAttempt.retainingReplyReceipt(previous: ComplaintReportAttempt?): ComplaintReportAttempt =
    when (this) {
        is ComplaintReportAttempt.Completed -> this
        is ComplaintReportAttempt.Unresolved ->
            ComplaintReportAttempt.Unresolved(failure, knownApplication ?: previous.knownReplyApplication(), pending)
    }

internal fun ComplaintReportFailure.afterReplyAttempt(previous: ComplaintReportAttempt?): ComplaintReportAttempt =
    ComplaintReportAttempt.Unresolved(
        failure = this,
        knownApplication = previous.knownReplyApplication(),
        pending = (previous as? ComplaintReportAttempt.Unresolved)?.pending,
    )

private fun ComplaintReportAttempt?.knownReplyApplication(): ComplaintReportApplication? =
    when (this) {
        is ComplaintReportAttempt.Completed -> application
        is ComplaintReportAttempt.Unresolved -> knownApplication
        null -> null
    }

internal fun closedReplyState(): ComplaintReplyState =
    ComplaintReplyState(
        context = ComplaintReplyContext(false, SettingsFeedbackDeletionState.Uncertain),
        activity = ComplaintReplyActivity.CLOSED,
    )

/** Producer refusals may narrow an earlier local observation, never authorize setup or cleanup. */
internal fun ComplaintReplyState.withReplyFailureObservation(failure: ComplaintReportFailure?): ComplaintReplyState {
    val installation =
        when (failure?.block) {
            ComplaintReportBlock.MISSING -> SettingsFeedbackDeletionState.Missing
            ComplaintReportBlock.CLEANUP_REQUIRED -> SettingsFeedbackDeletionState.LocalCleanupRequired
            ComplaintReportBlock.REMOTE_DELETION_PENDING -> SettingsFeedbackDeletionState.Pending()
            ComplaintReportBlock.STALE_BINDING -> SettingsFeedbackDeletionState.Uncertain
            else ->
                if (context.installation == SettingsFeedbackDeletionState.Checking) {
                    SettingsFeedbackDeletionState.Uncertain
                } else {
                    context.installation
                }
        }
    return copy(context = context.copy(installation = installation))
}
