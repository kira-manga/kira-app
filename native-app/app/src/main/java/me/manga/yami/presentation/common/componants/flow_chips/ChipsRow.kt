package me.manga.yamiapk.presentation.common.componants.flow_chips

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipsRow(
    items: List<String>,
    selectedItem: String,
    onItemSelected: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        items.forEach { text ->
            val isSelected = text == selectedItem
            ChipItemRow(
                text = text,
                isSelected = isSelected,
                onClick = { onItemSelected(text) }
            )
        }
    }
}

@Composable
fun ChipItemRow(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = if (isSelected) {
            {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        } else null,
        modifier = modifier
            .defaultMinSize(minHeight = 32.dp)
            .padding(horizontal = 4.dp),
        shape = RoundedCornerShape(16.dp),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = if (isSelected)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            enabled = true,
            selected = isSelected
        ),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            disabledSelectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}