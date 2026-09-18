package me.manga.kira.presentation.settings.feedback.reply

import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportField
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.model.feedback.ComplaintReportRejection
import me.manga.kira.presentation.mvi.MviState
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackDeletionState

/** One original reply only; terminal completion never starts another draft or refreshes its parent. */
enum class ComplaintReplyActivity { CHECKING, BLOCKED, EDITING, WORKING, LIVE, TERMINAL, CLOSED }

/** Rendering observations only, never an installation session or pending-operation capability. */
data class ComplaintReplyContext(
    val targetAvailable: Boolean,
    val installation: SettingsFeedbackDeletionState = SettingsFeedbackDeletionState.Checking,
)

/** Content-free validation/failure facts and the original operation receipt. */
sealed interface ComplaintReplyResult {
    data class Invalid(
        val field: ComplaintReportField,
        val reason: ComplaintReportRejection,
    ) : ComplaintReplyResult

    class Failure(
        val failure: ComplaintReportFailure,
    ) : ComplaintReplyResult {
        override fun toString(): String = "ComplaintReplyResult.Failure(redacted)"
    }

    class Attempt(
        val attempt: ComplaintReportAttempt,
    ) : ComplaintReplyResult {
        override fun toString(): String = "ComplaintReplyResult.Attempt(redacted)"
    }
}

/** Memory-only UI state. The original parent ID and live reply handle stay private to the VM. */
data class ComplaintReplyState(
    val context: ComplaintReplyContext,
    val body: String = "",
    val activity: ComplaintReplyActivity = ComplaintReplyActivity.CHECKING,
    val result: ComplaintReplyResult? = null,
    val recovery: ComplaintReportRecovery? = null,
) : MviState {
    val busy: Boolean
        get() = activity == ComplaintReplyActivity.CHECKING || activity == ComplaintReplyActivity.WORKING
    val isClosed: Boolean get() = activity == ComplaintReplyActivity.CLOSED
    val editable: Boolean get() = activeTarget && activity == ComplaintReplyActivity.EDITING
    val canSubmit: Boolean get() = editable
    val canRetry: Boolean get() = activeTarget && activity == ComplaintReplyActivity.LIVE
    val canRefreshRecovery: Boolean
        get() =
            context.targetAvailable && !busy && !isClosed &&
                result !is ComplaintReplyResult.Attempt && activity != ComplaintReplyActivity.TERMINAL
    val canOpenRecovery: Boolean get() = !isClosed

    private val activeTarget: Boolean
        get() = context.targetAvailable && context.installation == SettingsFeedbackDeletionState.Active

    override fun toString(): String = "ComplaintReplyState(redacted)"
}
