package me.manga.kira.presentation.settings.feedback

import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery

internal fun SettingsFeedbackEntry.initialState(): SettingsFeedbackState =
    SettingsFeedbackState(
        context = SettingsFeedbackContext(this),
    )

internal fun SettingsFeedbackState.withMissingObservation(missing: Boolean): SettingsFeedbackState =
    copy(
        context = context.copy(missingInstallationObserved = missing),
    )

internal fun SettingsFeedbackState.withConfirmation(pending: Boolean): SettingsFeedbackState =
    copy(
        context = context.copy(confirmationPending = pending),
    )

internal fun SettingsFeedbackState.afterWork(
    terminal: Boolean,
    hasLiveReport: Boolean,
): SettingsFeedbackState =
    copy(
        activity =
            when {
                terminal -> SettingsFeedbackActivity.TERMINAL
                hasLiveReport -> SettingsFeedbackActivity.LIVE
                else -> SettingsFeedbackActivity.EDITING
            },
    )

internal fun SettingsFeedbackState.withRecovery(observation: ComplaintReportRecovery): SettingsFeedbackState =
    copy(
        recovery = observation,
        context = context.copy(missingInstallationObserved = observation.stopped?.block == ComplaintReportBlock.MISSING),
    )

internal fun SettingsFeedbackState.afterHistorySetup(): SettingsFeedbackState =
    copy(
        result = SettingsFeedbackResult.HistorySetupCompleted,
        recovery = null,
        context = context.copy(missingInstallationObserved = false),
    )

internal fun SettingsFeedbackState.afterLocalReset(): SettingsFeedbackState =
    copy(
        draft = entry.initialDraft(),
        result = SettingsFeedbackResult.LocalResetCompleted,
        recovery = null,
        context = SettingsFeedbackContext(entry),
    )
