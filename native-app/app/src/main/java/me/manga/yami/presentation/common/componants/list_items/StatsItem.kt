package me.manga.yamiapk.presentation.common.componants.list_items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.yamiapk.R


@Composable
fun StatsItem(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    value: Int = 0,
    onClick: (() -> Unit)? = null

) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            )
            .padding(vertical = 12.dp),

        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))

        }

        Column(modifier = Modifier.weight(1f)) {
            androidx.compose.material.Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp
            )
            description?.let {
                androidx.compose.material.Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8F),
                    fontSize = 12.sp
                )
            }
        }
        Text(
            text = stringResource(R.string.value_count, value),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
    }
}


@Preview(showBackground = true)
@Composable
fun PreviewStatsItem() {
    MaterialTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                StatsItem(
                    title = "Views",
                    description = "Number of page views",
                    icon = Icons.Default.Info,
                    value = 1234,
                    onClick = {}
                )
                Spacer(modifier = Modifier.height(8.dp))
                StatsItem(
                    title = "Likes",
                    value = 5678
                )
            }
        }
    }
}
