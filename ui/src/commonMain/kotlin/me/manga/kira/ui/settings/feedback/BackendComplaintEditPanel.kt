package me.manga.kira.ui.settings.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportField
import me.manga.kira.presentation.settings.feedback.edit.BackendComplaintEditEffect
import me.manga.kira.presentation.settings.feedback.edit.BackendComplaintEditIntent
import me.manga.kira.presentation.settings.feedback.edit.BackendComplaintEditState
import me.manga.kira.presentation.settings.feedback.edit.BackendComplaintEditViewModel
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.backend_edit_conflict
import me.manga.kira.ui.generated.resources.backend_edit_live_required
import me.manga.kira.ui.generated.resources.backend_edit_memory_only
import me.manga.kira.ui.generated.resources.backend_edit_open_recovery
import me.manga.kira.ui.generated.resources.backend_edit_receipt_expired
import me.manga.kira.ui.generated.resources.backend_edit_retry_same
import me.manga.kira.ui.generated.resources.backend_edit_unresolved
import me.manga.kira.ui.generated.resources.close
import me.manga.kira.ui.generated.resources.complaint_body
import me.manga.kira.ui.generated.resources.edit_complaint
import me.manga.kira.ui.generated.resources.settings_report_cleanup_pending
import me.manga.kira.ui.generated.resources.settings_report_recovery_title
import me.manga.kira.ui.generated.resources.settings_report_unavailable
import me.manga.kira.ui.generated.resources.subject
import me.manga.kira.ui.generated.resources.submit
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Unregistered backend-only editor. Caller owns the VM store and supplies the existing same-graph
 * Settings recovery opening; callbacks run after this editor's work drains, with no content or handles.
 */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
fun BackendComplaintEditPanel(
    viewModel: BackendComplaintEditViewModel,
    onClosed: () -> Unit,
    onOpenRecovery: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val latestOnClosed by rememberUpdatedState(onClosed)
    val latestOnRecovery by rememberUpdatedState(onOpenRecovery)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                BackendComplaintEditEffect.Closed -> latestOnClosed()
                BackendComplaintEditEffect.OpenRecovery -> latestOnRecovery()
            }
        }
    }
    if (!state.closed) BackendEditDialog(state, viewModel::submit)
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun BackendEditDialog(
    state: BackendComplaintEditState,
    onIntent: (BackendComplaintEditIntent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onIntent(BackendComplaintEditIntent.Close) },
        title = { Text(stringResource(Res.string.edit_complaint)) },
        text = { BackendEditBody(state, onIntent) },
        confirmButton = { BackendEditPrimaryAction(state, onIntent) },
        dismissButton = {
            TextButton(onClick = { onIntent(BackendComplaintEditIntent.Close) }) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun BackendEditBody(
    state: BackendComplaintEditState,
    onIntent: (BackendComplaintEditIntent) -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
    ) {
        Text(stringResource(Res.string.backend_edit_memory_only), style = MaterialTheme.typography.bodySmall)
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (state.hasDraft) {
            BackendEditFields(state, onIntent)
        } else {
            Text(stringResource(Res.string.settings_report_unavailable))
        }
        BackendEditResult(state)
        if (state.result.recoveryObserved) Text(stringResource(Res.string.settings_report_recovery_title))
        TextButton(onClick = { onIntent(BackendComplaintEditIntent.OpenRecovery) }) {
            Text(stringResource(Res.string.backend_edit_open_recovery))
        }
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun BackendEditFields(
    state: BackendComplaintEditState,
    onIntent: (BackendComplaintEditIntent) -> Unit,
) {
    state.draft.subject?.let { subject ->
        OutlinedTextField(
            value = subject,
            onValueChange = { onIntent(BackendComplaintEditIntent.ChangeSubject(it)) },
            enabled = state.editable,
            isError = state.invalidField == ComplaintReportField.SUBJECT,
            label = { Text(stringResource(Res.string.subject)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OutlinedTextField(
        value = state.draft.body,
        onValueChange = { onIntent(BackendComplaintEditIntent.ChangeBody(it)) },
        enabled = state.editable,
        isError = state.invalidField == ComplaintReportField.BODY,
        label = { Text(stringResource(Res.string.complaint_body)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = BODY_MIN_LINES,
        maxLines = BODY_MAX_LINES,
    )
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun BackendEditPrimaryAction(
    state: BackendComplaintEditState,
    onIntent: (BackendComplaintEditIntent) -> Unit,
) {
    when {
        state.editable ->
            TextButton(onClick = { onIntent(BackendComplaintEditIntent.Submit) }) {
                Text(stringResource(Res.string.submit))
            }
        state.canRetry ->
            TextButton(onClick = { onIntent(BackendComplaintEditIntent.Retry) }) {
                Text(stringResource(Res.string.backend_edit_retry_same))
            }
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun BackendEditResult(state: BackendComplaintEditState) {
    val result = state.result
    state.invalidField?.let { Text(settingsReportInvalidText(it), color = MaterialTheme.colorScheme.error) }
    if (result.conflictObserved) {
        Text(stringResource(Res.string.backend_edit_conflict), color = MaterialTheme.colorScheme.error)
    }
    val receipt = result.receipt
    if (receipt != null) {
        Text(stringResource(settingsEditReceiptResource(receipt)))
        if (result.cleanupPending) Text(stringResource(Res.string.settings_report_cleanup_pending))
    } else if (state.canRetry) {
        Text(stringResource(Res.string.backend_edit_unresolved))
    }
    result.failure?.let { Text(backendEditFailureText(it)) }
}

@Composable
private fun backendEditFailureText(failure: ComplaintReportFailure): String =
    when (failure.block) {
        ComplaintReportBlock.LIVE_REQUEST_REQUIRED -> stringResource(Res.string.backend_edit_live_required)
        ComplaintReportBlock.RECEIPT_WINDOW_EXPIRED -> stringResource(Res.string.backend_edit_receipt_expired)
        else -> settingsReportFailureText(failure)
    }

private const val BODY_MIN_LINES = 4
private const val BODY_MAX_LINES = 8
