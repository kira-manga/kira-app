package me.manga.kira.ui.settings.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportPending
import me.manga.kira.domain.model.feedback.ComplaintReportPhase
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackIntent
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackRecoveryKind
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackResult
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.cancel
import me.manga.kira.ui.generated.resources.settings_report_cancel_prepared
import me.manga.kira.ui.generated.resources.settings_report_may_have_sent
import me.manga.kira.ui.generated.resources.settings_report_pending_title
import me.manga.kira.ui.generated.resources.settings_report_prepared
import me.manga.kira.ui.generated.resources.settings_report_recovery_empty
import me.manga.kira.ui.generated.resources.settings_report_recovery_title
import me.manga.kira.ui.generated.resources.settings_report_refresh
import me.manga.kira.ui.generated.resources.settings_report_request_reset
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** Bounded observations only; an empty result never enables a replacement for a live request. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun SettingsReportRecoveryContent(
    state: SettingsFeedbackState,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        HorizontalDivider()
        Text(stringResource(Res.string.settings_report_recovery_title), style = MaterialTheme.typography.titleSmall)
        TextButton(
            onClick = { onIntent(SettingsFeedbackIntent.RefreshRecovery) },
            enabled = state.editable,
        ) {
            Text(stringResource(Res.string.settings_report_refresh))
        }
        SettingsReportRecoveryObservations(state, onIntent)
        SettingsInstallationRecoveryActions(state, onIntent)
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun SettingsReportRecoveryObservations(
    state: SettingsFeedbackState,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    val recovery = state.recovery ?: return
    val entries = recovery.entries()
    if (entries.isEmpty()) Text(stringResource(Res.string.settings_report_recovery_empty))
    recovery.stopped?.let { Text(settingsReportFailureText(it)) }
    entries.forEachIndexed { index, observation ->
        Text(
            stringResource(Res.string.settings_report_pending_title, index + 1),
            style = MaterialTheme.typography.labelLarge,
        )
        SettingsReportAttemptSummary(observation.attempt)
        if (observation.attempt is ComplaintReportAttempt.Unresolved) {
            SettingsReportPendingActions(
                observation.pending,
                !state.busy && !state.confirmationPending,
                onIntent,
            )
        }
    }
}

/** Buttons carry the exact observed handle; the repository rechecks whether the action is allowed. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun SettingsReportPendingActions(
    pending: ComplaintReportPending,
    enabled: Boolean,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    when (pending.phase) {
        ComplaintReportPhase.PREPARED -> {
            Text(stringResource(Res.string.settings_report_prepared))
            TextButton(
                onClick = { onIntent(SettingsFeedbackIntent.CancelPrepared(pending.handle)) },
                enabled = enabled,
            ) {
                Text(stringResource(Res.string.settings_report_cancel_prepared))
            }
        }
        ComplaintReportPhase.MAY_HAVE_DISPATCHED -> {
            Text(stringResource(Res.string.settings_report_may_have_sent))
            TextButton(
                onClick = { onIntent(SettingsFeedbackIntent.RequestRecovery(pending.handle)) },
                enabled = enabled,
            ) {
                Text(stringResource(Res.string.settings_report_request_reset))
            }
        }
    }
}

/** Local reset/abandonment requires a real prompt and this warning; dismissal grants no cleanup authority. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun SettingsReportResetDialog(
    state: SettingsFeedbackState,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    val kind = state.recoveryKind ?: return
    AlertDialog(
        onDismissRequest = { if (!state.busy) onIntent(SettingsFeedbackIntent.CancelRecovery) },
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        title = { Text(settingsInstallationRecoveryTitle(kind)) },
        text = { SettingsReportResetWarning(state, kind) },
        confirmButton = {
            TextButton(onClick = { onIntent(SettingsFeedbackIntent.ConfirmRecovery) }, enabled = !state.busy) {
                Text(settingsInstallationRecoveryConfirmation(kind))
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(SettingsFeedbackIntent.CancelRecovery) }, enabled = !state.busy) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun SettingsReportResetWarning(
    state: SettingsFeedbackState,
    kind: SettingsFeedbackRecoveryKind,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        SettingsInstallationRecoveryWarning(kind)
        val failure =
            when (val result = state.result) {
                is SettingsFeedbackResult.Failure -> result.failure
                is SettingsFeedbackResult.Attempt -> (result.attempt as? ComplaintReportAttempt.Unresolved)?.failure
                else -> null
            }
        failure?.let { Text(settingsReportFailureText(it)) }
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
