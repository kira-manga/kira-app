@file:Suppress("ktlint:standard:function-naming", "FunctionNaming")

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportField
import me.manga.kira.domain.model.feedback.ComplaintReportPhase
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackDeletionState
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyActivity
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyIntent
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyResult
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.backend_reply_live_required
import me.manga.kira.ui.generated.resources.backend_reply_memory_only
import me.manga.kira.ui.generated.resources.backend_reply_open_recovery
import me.manga.kira.ui.generated.resources.backend_reply_receipt_expired
import me.manga.kira.ui.generated.resources.backend_reply_retry_same
import me.manga.kira.ui.generated.resources.backend_reply_target_unavailable
import me.manga.kira.ui.generated.resources.backend_reply_unresolved
import me.manga.kira.ui.generated.resources.close
import me.manga.kira.ui.generated.resources.np_complaint_action_reply_sent
import me.manga.kira.ui.generated.resources.reply_placeholder
import me.manga.kira.ui.generated.resources.reply_to_complaint
import me.manga.kira.ui.generated.resources.request_failed
import me.manga.kira.ui.generated.resources.request_feedback_missing_installation
import me.manga.kira.ui.generated.resources.settings_installation_deletion_checking
import me.manga.kira.ui.generated.resources.settings_installation_deletion_cleanup
import me.manga.kira.ui.generated.resources.settings_installation_deletion_pending
import me.manga.kira.ui.generated.resources.settings_report_cleanup_pending
import me.manga.kira.ui.generated.resources.settings_report_may_have_sent
import me.manga.kira.ui.generated.resources.settings_report_prepared
import me.manga.kira.ui.generated.resources.settings_report_recovery_empty
import me.manga.kira.ui.generated.resources.settings_report_recovery_title
import me.manga.kira.ui.generated.resources.settings_report_refresh
import me.manga.kira.ui.generated.resources.settings_report_unavailable
import me.manga.kira.ui.generated.resources.submit
import me.manga.kira.ui.generated.resources.your_reply
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Dumb, unselected ordinary-reply panel. A candidate host owns the VM/effect collector and must
 * wait for its drained close/handoff effect before opening existing Settings recovery on that owner.
 * No route, saved draft, graph selection, enrollment or pending-mutation logic lives here.
 */
@Composable
fun BackendComplaintReplyPanel(
    state: ComplaintReplyState,
    onIntent: (ComplaintReplyIntent) -> Unit,
) {
    if (state.isClosed) return
    AlertDialog(
        onDismissRequest = { onIntent(ComplaintReplyIntent.Close) },
        title = { Text(stringResource(Res.string.reply_to_complaint)) },
        text = { ReplyPanelBody(state, onIntent) },
        confirmButton = { ReplyPrimaryAction(state, onIntent) },
        dismissButton = {
            TextButton(onClick = { onIntent(ComplaintReplyIntent.Close) }) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}

@Composable
private fun ReplyPanelBody(state: ComplaintReplyState, onIntent: (ComplaintReplyIntent) -> Unit) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.md),
    ) {
        Text(stringResource(Res.string.backend_reply_memory_only), style = MaterialTheme.typography.bodySmall)
        if (state.context.targetAvailable) {
            replyInstallationText(state.context.installation)?.let { Text(it) }
            ReplyBodyField(state, onIntent)
        } else {
            Text(stringResource(Res.string.backend_reply_target_unavailable))
        }
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ReplyResultContent(state.result)
        ReplyRecoveryContent(state, onIntent)
        TextButton(
            onClick = { onIntent(ComplaintReplyIntent.OpenRecovery) },
            enabled = state.canOpenRecovery,
        ) {
            Text(stringResource(Res.string.backend_reply_open_recovery))
        }
    }
}

@Composable
private fun ReplyBodyField(state: ComplaintReplyState, onIntent: (ComplaintReplyIntent) -> Unit) {
    val invalid =
        (state.result as? ComplaintReplyResult.Invalid)
            ?.takeIf { it.field == ComplaintReportField.BODY }
            ?.let { settingsReportInvalidText(it.field) }
    OutlinedTextField(
        value = state.body,
        onValueChange = { onIntent(ComplaintReplyIntent.ChangeBody(it)) },
        enabled = state.editable,
        label = { Text(stringResource(Res.string.your_reply)) },
        placeholder = { Text(stringResource(Res.string.reply_placeholder)) },
        isError = invalid != null,
        modifier = Modifier.fillMaxWidth().semantics { if (invalid != null) error(invalid) },
        minLines = REPLY_BODY_MIN_LINES,
        maxLines = REPLY_BODY_MAX_LINES,
    )
}

@Composable
private fun replyInstallationText(installation: SettingsFeedbackDeletionState): String? =
    when (installation) {
        SettingsFeedbackDeletionState.Active -> null
        SettingsFeedbackDeletionState.Checking -> stringResource(Res.string.settings_installation_deletion_checking)
        SettingsFeedbackDeletionState.Missing -> stringResource(Res.string.request_feedback_missing_installation)
        SettingsFeedbackDeletionState.LocalCleanupRequired ->
            stringResource(Res.string.settings_installation_deletion_cleanup)
        is SettingsFeedbackDeletionState.Pending -> stringResource(Res.string.settings_installation_deletion_pending)
        SettingsFeedbackDeletionState.Uncertain,
        SettingsFeedbackDeletionState.Completed,
        -> stringResource(Res.string.settings_report_unavailable)
    }

@Composable
private fun ReplyPrimaryAction(state: ComplaintReplyState, onIntent: (ComplaintReplyIntent) -> Unit) {
    if (state.activity == ComplaintReplyActivity.TERMINAL) return
    if (state.result is ComplaintReplyResult.Attempt) {
        TextButton(onClick = { onIntent(ComplaintReplyIntent.Retry) }, enabled = state.canRetry) {
            Text(stringResource(Res.string.backend_reply_retry_same))
        }
    } else {
        TextButton(onClick = { onIntent(ComplaintReplyIntent.Submit) }, enabled = state.canSubmit) {
            Text(stringResource(Res.string.submit))
        }
    }
}

@Composable
private fun ReplyResultContent(result: ComplaintReplyResult?) {
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        when (result) {
            is ComplaintReplyResult.Invalid ->
                Text(settingsReportInvalidText(result.field), color = MaterialTheme.colorScheme.error)
            is ComplaintReplyResult.Failure -> Text(replyFailureText(result.failure))
            is ComplaintReplyResult.Attempt -> ReplyAttemptSummary(result.attempt)
            null -> Unit
        }
    }
}

/** A known receipt stays visible beside incomplete cleanup; it is not a current detail row. */
@Composable
private fun ReplyAttemptSummary(attempt: ComplaintReportAttempt) {
    val application =
        when (attempt) {
            is ComplaintReportAttempt.Completed -> attempt.application
            is ComplaintReportAttempt.Unresolved -> attempt.knownApplication
        }
    when (application) {
        null -> Text(stringResource(Res.string.backend_reply_unresolved))
        is ComplaintReportApplication.Applied -> Text(stringResource(Res.string.np_complaint_action_reply_sent))
        is ComplaintReportApplication.Rejected -> Text(stringResource(Res.string.request_failed))
        else -> SettingsReportAttemptSummary(ComplaintReportAttempt.Completed(application))
    }
    if (attempt is ComplaintReportAttempt.Unresolved) {
        if (application != null) Text(stringResource(Res.string.settings_report_cleanup_pending))
        Text(replyFailureText(attempt.failure))
        attempt.pending?.let { ReplyPendingPhase(it.phase) }
    }
}

@Composable
private fun replyFailureText(failure: ComplaintReportFailure): String =
    when (failure.block) {
        ComplaintReportBlock.RECEIPT_WINDOW_EXPIRED -> stringResource(Res.string.backend_reply_receipt_expired)
        ComplaintReportBlock.LIVE_REQUEST_REQUIRED -> stringResource(Res.string.backend_reply_live_required)
        else -> settingsReportFailureText(failure)
    }

/** Shared recovery may describe report/reply, edit or single-delete; never label it all as this reply. */
@Composable
private fun ReplyRecoveryContent(state: ComplaintReplyState, onIntent: (ComplaintReplyIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        HorizontalDivider()
        Text(stringResource(Res.string.settings_report_recovery_title), style = MaterialTheme.typography.titleSmall)
        TextButton(
            onClick = { onIntent(ComplaintReplyIntent.RefreshRecovery) },
            enabled = state.canRefreshRecovery,
        ) {
            Text(stringResource(Res.string.settings_report_refresh))
        }
        val recovery = state.recovery
        if (recovery != null) {
            val entries = recovery.entries()
            if (entries.isEmpty()) Text(stringResource(Res.string.settings_report_recovery_empty))
            recovery.stopped?.let { Text(settingsReportFailureText(it)) }
            entries.forEach { observation ->
                HorizontalDivider()
                SettingsReportAttemptSummary(observation.attempt)
                if (observation.attempt is ComplaintReportAttempt.Unresolved) ReplyPendingPhase(observation.pending.phase)
            }
        }
    }
}

@Composable
private fun ReplyPendingPhase(phase: ComplaintReportPhase) {
    Text(
        stringResource(
            when (phase) {
                ComplaintReportPhase.PREPARED -> Res.string.settings_report_prepared
                ComplaintReportPhase.MAY_HAVE_DISPATCHED -> Res.string.settings_report_may_have_sent
            },
        ),
    )
}

private const val REPLY_BODY_MIN_LINES = 4
private const val REPLY_BODY_MAX_LINES = 8
