package me.manga.kira.ui.settings.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.presentation.settings.feedback.delete.BackendComplaintDeleteEffect
import me.manga.kira.presentation.settings.feedback.delete.BackendComplaintDeleteIntent
import me.manga.kira.presentation.settings.feedback.delete.BackendComplaintDeleteState
import me.manga.kira.presentation.settings.feedback.delete.BackendComplaintDeleteViewModel
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.backend_delete_conflict
import me.manga.kira.ui.generated.resources.backend_delete_live_required
import me.manga.kira.ui.generated.resources.backend_delete_open_recovery
import me.manga.kira.ui.generated.resources.backend_delete_receipt_expired
import me.manga.kira.ui.generated.resources.backend_delete_retry_same
import me.manga.kira.ui.generated.resources.backend_delete_unavailable
import me.manga.kira.ui.generated.resources.backend_delete_unresolved
import me.manga.kira.ui.generated.resources.backend_delete_warning
import me.manga.kira.ui.generated.resources.close
import me.manga.kira.ui.generated.resources.delete
import me.manga.kira.ui.generated.resources.delete_complaint
import me.manga.kira.ui.generated.resources.settings_report_cleanup_pending
import me.manga.kira.ui.generated.resources.settings_report_recovery_title
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** Caller owns the VM store. Recovery callbacks receive no content, target or live-operation handle. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
fun BackendComplaintDeletePanel(
    viewModel: BackendComplaintDeleteViewModel,
    onClosed: () -> Unit,
    onOpenRecovery: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val latestOnClosed by rememberUpdatedState(onClosed)
    val latestOnRecovery by rememberUpdatedState(onOpenRecovery)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                BackendComplaintDeleteEffect.Closed -> latestOnClosed()
                BackendComplaintDeleteEffect.OpenRecovery -> latestOnRecovery()
            }
        }
    }
    if (!state.closed) BackendDeleteDialog(state, viewModel::submit)
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun BackendDeleteDialog(
    state: BackendComplaintDeleteState,
    onIntent: (BackendComplaintDeleteIntent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onIntent(BackendComplaintDeleteIntent.Close) },
        title = { Text(stringResource(Res.string.delete_complaint)) },
        text = { BackendDeleteBody(state, onIntent) },
        confirmButton = {
            when {
                state.canConfirm ->
                    TextButton(onClick = { onIntent(BackendComplaintDeleteIntent.Confirm) }) {
                        Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                state.canRetry ->
                    TextButton(onClick = { onIntent(BackendComplaintDeleteIntent.Retry) }) {
                        Text(stringResource(Res.string.backend_delete_retry_same))
                    }
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(BackendComplaintDeleteIntent.Close) }) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun BackendDeleteBody(
    state: BackendComplaintDeleteState,
    onIntent: (BackendComplaintDeleteIntent) -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
    ) {
        Text(stringResource(Res.string.backend_delete_warning))
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (state.hasTarget) {
            state.preview.subject?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(state.preview.body)
        } else {
            Text(stringResource(Res.string.backend_delete_unavailable))
        }
        BackendDeleteResult(state)
        if (state.result.recoveryObserved) Text(stringResource(Res.string.settings_report_recovery_title))
        TextButton(onClick = { onIntent(BackendComplaintDeleteIntent.OpenRecovery) }) {
            Text(stringResource(Res.string.backend_delete_open_recovery))
        }
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun BackendDeleteResult(state: BackendComplaintDeleteState) {
    val result = state.result
    if (result.conflictObserved) {
        Text(stringResource(Res.string.backend_delete_conflict), color = MaterialTheme.colorScheme.error)
    }
    val receipt = result.receipt
    if (receipt != null) {
        Text(stringResource(settingsOwnerDeleteReceiptResource(receipt)))
        if (result.cleanupPending) Text(stringResource(Res.string.settings_report_cleanup_pending))
    } else if (state.canRetry) {
        Text(stringResource(Res.string.backend_delete_unresolved))
    }
    result.failure?.let { failure ->
        val resource =
            when (failure.block) {
                ComplaintReportBlock.LIVE_REQUEST_REQUIRED -> Res.string.backend_delete_live_required
                ComplaintReportBlock.RECEIPT_WINDOW_EXPIRED -> Res.string.backend_delete_receipt_expired
                else -> Res.string.backend_delete_unavailable
            }
        Text(stringResource(resource))
    }
}
