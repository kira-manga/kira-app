package me.manga.kira.ui.settings.feedback

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackIntent
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackRecoveryKind
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.settings_installation_abandon_confirm
import me.manga.kira.ui.generated.resources.settings_installation_abandon_title
import me.manga.kira.ui.generated.resources.settings_installation_abandon_warning
import me.manga.kira.ui.generated.resources.settings_installation_continue_cleanup
import me.manga.kira.ui.generated.resources.settings_installation_deletion_local_only
import me.manga.kira.ui.generated.resources.settings_installation_recovery_title
import me.manga.kira.ui.generated.resources.settings_installation_review_abandonment
import me.manga.kira.ui.generated.resources.settings_installation_review_unreadable
import me.manga.kira.ui.generated.resources.settings_report_reset_confirm
import me.manga.kira.ui.generated.resources.settings_report_reset_title
import me.manga.kira.ui.generated.resources.settings_report_reset_warning
import org.jetbrains.compose.resources.stringResource

/** Explicit checks only. Neither a rendered button nor an error grants local cleanup authority. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun SettingsInstallationRecoveryActions(
    state: SettingsFeedbackState,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    val enabled = !state.busy && !state.confirmationPending
    HorizontalDivider()
    Text(
        stringResource(Res.string.settings_installation_recovery_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(stringResource(Res.string.settings_installation_deletion_local_only))
    TextButton(onClick = { onIntent(SettingsFeedbackIntent.RequestUnreadableRecovery) }, enabled = enabled) {
        Text(stringResource(Res.string.settings_installation_review_unreadable))
    }
    TextButton(onClick = { onIntent(SettingsFeedbackIntent.RequestDeletionAbandonment) }, enabled = enabled) {
        Text(stringResource(Res.string.settings_installation_review_abandonment))
    }
    TextButton(onClick = { onIntent(SettingsFeedbackIntent.ResumeCleanup) }, enabled = enabled) {
        Text(stringResource(Res.string.settings_installation_continue_cleanup))
    }
}

@Composable
internal fun settingsInstallationRecoveryTitle(kind: SettingsFeedbackRecoveryKind): String =
    when (kind) {
        SettingsFeedbackRecoveryKind.REPORT,
        SettingsFeedbackRecoveryKind.UNREADABLE,
        -> stringResource(Res.string.settings_report_reset_title)
        SettingsFeedbackRecoveryKind.ABANDON_DELETION ->
            stringResource(Res.string.settings_installation_abandon_title)
    }

@Composable
internal fun settingsInstallationRecoveryConfirmation(kind: SettingsFeedbackRecoveryKind): String =
    when (kind) {
        SettingsFeedbackRecoveryKind.REPORT,
        SettingsFeedbackRecoveryKind.UNREADABLE,
        -> stringResource(Res.string.settings_report_reset_confirm)
        SettingsFeedbackRecoveryKind.ABANDON_DELETION ->
            stringResource(Res.string.settings_installation_abandon_confirm)
    }

/** Called only inside the existing warning after a genuine prompt has supplied its content-free kind. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun SettingsInstallationRecoveryWarning(kind: SettingsFeedbackRecoveryKind) {
    Text(stringResource(Res.string.settings_report_reset_warning))
    if (kind == SettingsFeedbackRecoveryKind.ABANDON_DELETION) {
        Text(stringResource(Res.string.settings_installation_abandon_warning))
    }
}
