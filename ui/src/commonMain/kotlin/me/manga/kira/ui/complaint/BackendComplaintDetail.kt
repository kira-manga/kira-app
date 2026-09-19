package me.manga.kira.ui.complaint

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.presentation.complaint.ComplaintDetailIntent
import me.manga.kira.presentation.complaint.ComplaintDetailState
import me.manga.kira.presentation.complaint.ComplaintDetailViewModel
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.close
import me.manga.kira.ui.generated.resources.delete_complaint
import me.manga.kira.ui.generated.resources.dropdown_button_refresh
import me.manga.kira.ui.generated.resources.edit_complaint
import me.manga.kira.ui.generated.resources.error_network_not_found
import me.manga.kira.ui.generated.resources.error_occurred
import me.manga.kira.ui.generated.resources.feedback_manager_title
import me.manga.kira.ui.generated.resources.np_user_loading_feedback
import me.manga.kira.ui.generated.resources.reply_to_complaint
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** Optional candidate detail. Callbacks are host-checked opening requests, never mutation authority. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
fun BackendComplaintDetail(
    viewModel: ComplaintDetailViewModel,
    onReply: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    BackendComplaintDetailContent(state, viewModel::submit, onReply, onEdit, onDelete)
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun BackendComplaintDetailContent(
    state: ComplaintDetailState,
    onIntent: (ComplaintDetailIntent) -> Unit,
    onReply: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    if (state.selectedId == null) return
    AlertDialog(
        onDismissRequest = { onIntent(ComplaintDetailIntent.Close) },
        title = { Text(stringResource(Res.string.feedback_manager_title)) },
        text = {
            BackendComplaintDetailRead(state) { BackendComplaintDetailActions(onReply, onEdit, onDelete) }
        },
        confirmButton = {
            TextButton(onClick = { onIntent(ComplaintDetailIntent.Close) }) {
                Text(stringResource(Res.string.close))
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(ComplaintDetailIntent.Retry) }, enabled = !state.isLoading) {
                Text(stringResource(if (state.error != null) Res.string.retry else Res.string.dropdown_button_refresh))
            }
        },
    )
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun BackendComplaintDetailRead(
    state: ComplaintDetailState,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        BackendComplaintDetailObservation(state)
        actions()
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun BackendComplaintDetailObservation(state: ComplaintDetailState) {
    if (state.isLoading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(stringResource(Res.string.np_user_loading_feedback))
    }
    if (state.error != null) {
        Text(stringResource(Res.string.error_occurred), color = MaterialTheme.colorScheme.error)
    }
    when (val detail = state.detail) {
        is ComplaintDetail.Owned -> BackendHistoryRow(detail.item)
        // Catalogue lookup renders localized copy only; it does not grant navigation or write authority.
        is ComplaintDetail.Notice -> BackendNoticeText(detail.item.noticeKey)
        ComplaintDetail.Unavailable -> Text(stringResource(Res.string.error_network_not_found))
        null -> Unit
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
private fun BackendComplaintDetailActions(
    onReply: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    if (onReply != null) {
        TextButton(onClick = onReply) { Text(stringResource(Res.string.reply_to_complaint)) }
    }
    if (onEdit != null) {
        TextButton(onClick = onEdit) { Text(stringResource(Res.string.edit_complaint)) }
    }
    if (onDelete != null) {
        TextButton(onClick = onDelete) {
            Text(stringResource(Res.string.delete_complaint), color = MaterialTheme.colorScheme.error)
        }
    }
}
