// presentation/features/complaint/ui/screens/AdminComplaintScreen.kt
@file:OptIn(ExperimentalMaterial3Api::class)

package me.manga.yamiapk.admin.complaint

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.presentation.features.complaint.data.SampleData
import me.manga.yamiapk.presentation.features.complaint.model.*
import me.manga.yamiapk.presentation.features.complaint.ui.components.*
import me.manga.yamiapk.presentation.features.complaint.utils.formatTimestamp
import me.manga.yamiapk.theme.YamiMangaTheme

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun AdminComplaintScreenPreview() {
    YamiMangaTheme(darkTheme = true) {
        AdminComplaintScreen(
            complaintsState = State.Success(SampleData.complaints),
            onRetry = {},
            onBackClick = {},
            onUpdateComplaintStatus = { _, _ -> },
            onDeleteComplaint = { _ -> },
            onUpdateComplaint = { _ -> },
            onAddClosureReason = { _, _ -> },
            onShowMessage = { _ -> }
        )
    }
}

@Composable
fun AdminComplaintScreen(
    complaintsState: State<List<Complaint>>,
    onRetry: () -> Unit,
    onBackClick: () -> Unit,
    onUpdateComplaintStatus: (Complaint, ComplaintStatus) -> Unit,
    onDeleteComplaint: (Complaint) -> Unit,
    onUpdateComplaint: (Complaint) -> Unit,
    onAddClosureReason: (Complaint, String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    var selectedComplaint by remember { mutableStateOf<Complaint?>(null) }
    var showStatusDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showClosureDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Search and filter state
    var searchQuery by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf<ComplaintStatus?>(null) }
    var selectedType by remember { mutableStateOf<ComplaintType?>(null) }
    var selectedAppVersion by remember { mutableStateOf<String?>(null) } // New app version filter
    var sortBy by remember { mutableStateOf(SortOption.DATE_DESC) }

    // Statistics state
    var showStats by remember { mutableStateOf(true) }

    val complaints = (complaintsState as? State.Success)?.data ?: emptyList()

    // Get available app versions for filtering
    val availableAppVersions = remember(complaints) {
        complaints
            .mapNotNull { it.metadata?.get("appVersion")?.toString() }
            .distinct()
            .sortedWith { a, b ->
                // Try to sort versions numerically if possible
                val aVersion = a.split(".").mapNotNull { it.toIntOrNull() }
                val bVersion = b.split(".").mapNotNull { it.toIntOrNull() }

                if (aVersion.isNotEmpty() && bVersion.isNotEmpty()) {
                    // Compare version numbers
                    for (i in 0 until maxOf(aVersion.size, bVersion.size)) {
                        val aPart = aVersion.getOrNull(i) ?: 0
                        val bPart = bVersion.getOrNull(i) ?: 0
                        val comparison = bPart.compareTo(aPart) // Descending order (newest first)
                        if (comparison != 0) return@sortedWith comparison
                    }
                    0
                } else {
                    b.compareTo(a) // Fallback to string comparison (descending)
                }
            }
    }

    // Filter and sort complaints
    val filteredComplaints = remember(
        complaints,
        searchQuery,
        selectedStatus,
        selectedType,
        selectedAppVersion,
        sortBy
    ) {
        complaints
            .filter { complaint ->
                val matchesSearch = searchQuery.isBlank() ||
                        complaint.body.contains(searchQuery, true) ||
                        complaint.subject.contains(searchQuery, true) ||
                        complaint.id.contains(searchQuery, true) ||
                        complaint.userId.contains(searchQuery, true)

                val matchesStatus = selectedStatus == null || complaint.status == selectedStatus
                val matchesType = selectedType == null || complaint.type == selectedType

                // New app version filtering logic
                val matchesAppVersion = selectedAppVersion == null ||
                        complaint.metadata?.get("appVersion")?.toString() == selectedAppVersion

                matchesSearch && matchesStatus && matchesType && matchesAppVersion
            }
            .let { filtered ->
                when (sortBy) {
                    SortOption.DATE_ASC -> filtered.sortedBy { it.createdAt }
                    SortOption.DATE_DESC -> filtered.sortedByDescending { it.createdAt }
                    SortOption.STATUS -> filtered.sortedBy { it.status.ordinal }
                    SortOption.TYPE -> filtered.sortedBy { it.type.ordinal }
                    SortOption.USER_ID -> filtered.sortedBy { it.userId }
                    SortOption.APP_VERSION -> filtered.sortedBy {
                        it.metadata?.get("appVersion")?.toString() ?: ""
                    }
                    SortOption.APP_VERSION_DESC -> filtered.sortedByDescending {
                        it.metadata?.get("appVersion")?.toString() ?: ""
                    }
                }
            }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // App Bar
        item {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.admin_complaint_management),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showStats = !showStats }) {
                        Icon(
                            if (showStats) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle Statistics"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Handle different states
        when (complaintsState) {
            is State.Loading -> {
                item {
                    LoadingState(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 64.dp),
                        message = stringResource(R.string.loading_complaints)
                    )
                }
                return@LazyColumn
            }

            is State.Error -> {
                item {
                    ErrorState(
                        error = complaintsState,
                        onRetry = onRetry,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 64.dp)
                    )
                }
                return@LazyColumn
            }

            is State.Success -> {
                if (complaints.isEmpty()) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.no_complaints_found),
                            message = stringResource(R.string.no_complaints_message),
                            modifier = Modifier.fillParentMaxSize()
                        )
                    }
                    return@LazyColumn
                }
            }
        }

        // Statistics Card
        if (showStats) {
            item {
                AdminStatisticsCard(
                    complaints = complaints,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // Search and Filter Section
        item {
            AdminSearchAndFilterSection(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                selectedStatus = selectedStatus,
                onStatusSelected = { selectedStatus = it },
                selectedType = selectedType,
                onTypeSelected = { selectedType = it },
                selectedAppVersion = selectedAppVersion, // Pass selected app version
                onAppVersionSelected = { selectedAppVersion = it }, // Pass callback
                availableAppVersions = availableAppVersions, // Pass available versions
                sortBy = sortBy,
                onSortChanged = { sortBy = it },
                resultsCount = filteredComplaints.size
            )
        }

        // No Results State
        if (filteredComplaints.isEmpty() && complaints.isNotEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.no_results_found),
                    message = stringResource(R.string.try_different_filters),
                    icon = Icons.Default.FilterAlt,
                    modifier = Modifier
                        .fillParentMaxSize()
                        .padding(top = 32.dp)
                )
            }
        } else {
            // Complaint Cards
            items(filteredComplaints) { complaint ->
                AdminComplaintCard(
                    complaint = complaint,
                    onStatusClick = {
                        selectedComplaint = complaint
                        showStatusDialog = true
                    },
                    onEditClick = {
                        selectedComplaint = complaint
                        showEditDialog = true
                    },
                    onClosureReasonClick = {
                        selectedComplaint = complaint
                        showClosureDialog = true
                    },
                    onDeleteClick = {
                        selectedComplaint = complaint
                        showDeleteDialog = true
                    }
                )
            }
        }
    }

    // Status Change Dialog
    if (showStatusDialog && selectedComplaint != null) {
        StatusChangeDialog(
            complaint = selectedComplaint!!,
            onDismiss = {
                showStatusDialog = false
                selectedComplaint = null
            },
            onStatusChanged = { complaint, newStatus ->
                onUpdateComplaintStatus(complaint, newStatus)
                onShowMessage("Status updated to ${newStatus.name}")
                showStatusDialog = false
                selectedComplaint = null
            }
        )
    }

    // Edit Dialog
    if (showEditDialog && selectedComplaint != null) {
        EditComplaintDialog(
            complaint = selectedComplaint!!,
            onDismiss = {
                showEditDialog = false
                selectedComplaint = null
            },
            onComplaintUpdated = { updatedComplaint ->
                onUpdateComplaint(updatedComplaint)
                onShowMessage("Complaint updated successfully")
                showEditDialog = false
                selectedComplaint = null
            }
        )
    }

    // Closure Reason Dialog
    if (showClosureDialog && selectedComplaint != null) {
        ClosureReasonDialog(
            complaint = selectedComplaint!!,
            onDismiss = {
                showClosureDialog = false
                selectedComplaint = null
            },
            onReasonAdded = { complaint, reason ->
                onAddClosureReason(complaint, reason)
                onShowMessage("Closure reason added")
                showClosureDialog = false
                selectedComplaint = null
            }
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteDialog && selectedComplaint != null) {
        DeleteConfirmationDialog(
            complaint = selectedComplaint!!,
            onDismiss = {
                showDeleteDialog = false
                selectedComplaint = null
            },
            onConfirmDelete = { complaint ->
                onDeleteComplaint(complaint)
                onShowMessage("Complaint deleted successfully")
                showDeleteDialog = false
                selectedComplaint = null
            }
        )
    }
}

@Composable
private fun AdminSearchAndFilterSection(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedStatus: ComplaintStatus?,
    onStatusSelected: (ComplaintStatus?) -> Unit,
    selectedType: ComplaintType?,
    onTypeSelected: (ComplaintType?) -> Unit,
    selectedAppVersion: String?, // New parameter
    onAppVersionSelected: (String?) -> Unit, // New parameter
    availableAppVersions: List<String>, // New parameter
    sortBy: SortOption,
    onSortChanged: (SortOption) -> Unit,
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
            placeholder = { Text(stringResource(R.string.search_complaints_admin)) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = stringResource(R.string.clear)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Status Filter
        Text(
            text = stringResource(R.string.filter_by_status),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            item {
                FilterChip(
                    onClick = { onStatusSelected(null) },
                    label = { Text(stringResource(R.string.all_statuses)) },
                    selected = selectedStatus == null
                )
            }
            items(ComplaintStatus.entries.toTypedArray()) { status ->
                FilterChip(
                    onClick = {
                        onStatusSelected(if (selectedStatus == status) null else status)
                    },
                    label = { Text(status.getDisplayName(context)) },
                    selected = selectedStatus == status
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Type Filter
        Text(
            text = stringResource(R.string.filter_by_type),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            item {
                FilterChip(
                    onClick = { onTypeSelected(null) },
                    label = { Text(stringResource(R.string.all_types)) },
                    selected = selectedType == null
                )
            }
            items(ComplaintType.entries.toTypedArray()) { type ->
                FilterChip(
                    onClick = {
                        onTypeSelected(if (selectedType == type) null else type)
                    },
                    label = { Text(type.getDisplayName(context)) },
                    selected = selectedType == type
                )
            }
        }

        // New App Version Filter Section
        if (availableAppVersions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Filter by App Version",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                item {
                    FilterChip(
                        onClick = { onAppVersionSelected(null) },
                        label = { Text("All Versions") },
                        selected = selectedAppVersion == null
                    )
                }
                items(availableAppVersions) { version ->
                    FilterChip(
                        onClick = {
                            onAppVersionSelected(if (selectedAppVersion == version) null else version)
                        },
                        label = {
                            Text(
                                text = "v$version",
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        selected = selectedAppVersion == version
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Sort Options
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.sort_by),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )

            var expanded by remember { mutableStateOf(false) }

            Box {
                OutlinedButton(
                    onClick = { expanded = true }
                ) {
                    Text(sortBy.getDisplayName(context))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    SortOption.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.getDisplayName(context)) },
                            onClick = {
                                onSortChanged(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Results count and active filters summary
        Column {
            Text(
                text = stringResource(R.string.complaints_found_count, resultsCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Show active filters summary
            val activeFilters = buildList {
                if (selectedStatus != null) add("Status: ${selectedStatus.getDisplayName(context)}")
                if (selectedType != null) add("Type: ${selectedType.getDisplayName(context)}")
                if (selectedAppVersion != null) add("Version: v$selectedAppVersion")
                if (searchQuery.isNotEmpty()) add("Search: \"$searchQuery\"")
            }

            if (activeFilters.isNotEmpty()) {
                Text(
                    text = "Active filters: ${activeFilters.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

// Rest of the composables remain the same...
@Composable
private fun AdminStatisticsCard(
    complaints: List<Complaint>,
    modifier: Modifier = Modifier
) {
    val statusCounts = complaints.groupBy { it.status }.mapValues { it.value.size }
    val typeCounts = complaints.groupBy { it.type }.mapValues { it.value.size }
    val appVersionCounts = complaints
        .mapNotNull { it.metadata?.get("appVersion")?.toString() }
        .groupBy { it }
        .mapValues { it.value.size }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.complaints_statistics),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Total complaints
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.total_complaints),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = complaints.size.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status breakdown
            Text(
                text = stringResource(R.string.by_status),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )

            ComplaintStatus.entries.forEach { status ->
                val count = statusCounts[status] ?: 0
                if (count > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusChip(status = status)
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // App versions breakdown (if there are multiple versions)
            if (appVersionCounts.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "By app version",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                appVersionCounts.entries.sortedByDescending { it.value }.take(5)
                    .forEach { (version, count) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "v$version",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun AdminComplaintCard(
    complaint: Complaint,
    onStatusClick: () -> Unit,
    onEditClick: () -> Unit,
    onClosureReasonClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current
    val appVersion = complaint.metadata?.get("appVersion")?.toString()
    val replyToId = complaint.metadata?.get("replyto")
    val clipboardManager = LocalClipboardManager.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with complaint info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = complaint.type.getDisplayName(context),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = complaint.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (replyToId != null) {
                        Text(
                            text = replyToId.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.user_id_format,
                                complaint.userId.take(8)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                        )

                        // App Version Display
                        if (!appVersion.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer
                            ) {
                                Text(
                                    text = "v$appVersion",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Surface(
                        modifier = Modifier.clickable { onStatusClick() },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusChip(status = complaint.status)
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Change Status",
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(start = 4.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Complaint body
            Text(
                modifier = Modifier.combinedClickable(
                    onClick = { /* if you want a normal click */ },
                    onLongClick = {
                        clipboardManager.setText(AnnotatedString(complaint.body))

                        Toast.makeText(
                            context,
                            context.getString(R.string.title_copied),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ),
                text = complaint.body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            // Show closure reason if exists
            if (complaint.status == ComplaintStatus.CLOSED || complaint.status == ComplaintStatus.PINNED) {
                val closureReason = complaint.metadata?.get("reason")?.toString()
                if (!closureReason.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ClosureReasonCard(closureReason = closureReason)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Footer with timestamp and actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = formatTimestamp(complaint.createdAt?.time ?: 0L),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.feedback_id_format, complaint.id.take(8)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Action buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Add closure reason button
                    IconButton(
                        onClick = onClosureReasonClick,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Note,
                            contentDescription = "Add Closure Reason",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    // Edit button
                    IconButton(
                        onClick = onEditClick,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }

                    // Delete button
                    IconButton(
                        onClick = onDeleteClick,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
    }
}

enum class SortOption {
    DATE_DESC, DATE_ASC, STATUS, TYPE, USER_ID, APP_VERSION, APP_VERSION_DESC;

    fun getDisplayName(context: Context): String = when (this) {
        DATE_DESC -> context.getString(R.string.sort_date_newest)
        DATE_ASC -> context.getString(R.string.sort_date_oldest)
        STATUS -> context.getString(R.string.sort_status)
        TYPE -> context.getString(R.string.sort_type)
        USER_ID -> context.getString(R.string.sort_user_id)
        APP_VERSION -> context.getString(R.string.sort_app_version)
        APP_VERSION_DESC -> context.getString(R.string.sort_app_version_desc)
    }
}