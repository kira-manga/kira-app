package me.manga.yamiapk.presentation.features.complaint.ui.components


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.complaint.model.Complaint
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintStatus
import me.manga.yamiapk.presentation.features.complaint.utils.apiLevelToAndroidVersion
import me.manga.yamiapk.presentation.features.complaint.utils.formatTimestamp

@Composable
fun ComplaintCard(
    complaint: Complaint,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 16.dp,vertical = 4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        )
        {
            // Header with title and status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = complaint.type.getDisplayName(context = context),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = complaint.subject,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                StatusChip(status = complaint.status)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Device and source info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                complaint.metadata
                    ?.get("osVersion")               // Any?
                    ?.toString()                     // String?
                    ?.toIntOrNull()                  // Int?
                    ?.let { apiLevel ->              // Only if apiLevel != null
                        InfoItem(
                            icon = Icons.Default.Android,
                            text = apiLevelToAndroidVersion(apiLevel)
                        )
                    }

                // Manufacturer
                complaint.metadata
                    ?.get("manufacturer")            // Any?
                    ?.toString()                     // String?
                    ?.takeIf { it.isNotBlank() }    // only non-blank
                    ?.let { manufacturer ->         // Only if manufacturer != null/blank
                        InfoItem(
                            icon = Icons.Default.PhoneAndroid,
                            text = manufacturer
                        )
                    }
            }


            Spacer(modifier = Modifier.height(8.dp))

            // Description
            Text(
                text = complaint.body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Show closure reason if status is CLOSED
            if (complaint.status == ComplaintStatus.CLOSED || complaint.status == ComplaintStatus.PINNED ) {
                val closureReason = complaint.metadata?.get("reason")?.toString()
                if (!closureReason.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ClosureReasonCard(closureReason = closureReason)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Footer with timestamp and ID
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            )
            {
                Text(
                    text = formatTimestamp(complaint.createdAt?.time ?: 0L),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.feedback_id_format, complaint.id.take(8)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            }

        }
    }
