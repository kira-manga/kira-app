package me.manga.yamiapk.presentation.features.statistics.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.yamiapk.R


@Composable
fun StatsOverview(
    inLibrary: Int,
    readDuration: String,
    completedEntries: Int
) {
    Card(
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp), // gap between items
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            OverviewItem(
                label = stringResource(R.string.label_in_library),
                value = inLibrary.toString(),
                modifier = Modifier.weight(1f)      // fill equally
            )
            OverviewItem(
                label = stringResource(R.string.label_read_duration),
                value = readDuration,
                modifier = Modifier.weight(1f)
            )
            OverviewItem(
                label = stringResource(R.string.label_completed_entries),
                value = completedEntries.toString(),
                modifier = Modifier.weight(1f)
            )   }
    }
}

@Composable
fun OverviewItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier  // allow parent to size/position
) {
    Column(
        modifier = modifier.padding(horizontal = 18.dp),          // apply incoming modifier
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = formatStatValue(value),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun formatStatValue(value: String): String {
    return value.toIntOrNull()?.let { number ->
        stringResource(R.string.value_count, number)
    } ?: value
}