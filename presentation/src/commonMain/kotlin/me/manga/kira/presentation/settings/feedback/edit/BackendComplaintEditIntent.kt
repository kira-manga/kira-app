package me.manga.kira.presentation.settings.feedback.edit

import me.manga.kira.presentation.mvi.MviIntent

/** Explicit edit actions only; no target replacement, new-key retry, enrollment or saved prose. */
sealed interface BackendComplaintEditIntent : MviIntent {
    data class ChangeSubject(
        val subject: String,
    ) : BackendComplaintEditIntent {
        override fun toString(): String = "BackendComplaintEditIntent.ChangeSubject(redacted)"
    }

    data class ChangeBody(
        val body: String,
    ) : BackendComplaintEditIntent {
        override fun toString(): String = "BackendComplaintEditIntent.ChangeBody(redacted)"
    }

    data object Submit : BackendComplaintEditIntent

    /** Ask the existing edit port to reconcile/retry this opening's exact live handle only. */
    data object Retry : BackendComplaintEditIntent

    /** Retire this editor before asking the caller for the existing Settings recovery opening. */
    data object OpenRecovery : BackendComplaintEditIntent

    /** Cancel this opening's work, not its durable pending evidence or shared backend owner. */
    data object Close : BackendComplaintEditIntent
}
