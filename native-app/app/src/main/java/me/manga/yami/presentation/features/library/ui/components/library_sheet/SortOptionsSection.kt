package me.manga.yamiapk.presentation.features.library.ui.components.library_sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SortOptionsSection(
    items: List<T>,
    selectedItem: T,
    isAscending: Boolean,
    onItemSelected: (T) -> Unit,
    onDirectionChange: (Boolean) -> Unit,
    label: (T) -> String,
    headerText: String,
    sortByText: String,
    sortDirectionText: String,
    ascendingText: String,
    descendingText: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = headerText,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Sort direction toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SwapVert,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(sortDirectionText)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isAscending) ascendingText else descendingText)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = isAscending,
                    onCheckedChange = onDirectionChange
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = sortByText,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            items.forEach { item ->
                val selected = item == selectedItem
                FilterChip(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    selected = selected,
                    onClick = { onItemSelected(item) },
                    shape = RoundedCornerShape(16.dp),
                    label = { Text(label(item)) }
                )
            }
        }
    }
}
