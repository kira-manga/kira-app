package me.manga.kira.presentation.settings.feedback

import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportDraft
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportField
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.model.feedback.ComplaintReportRejection
import me.manga.kira.presentation.mvi.MviState

/** A live request cannot be edited/replaced; terminal results require an explicit new draft. */
enum class SettingsFeedbackActivity { EDITING, WORKING, LIVE, TERMINAL }

/** Content-free warning copy; the matching exact consent token remains private to the ViewModel. */
enum class SettingsFeedbackRecoveryKind { REPORT, UNREADABLE, ABANDON_DELETION }

/** Typed rendering results. Local reset is deliberately not represented as remote deletion success. */
sealed interface SettingsFeedbackResult {
    data class Invalid(
        val field: ComplaintReportField,
        val reason: ComplaintReportRejection,
    ) : SettingsFeedbackResult

    class Attempt(
        val attempt: ComplaintReportAttempt,
    ) : SettingsFeedbackResult {
        override fun toString(): String = "SettingsFeedbackResult.Attempt(redacted)"
    }

    class Failure(
        val failure: ComplaintReportFailure,
    ) : SettingsFeedbackResult {
        override fun toString(): String = "SettingsFeedbackResult.Failure(redacted)"
    }

    data object PreparedCancelled : SettingsFeedbackResult

    data object LocalResetCompleted : SettingsFeedbackResult

    /** Local deletion continuation was abandoned; this is never a server-erasure receipt. */
    data object LocalDeletionAbandoned : SettingsFeedbackResult

    /** Existing persisted cleanup was checked; success may be an ACTIVE no-op, not erased identity. */
    data object CleanupCheckCompleted : SettingsFeedbackResult

    /** The explicit history/setup read finished; no report was prepared or submitted by that action. */
    data object HistorySetupCompleted : SettingsFeedbackResult
}

/** Memory-only Settings feedback substate; no SavedStateHandle, serialization or metadata exposure. */
data class SettingsFeedbackState(
    val context: SettingsFeedbackContext = SettingsFeedbackContext(),
    val draft: ComplaintReportDraft = context.entry.initialDraft(),
    val activity: SettingsFeedbackActivity = SettingsFeedbackActivity.EDITING,
    val result: SettingsFeedbackResult? = null,
    val recovery: ComplaintReportRecovery? = null,
) : MviState {
    val entry: SettingsFeedbackEntry get() = context.entry
    val recoveryKind: SettingsFeedbackRecoveryKind? get() = context.recoveryKind
    val confirmationPending: Boolean get() = recoveryKind != null
    val busy: Boolean get() = activity == SettingsFeedbackActivity.WORKING
    val editable: Boolean get() = activity == SettingsFeedbackActivity.EDITING && !confirmationPending
    val canRetry: Boolean get() = activity == SettingsFeedbackActivity.LIVE && !confirmationPending
    val canStartNewDraft: Boolean get() = activity == SettingsFeedbackActivity.TERMINAL && !confirmationPending
    val canSetupHistory: Boolean get() = editable && context.missingInstallationObserved

    override fun toString(): String = "SettingsFeedbackState(redacted)"
}

/** Immutable per-opening presentation context, never an installation session or admission authority. */
data class SettingsFeedbackContext(
    val entry: SettingsFeedbackEntry = SettingsFeedbackEntry.General,
    /** Last report-side MISSING observation; only gates the explicit safe history read. */
    val missingInstallationObserved: Boolean = false,
    val recoveryKind: SettingsFeedbackRecoveryKind? = null,
) {
    override fun toString(): String = "SettingsFeedbackContext(redacted)"
}
