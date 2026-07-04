package me.manga.yamiapk.presentation.features.reader.ui.components.reading_mode_dialog

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.presentation.features.reader.data.ReadingMode


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingModeChips(
    modes: List<ReadingMode>,
    selectedMode: ReadingMode,
    onModeSelected: (ReadingMode) -> Unit
) {
    FlowColumn(
        modifier = Modifier.fillMaxWidth()
    ) {
        modes.forEach { mode ->
            val isSelected = mode == selectedMode
            ChipItem(
                mode = mode,
                isSelected = isSelected,
                onClick = { onModeSelected(mode) }
            )
        }
    }
}

@Composable
fun ChipItem(
    mode: ReadingMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            disabledSelectedContainerColor =MaterialTheme.colorScheme.inverseOnSurface,
            disabledContainerColor = MaterialTheme.colorScheme.inverseOnSurface,
            containerColor = MaterialTheme.colorScheme.inverseOnSurface.copy(0.3F),
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        label = {
            Row {
                Icon(
                    painter = painterResource(id = mode.iconRes),
                    contentDescription = mode.name,
                    modifier = Modifier.size(20.dp),
                    tint = if (isSelected)  MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary.copy(alpha = 0.9F)
                )
                Spacer(modifier = Modifier.width(8.dp).height(12.dp))
                Text(
                    text = stringResource(mode.titleRes),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected)  MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary.copy(alpha = 0.9F)
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()

            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(40.dp)              // ← new: set a fixed height
                 ,
        shape = RoundedCornerShape(18.dp),

        border = FilterChipDefaults.filterChipBorder(
            borderColor = if (isSelected)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            enabled = true,
            selected = isSelected
        ),

    )
}
