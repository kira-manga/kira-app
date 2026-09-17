package me.manga.kira.ui.complaint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintHistoryType
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
import me.manga.kira.presentation.complaint.ComplaintIntent
import me.manga.kira.presentation.complaint.ComplaintState
import me.manga.kira.ui.components.KiraEmptyState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.error_occurred
import me.manga.kira.ui.generated.resources.no_results_found
import me.manga.kira.ui.generated.resources.np_no_feedback_message
import me.manga.kira.ui.generated.resources.np_no_feedback_title
import me.manga.kira.ui.generated.resources.np_try_different_search
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.generated.resources.unknown
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** No clickable row/action-dialog or conversion to legacy ComplaintSummary exists in this branch. */
@Composable
internal fun BackendComplaintHistory(state: ComplaintState, onIntent: (ComplaintIntent) -> Unit) {
    val history = state.history as? ComplaintHistory.Backend ?: return
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxSize()) {
        ComplaintHistoryRefreshStatus(state, onIntent)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item(key = "search") {
                SearchAndFilterSection(state.searchQuery, state.selectedStatus, state.backendItems.size, onIntent)
            }
            items(history.notices, key = { "notice:" + it.id }) {
                // No live notice-key catalog has been accepted. Unknown keys never inject server text/actions.
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.unknown), modifier = Modifier.padding(spacing.md))
                }
            }
            items(state.backendItems, key = { "owner:" + it.id }) { row -> BackendHistoryRow(row) }
            if (history.notices.isEmpty() && history.items.isEmpty()) {
                item(key = "empty") {
                    KiraEmptyState(
                        title = stringResource(Res.string.np_no_feedback_title),
                        message = stringResource(Res.string.np_no_feedback_message),
                    )
                }
            } else if (state.backendItems.isEmpty() && history.items.isNotEmpty()) {
                item(key = "no_matches") {
                    KiraEmptyState(
                        title = stringResource(Res.string.no_results_found),
                        message = stringResource(Res.string.np_try_different_search),
                    )
                }
            }
        }
    }
}

@Composable
private fun BackendHistoryRow(row: ComplaintOwnerRow) {
    val spacing = LocalSpacing.current
    val title = when (row) {
        is ComplaintOwnerRow.Report -> row.subject
        is ComplaintOwnerRow.Reply -> row.subject
        is ComplaintOwnerRow.NoticeReply, is UnknownComplaintItem -> stringResource(Res.string.unknown)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (row is ComplaintOwnerRow.Content) {
                when (val status = row.fields.status) {
                    is ComplaintHistoryStatus.Known -> ComplaintStatusChip(status.value)
                    ComplaintHistoryStatus.Unrecognized -> Text(stringResource(Res.string.unknown))
                }
                if (row.type is ComplaintHistoryType.Unrecognized) Text(stringResource(Res.string.unknown))
                Text(row.fields.body, style = MaterialTheme.typography.bodyMedium)
                row.fields.closureReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Loading/error is supplementary whenever a genuine prior snapshot (including empty) exists. */
@Composable
internal fun ComplaintHistoryRefreshStatus(state: ComplaintState, onIntent: (ComplaintIntent) -> Unit) {
    if (!state.isLoading && state.error == null) return
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (state.isLoading) CircularProgressIndicator(modifier = Modifier.size(spacing.lg))
        if (state.error != null) {
            Text(stringResource(Res.string.error_occurred), modifier = Modifier.weight(1f))
            TextButton(onClick = { onIntent(ComplaintIntent.OnRetry) }) { Text(stringResource(Res.string.retry)) }
        }
    }
}
