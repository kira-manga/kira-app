package me.manga.kira.presentation.settings.feedback

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintPendingReport
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery

internal fun SettingsFeedbackEntry.initialState(): SettingsFeedbackState =
    SettingsFeedbackState(
        context = SettingsFeedbackContext(this),
    )

internal fun SettingsFeedbackState.withMissingObservation(missing: Boolean): SettingsFeedbackState =
    copy(
        context = context.copy(missingInstallationObserved = missing),
    )

internal fun SettingsFeedbackState.withRecoveryKind(kind: SettingsFeedbackRecoveryKind?): SettingsFeedbackState =
    copy(
        context =
            context.copy(
                promptKind =
                    when (kind) {
                        SettingsFeedbackRecoveryKind.REPORT -> SettingsFeedbackPromptKind.REPORT
                        SettingsFeedbackRecoveryKind.UNREADABLE -> SettingsFeedbackPromptKind.UNREADABLE
                        SettingsFeedbackRecoveryKind.ABANDON_DELETION -> SettingsFeedbackPromptKind.ABANDON_DELETION
                        null -> null
                    },
            ),
    )

internal fun SettingsFeedbackState.withPreparation(prepared: ComplaintReportPreparation): SettingsFeedbackState =
    withMissingObservation(prepared.isMissingPreparation()).copy(
        result =
            when (prepared) {
                is ComplaintReportPreparation.Ready -> result
                is ComplaintReportPreparation.Invalid ->
                    SettingsFeedbackResult.Invalid(prepared.field, prepared.reason)
                is ComplaintReportPreparation.Blocked -> SettingsFeedbackResult.Failure(prepared.failure)
            },
    )

internal fun SettingsFeedbackState.withRecovery(result: AppResult<ComplaintReportRecovery>): SettingsFeedbackState =
    when (result) {
        is AppResult.Success -> withRecovery(result.value)
        is AppResult.Failure ->
            withMissingObservation(false).copy(
                result = SettingsFeedbackResult.Failure(ComplaintReportFailure(result.error)),
            )
    }

internal fun SettingsFeedbackState.afterWork(
    terminal: Boolean,
    hasLiveReport: Boolean,
): SettingsFeedbackState =
    copy(
        activity =
            when {
                terminal -> SettingsFeedbackActivity.TERMINAL
                !normalActionsAllowed && deletion != SettingsFeedbackDeletionState.Missing ->
                    SettingsFeedbackActivity.BLOCKED
                hasLiveReport -> SettingsFeedbackActivity.LIVE
                else -> SettingsFeedbackActivity.EDITING
            },
    )

internal fun SettingsFeedbackState.withRecovery(observation: ComplaintReportRecovery): SettingsFeedbackState =
    copy(
        recovery = observation,
        context =
            context.copy(
                missingInstallationObserved = observation.stopped?.block == ComplaintReportBlock.MISSING,
            ),
    )

internal fun SettingsFeedbackState.afterHistorySetup(): SettingsFeedbackState =
    copy(
        result = SettingsFeedbackResult.HistorySetupCompleted,
        recovery = null,
        context = context.copy(missingInstallationObserved = false),
    )

internal fun SettingsFeedbackState.afterPreparedCancellation(report: ComplaintPendingReport): SettingsFeedbackState =
    copy(
        result = SettingsFeedbackResult.PreparedCancelled,
        recovery = recovery?.without(report),
    )

internal fun SettingsFeedbackState.afterLocalReset(kind: SettingsFeedbackRecoveryKind): SettingsFeedbackState =
    copy(
        draft = entry.initialDraft(),
        result =
            if (kind == SettingsFeedbackRecoveryKind.ABANDON_DELETION) {
                SettingsFeedbackResult.LocalDeletionAbandoned
            } else {
                SettingsFeedbackResult.LocalResetCompleted
            },
        recovery = null,
        context = SettingsFeedbackContext(entry, deletion = SettingsFeedbackDeletionState.Uncertain),
    )
