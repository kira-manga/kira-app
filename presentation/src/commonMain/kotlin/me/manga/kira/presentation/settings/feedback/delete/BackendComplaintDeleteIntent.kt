package me.manga.kira.presentation.settings.feedback.delete

import me.manga.kira.presentation.mvi.MviIntent

/** Explicit confirmation and same-handle retry only; no target replacement or installation deletion. */
sealed interface BackendComplaintDeleteIntent : MviIntent {
    data object Confirm : BackendComplaintDeleteIntent
    data object Retry : BackendComplaintDeleteIntent
    data object OpenRecovery : BackendComplaintDeleteIntent
    data object Close : BackendComplaintDeleteIntent
}
