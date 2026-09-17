package me.manga.kira.presentation.settings.feedback

import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.presentation.mvi.MviIntent

/** Explicit user actions only. Prose and opaque observations never appear in diagnostic rendering. */
sealed interface SettingsFeedbackIntent : MviIntent {
    data class ChangeCategory(
        val type: ComplaintType,
        val subject: String,
    ) : SettingsFeedbackIntent {
        override fun toString(): String = "SettingsFeedbackIntent.ChangeCategory(redacted)"
    }

    data class ChangeSubject(
        val subject: String,
    ) : SettingsFeedbackIntent {
        override fun toString(): String = "SettingsFeedbackIntent.ChangeSubject(redacted)"
    }

    data class ChangeBody(
        val body: String,
    ) : SettingsFeedbackIntent {
        override fun toString(): String = "SettingsFeedbackIntent.ChangeBody(redacted)"
    }

    data object Submit : SettingsFeedbackIntent

    data object Retry : SettingsFeedbackIntent

    data object RefreshRecovery : SettingsFeedbackIntent

    /** Explicit safe history enrollment/read only, never implicit report creation or submission. */
    data object SetupHistory : SettingsFeedbackIntent

    /** Request a fresh eligibility check and warning, never infer reset permission from an error. */
    data object RequestUnreadableRecovery : SettingsFeedbackIntent

    /** Review local abandonment of an existing deletion only; this never starts remote deletion. */
    data object RequestDeletionAbandonment : SettingsFeedbackIntent

    /** Explicitly continue existing durable cleanup authority without enrolling or submitting. */
    data object ResumeCleanup : SettingsFeedbackIntent

    class CancelPrepared(
        val report: ComplaintPendingReport,
    ) : SettingsFeedbackIntent {
        override fun toString(): String = "SettingsFeedbackIntent.CancelPrepared(redacted)"
    }

    class RequestRecovery(
        val report: ComplaintPendingReport,
    ) : SettingsFeedbackIntent {
        override fun toString(): String = "SettingsFeedbackIntent.RequestRecovery(redacted)"
    }

    data object CancelRecovery : SettingsFeedbackIntent

    data object ConfirmRecovery : SettingsFeedbackIntent

    data object NewDraft : SettingsFeedbackIntent

    /** Cancel only this VM's coroutine work and dismiss its exact prompt, without clearing pending records. */
    data object Close : SettingsFeedbackIntent
}
