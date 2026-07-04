// presentation/features/complaint/ui/screens/ComplaintScreen.kt
@file:OptIn(ExperimentalMaterial3Api::class)

package me.manga.yamiapk.presentation.features.complaint.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.presentation.features.complaint.data.SampleData
import me.manga.yamiapk.presentation.features.complaint.data.customTopComplaints
import me.manga.yamiapk.presentation.features.complaint.data.getCustomTopComplaints
import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintStatus
import me.manga.yamiapk.presentation.features.complaint.ui.components.ComplaintActionDialog
import me.manga.yamiapk.presentation.features.complaint.ui.components.ComplaintCard
import me.manga.yamiapk.presentation.features.complaint.ui.components.EmptyState
import me.manga.yamiapk.presentation.features.complaint.ui.components.ErrorState
import me.manga.yamiapk.presentation.features.complaint.ui.components.LoadingState
import me.manga.yamiapk.theme.YamiMangaTheme

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun ComplaintScreenSuccessPreview() {
    YamiMangaTheme(darkTheme = true) {
        ComplaintScreen(
            complaintsState = State.Success(SampleData.complaints),
            onRetry = {},
            onBackClick = {},

            onHelp = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun ComplaintScreenLoadingPreview() {
    YamiMangaTheme(darkTheme = true) {
        ComplaintScreen(
            complaintsState = State.Loading,
            onRetry = {},
            onBackClick = {},

            onHelp = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun ComplaintScreenErrorPreview() {
    YamiMangaTheme(darkTheme = true) {
        ComplaintScreen(
            complaintsState = State.Error(0, "Cannot reach server—please check your internet connection."),
            onRetry = {},
            onBackClick = {},

            onHelp = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun ComplaintScreenEmptyPreview() {
    YamiMangaTheme(darkTheme = false) {
        ComplaintScreen(
            complaintsState = State.Success(emptyList()),
            onRetry = {},
            onBackClick = {},
            onHelp = {}
        )
    }
}

// Updated ComplaintScreen.kt - Key changes highlighted
@Composable
fun ComplaintScreen(
    complaintsState: State<List<Complaint>>,
    onRetry: () -> Unit,
    onHelp: () -> Unit,
    onBackClick: () -> Unit,
    onReplyComplaint: (Complaint,  Complaint) -> Unit = { _, _ -> },
    onEditComplaint: (Complaint, String) -> Unit = { _, _ -> },
    onDeleteComplaint: (Complaint) -> Unit = {},
    onShowMessage: (String) -> Unit = {}
) {
    // Dialog state
    var selectedComplaint by remember { mutableStateOf<Complaint?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }

    // Prepare & filter your data up‐front
    val context = LocalContext.current
    val allComplaints = remember(complaintsState) {
        getCustomTopComplaints(context) + (complaintsState as? State.Success)?.data.orEmpty()
    }
    var searchQuery by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf<ComplaintStatus?>(null) }
    val filtered = allComplaints.filter { complaint ->
        val matchesSearch = complaint.body.contains(searchQuery, true) ||
                complaint.subject.contains(searchQuery, true) ||
                complaint.id.contains(searchQuery, true)
        val matchesStatus = selectedStatus == null || complaint.status == selectedStatus
        matchesSearch && matchesStatus
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        // 1) App bar
        item {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(R.string.feedback_manager_title), fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 2) Loading / Error / Empty states
        when (complaintsState) {
            is State.Loading -> {
                item {
                    LoadingState(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 64.dp),
                        message = stringResource(R.string.loading_feedback)
                    )
                }
                return@LazyColumn
            }
            is State.Error -> {
                item {
                    ErrorState(
                        error = complaintsState,
                        onRetry = onRetry,
                        onHelp = if (complaintsState.code == 403) onHelp else null,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 64.dp)
                    )
                }
                return@LazyColumn
            }
            is State.Success -> {
                if (allComplaints.isEmpty()) {
                    item {
                        EmptyState(modifier = Modifier.fillParentMaxSize())
                    }
                    return@LazyColumn
                }
            }
        }

        // 3) Search & Filter header
        item {
            SearchAndFilterSection(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                selectedStatus = selectedStatus,
                onStatusSelected = { selectedStatus = it },
                resultsCount = filtered.size
            )
        }

        // 4) No-results fallback
        if (filtered.isEmpty() && (searchQuery.isNotEmpty() || selectedStatus != null)) {
            item {
                EmptyState(
                    title = stringResource(R.string.no_results_found),
                    message = stringResource(R.string.try_different_search),
                    icon = Icons.Default.SearchOff,
                    modifier = Modifier
                        .fillParentMaxSize()
                        .padding(top = 32.dp)
                )
            }
        } else {
            // 5) The lazy list of complaint cards
            items(filtered) { complaint ->
                ComplaintCard(
                    complaint = complaint,
                    onClick = {
                        selectedComplaint = complaint
                        showActionDialog = true
                    }
                )
            }
        }
    }

    // Show enhanced action dialog when needed
    if (showActionDialog && selectedComplaint != null) {
        ComplaintActionDialog(
            complaint = selectedComplaint!!,
            onDismiss = {
                showActionDialog = false
                selectedComplaint = null
            },
            onReply = { complaint, replyText ->
                onReplyComplaint(complaint, replyText)
                onShowMessage("Reply sent successfully to complaint ${complaint.id}")
                showActionDialog = false
                selectedComplaint = null
            },
            onEdit = { complaint, editedText ->
                onEditComplaint(complaint, editedText)
                onShowMessage("Complaint ${complaint.id} updated successfully")
                showActionDialog = false
                selectedComplaint = null
            },
            onDelete = { complaint ->
                onDeleteComplaint(complaint)
                onShowMessage("Complaint ${complaint.id} deleted successfully")
                showActionDialog = false
                selectedComplaint = null
            }
        )
    }
}

@Composable
private fun SearchAndFilterSection(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedStatus: ComplaintStatus?,
    onStatusSelected: (ComplaintStatus?) -> Unit,
    resultsCount: Int
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_feedbacks_hint)) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Status Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    onClick = { onStatusSelected(null) },
                    label = { Text(stringResource(R.string.filter_all)) },
                    selected = selectedStatus == null
                )
            }
            items(ComplaintStatus.entries.toTypedArray()) { status ->
                FilterChip(
                    onClick = {
                        onStatusSelected(if (selectedStatus == status) null else status)
                    },
                    label = { Text(status.getDisplayName(context = context)) },
                    selected = selectedStatus == status
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Results count
        Text(
            text = stringResource(R.string.feedbacks_found_count, resultsCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}