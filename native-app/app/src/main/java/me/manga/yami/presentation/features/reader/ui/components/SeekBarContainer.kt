package me.manga.yamiapk.presentation.features.reader.ui.components

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ContentAlpha
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R

@Composable
fun SeekBarContainer(
    modifier: Modifier = Modifier,
    progress: Float,
    total: Float,
    hasNext: Boolean,
    hasPrevious: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekChange: (Float) -> Unit
) {
    val disabledAlpha = ContentAlpha.disabled
    val activeColor = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Previous button in a rounded card
        Card(
            shape = RoundedCornerShape(50),
            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.background.copy(alpha = 0.8f)),
            modifier = Modifier.wrapContentSize()
        ) {
            IconButton(
                enabled = hasNext,
                onClick = onNext,
                modifier = Modifier.wrapContentSize().padding(1.dp),

                ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_previous),
                    contentDescription = "Next",
                    modifier = Modifier.wrapContentSize(),
                    tint = activeColor.copy(alpha = if (hasNext) 1f else disabledAlpha)
                )
            }
        }

        // Slider group in its own rounded card
        Card(
            shape = RoundedCornerShape(50),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f)),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .fillMaxHeight()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                if (total > 0) {
                    Text(
                        text =( progress+1).toInt().toString(),
                        style = MaterialTheme.typography.bodySmall,

                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )

                    Slider(
                        value = progress,
                        onValueChange = onSeekChange,
                        valueRange = 0f..total-1,                  // ← starts at 1, not 0
                        steps = (total.toInt() - 1).coerceAtLeast(0),
                        modifier = Modifier.weight(9f)
                    )

                    Text(
                        text = total.toInt().toString(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodySmall,

                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Next button in a rounded card
        Card(
            shape = RoundedCornerShape(50),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f)),
            modifier = Modifier.wrapContentSize()
        ) {
            IconButton(
                enabled = hasPrevious,

                onClick = onPrevious,
                modifier = Modifier.wrapContentSize().padding(1.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_next),
                    contentDescription = "Previous",
                    modifier = Modifier.wrapContentSize(),
                    tint = activeColor.copy(alpha = if (hasPrevious) 1f else disabledAlpha)



                )
            }
        }
    }
}
