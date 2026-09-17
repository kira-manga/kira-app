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
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackDeletionState
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackIntent
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.cancel
import me.manga.kira.ui.generated.resources.settings_installation_deletion_active
import me.manga.kira.ui.generated.resources.settings_installation_deletion_check
import me.manga.kira.ui.generated.resources.settings_installation_deletion_checking
import me.manga.kira.ui.generated.resources.settings_installation_deletion_cleanup
import me.manga.kira.ui.generated.resources.settings_installation_deletion_close_notice
import me.manga.kira.ui.generated.resources.settings_installation_deletion_completed
import me.manga.kira.ui.generated.resources.settings_installation_deletion_confirm
import me.manga.kira.ui.generated.resources.settings_installation_deletion_continue
import me.manga.kira.ui.generated.resources.settings_installation_deletion_local_only
import me.manga.kira.ui.generated.resources.settings_installation_deletion_missing
import me.manga.kira.ui.generated.resources.settings_installation_deletion_pending
import me.manga.kira.ui.generated.resources.settings_installation_deletion_request
import me.manga.kira.ui.generated.resources.settings_installation_deletion_retry_after
import me.manga.kira.ui.generated.resources.settings_installation_deletion_retry_failed
import me.manga.kira.ui.generated.resources.settings_installation_deletion_title
import me.manga.kira.ui.generated.resources.settings_installation_deletion_uncertain
import me.manga.kira.ui.generated.resources.settings_installation_deletion_uninstall
import me.manga.kira.ui.generated.resources.settings_installation_deletion_warning
import me.manga.kira.ui.generated.resources.settings_installation_deletion_warning_title
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** Render producer state only; checking and continuation always require a user action here. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun SettingsInstallationDeletionContent(
    state: SettingsFeedbackState,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        HorizontalDivider()
        Text(stringResource(Res.string.settings_installation_deletion_title), style = MaterialTheme.typography.titleSmall)
        val deletion = state.deletion
        Text(settingsInstallationDeletionStatus(deletion))
        if (deletion is SettingsFeedbackDeletionState.Pending) SettingsInstallationDeletionPendingInfo(deletion)
        if (deletion is SettingsFeedbackDeletionState.Pending || deletion == SettingsFeedbackDeletionState.Uncertain) {
            Text(stringResource(Res.string.settings_installation_deletion_close_notice))
        }
        SettingsInstallationDeletionActions(state, onIntent)
    }
}

/** Only the deletion producer's Completed outcome uses completion copy; local observations never do. */
@Composable
private fun settingsInstallationDeletionStatus(deletion: SettingsFeedbackDeletionState): String =
    when (deletion) {
        SettingsFeedbackDeletionState.Checking -> stringResource(Res.string.settings_installation_deletion_checking)
        SettingsFeedbackDeletionState.Active -> stringResource(Res.string.settings_installation_deletion_active)
        SettingsFeedbackDeletionState.Missing -> stringResource(Res.string.settings_installation_deletion_missing)
        SettingsFeedbackDeletionState.LocalCleanupRequired ->
            stringResource(Res.string.settings_installation_deletion_cleanup)
        SettingsFeedbackDeletionState.Uncertain -> stringResource(Res.string.settings_installation_deletion_uncertain)
        is SettingsFeedbackDeletionState.Pending -> stringResource(Res.string.settings_installation_deletion_pending)
        SettingsFeedbackDeletionState.Completed -> stringResource(Res.string.settings_installation_deletion_completed)
    }

/** No raw error, identifier or request data is rendered. The server delay is a hint, not a timer. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun SettingsInstallationDeletionPendingInfo(pending: SettingsFeedbackDeletionState.Pending) {
    if (pending.error != null) Text(stringResource(Res.string.settings_installation_deletion_retry_failed))
    pending.retryAfterSeconds?.let { seconds ->
        Text(stringResource(Res.string.settings_installation_deletion_retry_after, seconds))
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun SettingsInstallationDeletionActions(
    state: SettingsFeedbackState,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    when (state.deletion) {
        SettingsFeedbackDeletionState.Active ->
            TextButton(
                onClick = { onIntent(SettingsFeedbackIntent.RequestRemoteDeletion) },
                enabled = state.canRequestRemoteDeletion,
            ) {
                Text(stringResource(Res.string.settings_installation_deletion_request))
            }
        is SettingsFeedbackDeletionState.Pending ->
            TextButton(
                onClick = { onIntent(SettingsFeedbackIntent.ContinueRemoteDeletion) },
                enabled = state.canContinueRemoteDeletion,
            ) {
                Text(stringResource(Res.string.settings_installation_deletion_continue))
            }
        else -> Unit
    }
    TextButton(
        onClick = { onIntent(SettingsFeedbackIntent.CheckInstallation) },
        enabled = !state.busy && !state.confirmationPending,
    ) {
        Text(stringResource(Res.string.settings_installation_deletion_check))
    }
}

/** Remote consent cannot invoke local ConfirmRecovery/CancelRecovery or outlive its state-owned prompt. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun SettingsInstallationDeletionDialog(
    state: SettingsFeedbackState,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    if (!state.remoteConfirmationPending) return
    AlertDialog(
        onDismissRequest = { if (!state.busy) onIntent(SettingsFeedbackIntent.CancelRemoteDeletion) },
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        title = { Text(stringResource(Res.string.settings_installation_deletion_warning_title)) },
        text = { SettingsInstallationDeletionWarning(state.busy) },
        confirmButton = {
            TextButton(onClick = { onIntent(SettingsFeedbackIntent.ConfirmRemoteDeletion) }, enabled = !state.busy) {
                Text(stringResource(Res.string.settings_installation_deletion_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(SettingsFeedbackIntent.CancelRemoteDeletion) }, enabled = !state.busy) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun SettingsInstallationDeletionWarning(busy: Boolean) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        Text(stringResource(Res.string.settings_installation_deletion_warning))
        Text(stringResource(Res.string.settings_installation_deletion_close_notice))
        Text(stringResource(Res.string.settings_installation_deletion_uninstall))
        Text(stringResource(Res.string.settings_installation_deletion_local_only))
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
