package me.manga.yamiapk.presentation.features.complaint.ui.components

import AutoSubtitleText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintStatus
import me.manga.yamiapk.presentation.features.complaint.model.DialogAction
import me.manga.yamiapk.presentation.features.complaint.utils.getColor
import me.manga.yamiapk.presentation.features.complaint.utils.getColorWithContrast

@Composable
fun ComplaintActionDialog(
    complaint: Complaint,
    onDismiss: () -> Unit,
    onReply: (Complaint,  Complaint) -> Unit,
    onEdit: (Complaint, String) -> Unit,
    onDelete: (Complaint) -> Unit
) {
    var currentAction by remember { mutableStateOf(DialogAction.NONE) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            when (currentAction) {
                DialogAction.NONE -> {
                    ActionSelectionContent(
                        complaint = complaint,
                        onReplyClick = { currentAction = DialogAction.REPLY },
                        onEditClick = { currentAction = DialogAction.EDIT },
                        onDeleteClick = { currentAction = DialogAction.DELETE },
                        onDismiss = onDismiss
                    )
                }
                DialogAction.REPLY -> {
                    ReplyContent(
                        complaint = complaint,
                        onReply = { replyText ->
                            onReply(complaint, replyText)
                            onDismiss()
                        },
                        onBack = { currentAction = DialogAction.NONE }
                    )
                }
                DialogAction.EDIT -> {
                    EditContent(
                        complaint = complaint,
                        onEdit = { editedText ->
                            onEdit(complaint, editedText)
                            onDismiss()
                        },
                        onBack = { currentAction = DialogAction.NONE }
                    )
                }
                DialogAction.DELETE -> {
                    DeleteConfirmationContent(
                        complaint = complaint,
                        onConfirmDelete = {
                            onDelete(complaint)
                            onDismiss()
                        },
                        onBack = { currentAction = DialogAction.NONE }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionSelectionContent(
    complaint: Complaint,
    onReplyClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.complaint_actions),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close)
                )
            }
        }

        // Complaint preview
        ComplaintPreviewCard(complaint = complaint)

        // Action buttons
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Reply button
            ElevatedButton(
                onClick = onReplyClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.reply),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (complaint.status != ComplaintStatus.PINNED){
            // Edit button
            OutlinedButton(
                onClick = onEditClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.edit),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            // Delete button
            OutlinedButton(
                onClick = onDeleteClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.delete),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            }
        }
    }
}

@Composable
private fun ReplyContent(
    complaint: Complaint,
    onReply: (Complaint) -> Unit,
    onBack: () -> Unit
) {
    var replyText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val maxChars = 500

    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
                AutoSubtitleText(
                    text = stringResource(R.string.reply_to_complaint),
                     maxLines = 1,
                    maxSize = 22.sp,
                    fontSize = 22.sp,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize =  22.sp,
                        color =  MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }

        // Original complaint reference
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.reply_to_complaint_id, complaint.id),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = complaint.subject,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = complaint.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Reply input
        OutlinedTextField(
            value = replyText,
            onValueChange = { if (it.length <= maxChars) replyText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.your_reply)) },
            placeholder = { Text(stringResource(R.string.reply_placeholder)) },
            minLines = 3,
            maxLines = 6,
            shape = RoundedCornerShape(12.dp),
            isError = replyText.length > maxChars
        )

        // Character count
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = stringResource(R.string.character_count, replyText.length, maxChars),
                style = MaterialTheme.typography.bodySmall,
                color = if (replyText.length > maxChars) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                Text(stringResource(R.string.cancel))
            }

            Button(
                onClick = {
                    if (replyText.isNotBlank() && replyText.length <= maxChars) {
                        isLoading = true
                        onReply(
                            Complaint(
                            userId = complaint.userId,
                            type = complaint.type,
                            subject =complaint.subject,
                            body = replyText,
                            status =  ComplaintStatus.OPEN,
                            metadata = (complaint.metadata ?: emptyMap()) + mapOf("replyto" to complaint.id)
                        )
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = replyText.isNotBlank() && replyText.length <= maxChars && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    AutoSubtitleText(stringResource(R.string.send_reply), maxLines = 1, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun EditContent(
    complaint: Complaint,
    onEdit: (String) -> Unit,
    onBack: () -> Unit
) {
    var editedText by remember { mutableStateOf(complaint.body) }
    var editedSubject by remember { mutableStateOf(complaint.subject) }
    var isLoading by remember { mutableStateOf(false) }
    val maxChars = 1000

    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
                AutoSubtitleText(
                    text = stringResource(R.string.edit_complaint),
                    maxLines = 1,
                    maxSize = 22.sp,
                    fontSize = 22.sp,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize =  22.sp,
                        color =  MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }

        // Edit info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.edit_complaint_id, complaint.id),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Subject input
        OutlinedTextField(
            value = editedSubject,
            onValueChange = { editedSubject = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.subject)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // Body input
        OutlinedTextField(
            value = editedText,
            onValueChange = { if (it.length <= maxChars) editedText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.complaint_body)) },
            placeholder = { Text(stringResource(R.string.edit_placeholder)) },
            minLines = 4,
            maxLines = 8,
            shape = RoundedCornerShape(12.dp),
            isError = editedText.length > maxChars
        )

        // Character count
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = stringResource(R.string.character_count, editedText.length, maxChars),
                style = MaterialTheme.typography.bodySmall,
                color = if (editedText.length > maxChars) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                AutoSubtitleText(stringResource(R.string.cancel))
            }

            Button(
                onClick = {
                    if (editedText.isNotBlank() && editedSubject.isNotBlank() &&
                        editedText.length <= maxChars) {
                        isLoading = true
                        onEdit(editedText)
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = editedText.isNotBlank() && editedSubject.isNotBlank() &&
                        editedText.length <= maxChars && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    AutoSubtitleText(stringResource(R.string.save_changes), maxLines = 1, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmationContent(
    complaint: Complaint,
    onConfirmDelete: () -> Unit,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
                AutoSubtitleText(
                    text = stringResource(R.string.delete_complaint),
                    maxLines = 1,
                    maxSize = 20.sp,
                    fontSize = 20.sp,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize =  20.sp,
                        color =  MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }

        // Warning card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.delete_warning_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.delete_warning_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Complaint preview
        ComplaintPreviewCard(complaint = complaint)

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                Text(stringResource(R.string.cancel))
            }

            Button(
                onClick = {
                    isLoading = true
                    onConfirmDelete()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                } else {
                    AutoSubtitleText(
                        text = stringResource(R.string.delete_forever),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }
    }
}

@Composable
private fun ComplaintPreviewCard(complaint: Complaint) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = complaint.subject,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = complaint.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))

                // Status badge with proper colors
                val (backgroundColor, textColor) = complaint.status.getColorWithContrast()
                Surface(
                    color = backgroundColor,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = complaint.status.getDisplayName(context),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "ID: ${complaint.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}