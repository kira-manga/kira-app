package me.manga.yamiapk.presentation.features.library.ui.components.library_sheet

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.presentation.features.library.ui.viewmodel.LibraryViewModel


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> FilterChipsRow(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    label: (T) -> String
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        items.forEach { item ->
            val isSelected = item == selectedItem
            FilterChipItem(
                item = item,
                isSelected = isSelected,
                onClick = { onItemSelected(item) },
                label = label
            )
        }
    }
}


@Composable
fun <T> FilterChipItem(
    item: T,
    isSelected: Boolean,
    onClick: () -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label(item)) },
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

@Preview(showBackground = true)
@Composable
fun FilterChipsPreview2() {
    // Example using LibraryViewModel.FilterType
    val sampleItems = LibraryViewModel.FilterType.entries.toList()
    val (selected, setSelected) = remember { androidx.compose.runtime.mutableStateOf(sampleItems.first()) }

   val coont =  LocalContext.current
    FilterChipsRow(
        items = sampleItems,
        selectedItem = selected,
        onItemSelected = setSelected,
        label = { it.getDisplayName(coont) }
    )
}
