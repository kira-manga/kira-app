package me.manga.kira.ui.settings.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEffect
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackIntent
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackResult
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackState
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackViewModel
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.category
import me.manga.kira.ui.generated.resources.close
import me.manga.kira.ui.generated.resources.request_feature_bug
import me.manga.kira.ui.generated.resources.settings_report_memory_only
import me.manga.kira.ui.generated.resources.settings_report_new_draft
import me.manga.kira.ui.generated.resources.settings_report_prepared_cancelled
import me.manga.kira.ui.generated.resources.settings_report_reset_completed
import me.manga.kira.ui.generated.resources.settings_report_retry_same
import me.manga.kira.ui.generated.resources.subject
import me.manga.kira.ui.generated.resources.submit
import me.manga.kira.ui.generated.resources.your_feedback
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** Candidate-only feedback surface. Drafts and action handles stay in the memory-only ViewModel. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
fun SettingsFeedbackDialog(
    viewModel: SettingsFeedbackViewModel,
    onClosed: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    SettingsFeedbackDialogContent(state, viewModel.effects, viewModel::submit, onClosed)
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun SettingsFeedbackDialogContent(
    state: SettingsFeedbackState,
    effects: Flow<SettingsFeedbackEffect>,
    onIntent: (SettingsFeedbackIntent) -> Unit,
    onClosed: () -> Unit,
) {
    val latestOnClosed by rememberUpdatedState(onClosed)
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                // State, not a potentially stale queued effect, owns confirmation visibility.
                SettingsFeedbackEffect.ConfirmLocalReset -> Unit
                SettingsFeedbackEffect.Closed -> latestOnClosed()
            }
        }
    }
    if (state.confirmationPending) {
        SettingsReportResetDialog(state, onIntent)
    } else {
        AlertDialog(
            onDismissRequest = { onIntent(SettingsFeedbackIntent.Close) },
            title = { Text(stringResource(Res.string.request_feature_bug)) },
            text = { SettingsFeedbackBody(state, onIntent) },
            confirmButton = { SettingsFeedbackPrimaryAction(state, onIntent) },
            dismissButton = {
                TextButton(onClick = { onIntent(SettingsFeedbackIntent.Close) }) {
                    Text(stringResource(Res.string.close))
                }
            },
        )
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun SettingsFeedbackBody(
    state: SettingsFeedbackState,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
    ) {
        Text(stringResource(Res.string.settings_report_memory_only), style = MaterialTheme.typography.bodySmall)
        SettingsFeedbackDraftFields(state, onIntent)
        SettingsFeedbackResultContent(state, onIntent)
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        SettingsReportRecoveryContent(state, onIntent)
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun SettingsFeedbackDraftFields(
    state: SettingsFeedbackState,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        Text(stringResource(Res.string.category), style = MaterialTheme.typography.labelLarge)
        SettingsFeedbackCategory(state, onIntent)
        OutlinedTextField(
            value = state.draft.subject,
            onValueChange = { onIntent(SettingsFeedbackIntent.ChangeSubject(it)) },
            enabled = state.editable,
            label = { Text(stringResource(Res.string.subject)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.draft.body,
            onValueChange = { onIntent(SettingsFeedbackIntent.ChangeBody(it)) },
            enabled = state.editable,
            label = { Text(stringResource(Res.string.your_feedback)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = BODY_MIN_LINES,
            maxLines = BODY_MAX_LINES,
        )
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun SettingsFeedbackCategory(
    state: SettingsFeedbackState,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = state.editable) {
            Text(settingsReportCategoryText(state.draft.type))
        }
        DropdownMenu(expanded = expanded && state.editable, onDismissRequest = { expanded = false }) {
            ComplaintType.entries.forEach { type ->
                val label = settingsReportCategoryText(type)
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onIntent(SettingsFeedbackIntent.ChangeCategory(type, label))
                    },
                )
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun SettingsFeedbackPrimaryAction(
    state: SettingsFeedbackState,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    when {
        state.editable ->
            TextButton(onClick = { onIntent(SettingsFeedbackIntent.Submit) }) {
                Text(stringResource(Res.string.submit))
            }
        state.canRetry ->
            TextButton(onClick = { onIntent(SettingsFeedbackIntent.Retry) }) {
                Text(stringResource(Res.string.settings_report_retry_same))
            }
        state.canStartNewDraft ->
            TextButton(onClick = { onIntent(SettingsFeedbackIntent.NewDraft) }) {
                Text(stringResource(Res.string.settings_report_new_draft))
            }
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun SettingsFeedbackResultContent(
    state: SettingsFeedbackState,
    onIntent: (SettingsFeedbackIntent) -> Unit,
) {
    when (val result = state.result) {
        is SettingsFeedbackResult.Invalid ->
            Text(settingsReportInvalidText(result.field), color = MaterialTheme.colorScheme.error)
        is SettingsFeedbackResult.Failure -> Text(settingsReportFailureText(result.failure))
        is SettingsFeedbackResult.Attempt -> {
            SettingsReportAttemptSummary(result.attempt)
            val unresolved = result.attempt as? ComplaintReportAttempt.Unresolved
            unresolved?.pending?.let { pending ->
                SettingsReportPendingActions(pending, !state.busy, onIntent)
            }
        }
        SettingsFeedbackResult.PreparedCancelled -> Text(stringResource(Res.string.settings_report_prepared_cancelled))
        SettingsFeedbackResult.LocalResetCompleted -> Text(stringResource(Res.string.settings_report_reset_completed))
        null -> Unit
    }
}

private const val BODY_MIN_LINES = 4
private const val BODY_MAX_LINES = 8
