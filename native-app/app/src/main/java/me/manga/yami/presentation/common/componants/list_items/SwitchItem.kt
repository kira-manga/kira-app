package me.manga.yamiapk.presentation.common.componants.list_items

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
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SwitchItem(
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tint :Color =MaterialTheme.colorScheme.onBackground
) {

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
            description?.let {
                Text(text = it, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5F), fontSize = 12.sp)
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.onBackground,
                uncheckedTrackAlpha = 0.4f

            )
        )
    }
}



@Preview(showBackground = true)
@Composable
fun PreviewSwitchItem() {
    MaterialTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                SwitchItem(
                    title = "Enable Notifications",
                    description = "Receive updates",
                    icon = Icons.Default.Settings,
                    checked = true,
                    onCheckedChange = {}
                )
                Spacer(modifier = Modifier.height(8.dp))
                SwitchItem(
                    title = "Dark Mode",
                    checked = false,
                    onCheckedChange = {}
                )
            }
        }
    }
}