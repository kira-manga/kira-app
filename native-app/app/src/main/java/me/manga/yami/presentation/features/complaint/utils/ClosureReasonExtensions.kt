package me.manga.yamiapk.presentation.features.complaint.utils

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.complaint.model.ClosureReasonType
import me.manga.yamiapk.presentation.features.complaint.model.ComplaintStatus


@Composable
fun ClosureReasonType.getDisplayText(): String {
    return when (this) {
        ClosureReasonType.DONE -> stringResource(R.string.closure_reason_done)
        ClosureReasonType.DONE_WAIT_UPDATE -> stringResource(R.string.closure_reason_done_wait_update)
        ClosureReasonType.OTHER -> stringResource(R.string.closure_reason_other)
        ClosureReasonType.PINNED -> stringResource(R.string.pinned_reason)
    }
}

@Composable
fun ClosureReasonType.getColorScheme(): Pair<Color, Color> {
    return when (this) {
        ClosureReasonType.DONE -> {
            // Green colors
            Color(0xFF4CAF50).copy(alpha = 0.2f) to Color(0xFF2E7D32)
        }
        ClosureReasonType.DONE_WAIT_UPDATE -> {
            // Blue colors
            Color(0xFF2196F3).copy(alpha = 0.2f) to Color(0xFF1565C0)
        }
        ClosureReasonType.OTHER -> {
            // Default colors (same as before)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) to
                    MaterialTheme.colorScheme.onErrorContainer
        }

        ClosureReasonType.PINNED -> Color(0xFFFFFFFF).copy(alpha = 0.8f) to Color(0xFF000000)
    }
}

@Composable
fun ComplaintStatus.getColor(): Color {
    return when (this) {
        ComplaintStatus.PINNED -> Color(0xFFFF9800) // Orange
        ComplaintStatus.IN_PROGRESS -> Color(0xFF2196F3) // Blue
        ComplaintStatus.RESOLVED -> Color(0xFF4CAF50) // Green
        ComplaintStatus.CLOSED -> Color(0xFF607D8B) // Blue Grey
        ComplaintStatus.OPEN -> Color(0xFFF44336) // Red
        ComplaintStatus.PLANNED -> Color(0xFF9C27B0) // Purple
        ComplaintStatus.UNKNOWN -> Color(0xFF1A191A)
        ComplaintStatus.NOT_PLANNED ->  Color(0xFF1A191A)
    }
}

@Composable
fun ComplaintStatus.getColorWithContrast(): Pair<Color, Color> {
    return when (this) {
        ComplaintStatus.PINNED -> Pair(
            Color(0xFFFF9800), // Background: Orange
            Color.White        // Text: White
        )
        ComplaintStatus.IN_PROGRESS -> Pair(
            Color(0xFF2196F3), // Background: Blue
            Color.White        // Text: White
        )
        ComplaintStatus.RESOLVED -> Pair(
            Color(0xFF4CAF50), // Background: Green
            Color.White        // Text: White
        )
        ComplaintStatus.CLOSED -> Pair(
            Color(0xFF607D8B), // Background: Blue Grey
            Color.White        // Text: White
        )
        ComplaintStatus.UNKNOWN -> Pair(
            Color(0xFFF44336), // Background: Red
            Color.White        // Text: White
        )
        ComplaintStatus.PLANNED -> Pair(
            Color(0xFF9C27B0), // Background: Purple
            Color.White        // Text: White
        )

        ComplaintStatus.OPEN -> Pair(
            Color(0xFF00BCD4), // Background: Purple
            Color.White        // Text: White
        )

        ComplaintStatus.NOT_PLANNED ->  Pair(   Color(0xFF8BC34A), // Background: Purple
        Color.White        // Text: White
            )
    }
}