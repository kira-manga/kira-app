package me.manga.yamiapk.presentation.features.library.ui.components.library_sheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R


@Composable
fun DisplayOptionsSection(
    modifier: Modifier = Modifier,
    count: Int? = null,                  // 0 means “auto”
    onCountChange: (Int) -> Unit,
    switchContents: List<@Composable () -> Unit>
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Render the slider section
        if (count != null) {
            Text(
                text = stringResource(R.string.items_per_row_label),
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = count.toFloat(),
                onValueChange = { onCountChange(it.toInt()) },
                valueRange = 0f..8f,     // allow 0
                steps = 7                // 4 “in-between” steps gives you 5 positions: 0,1,2,3,4
            )
            Text(
                text = if (count == 0)
                    stringResource(R.string.auto_text)
                else
                    stringResource(
                        R.string.items_count_format,
                        count,
                        if (count > 1) stringResource(R.string.items_plural) else stringResource(R.string.items_singular)
                    ),
                fontStyle = if (count == 0) FontStyle.Italic else FontStyle.Normal
            )
            Spacer(Modifier.height(24.dp))
            Divider()
        }
        Spacer(Modifier.height(24.dp))

        // Render each switch section, separated by dividers
        switchContents.forEachIndexed { index, switchItem ->
            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
            switchItem.invoke()
            if (index == switchContents.lastIndex) Divider()
        }
    }
}

