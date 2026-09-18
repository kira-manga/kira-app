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
import me.manga.kira.ui.generated.resources.dropdown_button_refresh
import me.manga.kira.ui.generated.resources.error_network_not_found
import me.manga.kira.ui.generated.resources.error_occurred
import me.manga.kira.ui.generated.resources.feedback_manager_title
import me.manga.kira.ui.generated.resources.np_user_loading_feedback
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.generated.resources.unknown
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** Optional candidate detail only. No saved content, mutation buttons or ownership inference. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
fun BackendComplaintDetail(viewModel: ComplaintDetailViewModel) {
    val state by viewModel.state.collectAsState()
    BackendComplaintDetailContent(state, viewModel::submit)
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun BackendComplaintDetailContent(
    state: ComplaintDetailState,
    onIntent: (ComplaintDetailIntent) -> Unit,
) {
    if (state.selectedId == null) return
    AlertDialog(
        onDismissRequest = { onIntent(ComplaintDetailIntent.Close) },
        title = { Text(stringResource(Res.string.feedback_manager_title)) },
        text = { BackendComplaintDetailRead(state) },
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
private fun BackendComplaintDetailRead(state: ComplaintDetailState) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
    ) {
        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(Res.string.np_user_loading_feedback))
        }
        if (state.error != null) {
            Text(stringResource(Res.string.error_occurred), color = MaterialTheme.colorScheme.error)
        }
        when (val detail = state.detail) {
            is ComplaintDetail.Owned -> BackendHistoryRow(detail.item)
            // No notice-key catalog has been admitted. Never use its raw key as text or action authority.
            is ComplaintDetail.Notice -> Text(stringResource(Res.string.unknown))
            ComplaintDetail.Unavailable -> Text(stringResource(Res.string.error_network_not_found))
            null -> Unit
        }
    }
}
