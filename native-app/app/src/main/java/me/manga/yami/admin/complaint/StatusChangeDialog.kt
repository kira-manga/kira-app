@file:OptIn(ExperimentalMaterial3Api::class)

package me.manga.yamiapk.admin.complaint

import me.manga.yamiapk.presentation.features.complaint.ui.components.StatusChip
import me.manga.yamiapk.presentation.features.complaint.utils.getDisplayText

// presentation/features/complaint/ui/components/AdminDialogs.kt


import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.complaint.model.*

@Composable
fun StatusChangeDialog(
    complaint: Complaint,
    onDismiss: () -> Unit,
    onStatusChanged: (Complaint, ComplaintStatus) -> Unit
) {
    val context = LocalContext.current
    var selectedStatus by remember { mutableStateOf(complaint.status) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.change_status),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.complaint_id_format, complaint.id.take(12)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.select_new_status),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column {
                    ComplaintStatus.entries.forEach { status ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedStatus == status,
                                    onClick = { selectedStatus = status }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedStatus == status,
                                onClick = { selectedStatus = status }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusChip(status = status)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onStatusChanged(complaint, selectedStatus)
                },
                enabled = selectedStatus != complaint.status
            ) {
                Text(stringResource(R.string.update_status))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun EditComplaintDialog(
    complaint: Complaint,
    onDismiss: () -> Unit,
    onComplaintUpdated: (Complaint) -> Unit
) {
    val context = LocalContext.current
    var selectedType by remember { mutableStateOf(complaint.type) }
    var subject by remember { mutableStateOf(complaint.subject) }
    var body by remember { mutableStateOf(complaint.body) }
    var showTypeDropdown by remember { mutableStateOf(false) }

    val hasChanges = selectedType != complaint.type ||
            subject != complaint.subject ||
            body != complaint.body

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.edit_complaint),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.complaint_id_format, complaint.id.take(12)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Type Selector
                Text(
                    text = stringResource(R.string.complaint_type),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = selectedType.getDisplayName(context),
                        onValueChange = { },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { showTypeDropdown = !showTypeDropdown }) {
                                Icon(
                                    if (showTypeDropdown) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Type"
                                )
                            }
                        }
                    )

                    DropdownMenu(
                        expanded = showTypeDropdown,
                        onDismissRequest = { showTypeDropdown = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ComplaintType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Text(type.getDisplayName(context))
                                },
                                onClick = {
                                    selectedType = type
                                    showTypeDropdown = false
                                },
                                leadingIcon = {
                                    if (selectedType == type) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Subject Field
                Text(
                    text = stringResource(R.string.subject),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.enter_subject)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Body Field
                Text(
                    text = stringResource(R.string.description),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.enter_description)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default
                    ),
                    maxLines = 5
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    Button(
                        onClick = {
                            val updatedComplaint = complaint.copy(
                                type = selectedType,
                                subject = subject.trim(),
                                body = body.trim()
                            )
                            onComplaintUpdated(updatedComplaint)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = hasChanges && subject.trim().isNotEmpty() && body.trim().isNotEmpty()
                    ) {
                        Text(stringResource(R.string.save_changes))
                    }
                }
            }
        }
    }
}

@Composable
fun ClosureReasonDialog(
    complaint: Complaint,
    onDismiss: () -> Unit,
    onReasonAdded: (Complaint, String) -> Unit
) {
    var reasonText by remember { mutableStateOf("") }
    var selectedReasonType by remember { mutableStateOf(ClosureReasonType.OTHER) }
    var showReasonTypeDropdown by remember { mutableStateOf(false) }

    // Pre-fill with existing reason if available
    LaunchedEffect(complaint) {
        val existingReason = complaint.metadata?.get("reason")?.toString()
        if (!existingReason.isNullOrBlank()) {
            reasonText = existingReason
            selectedReasonType = ClosureReasonType.fromString(existingReason)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.add_closure_reason),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.complaint_id_format, complaint.id.take(12)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Current Status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.current_status),
                        style = MaterialTheme.typography.labelMedium
                    )
                    StatusChip(status = complaint.status)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Reason Type Selector
                Text(
                    text = stringResource(R.string.reason_type),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = selectedReasonType.getDisplayText(),
                        onValueChange = { },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        trailingIcon = {
                            IconButton(onClick = { showReasonTypeDropdown = !showReasonTypeDropdown }) {
                                Icon(
                                    if (showReasonTypeDropdown) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Reason Type"
                                )
                            }
                        }
                    )

                    DropdownMenu(
                        expanded = showReasonTypeDropdown,
                        onDismissRequest = { showReasonTypeDropdown = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ClosureReasonType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Text(type.getDisplayText())
                                },
                                onClick = {
                                    selectedReasonType = type
                                    showReasonTypeDropdown = false
                                },
                                leadingIcon = {
                                    if (selectedReasonType == type) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Reason Text Field
                Text(
                    text = stringResource(R.string.closure_reason_details),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )

                OutlinedTextField(
                    value = reasonText,
                    onValueChange = { reasonText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.enter_closure_reason)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default
                    ),
                    maxLines = 5
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.cancel))
                    }

                    Button(
                        onClick = {
                            val finalReason = if (selectedReasonType != ClosureReasonType.OTHER) {
                                "${selectedReasonType.key}: ${reasonText.trim()}"
                            } else {
                                reasonText.trim()
                            }
                            onReasonAdded(complaint, finalReason)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = reasonText.trim().isNotEmpty()
                    ) {
                        Text(stringResource(R.string.add_reason))
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteConfirmationDialog(
    complaint: Complaint,
    onDismiss: () -> Unit,
    onConfirmDelete: (Complaint) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(R.string.delete_complaint_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.delete_complaint_message),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.complaint_details),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = stringResource(R.string.complaint_id_format, complaint.id.take(12)),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )

                        Text(
                            text = complaint.subject,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.status_label),
                                style = MaterialTheme.typography.bodySmall
                            )
                            StatusChip(status = complaint.status)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmDelete(complaint) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    text = stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.onError
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}