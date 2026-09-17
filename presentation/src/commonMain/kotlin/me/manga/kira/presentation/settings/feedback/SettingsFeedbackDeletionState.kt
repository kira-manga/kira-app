package me.manga.kira.presentation.settings.feedback

import me.manga.kira.core.error.AppError
import me.manga.kira.domain.repository.ComplaintInstallationDeletionObservation

/** Local observations and producer outcomes stay distinct. None exposes an identity or consent token. */
sealed interface SettingsFeedbackDeletionState {
    data object Checking : SettingsFeedbackDeletionState

    data object Active : SettingsFeedbackDeletionState

    data object Missing : SettingsFeedbackDeletionState

    data object LocalCleanupRequired : SettingsFeedbackDeletionState

    /** A failed/canceled confirmation may already have dispatched. Only fresh local observation can reopen actions. */
    data object Uncertain : SettingsFeedbackDeletionState

    data class Pending(
        val retryAfterSeconds: Int? = null,
        val error: AppError? = null,
    ) : SettingsFeedbackDeletionState {
        override fun toString(): String = "SettingsFeedbackDeletionState.Pending(redacted)"
    }

    /** Only the deletion producer may publish this, never Missing, local abandonment or generic cleanup Unit. */
    data object Completed : SettingsFeedbackDeletionState
}

internal fun ComplaintInstallationDeletionObservation.presentationState(): SettingsFeedbackDeletionState =
    when (this) {
        ComplaintInstallationDeletionObservation.Active -> SettingsFeedbackDeletionState.Active
        ComplaintInstallationDeletionObservation.Missing -> SettingsFeedbackDeletionState.Missing
        ComplaintInstallationDeletionObservation.LocalCleanupRequired ->
            SettingsFeedbackDeletionState.LocalCleanupRequired
        ComplaintInstallationDeletionObservation.RemoteDeletionPending -> SettingsFeedbackDeletionState.Pending()
    }

internal fun SettingsFeedbackDeletionState.canStartNewDraft(): Boolean =
    this == SettingsFeedbackDeletionState.Active ||
        this == SettingsFeedbackDeletionState.Missing ||
        this == SettingsFeedbackDeletionState.Completed

internal fun SettingsFeedbackState.withDeletion(deletion: SettingsFeedbackDeletionState): SettingsFeedbackState =
    copy(context = context.copy(deletion = deletion, missingInstallationObserved = false))

internal fun SettingsFeedbackState.withRemotePrompt(): SettingsFeedbackState =
    copy(context = context.copy(promptKind = SettingsFeedbackPromptKind.REMOTE_DELETION))
