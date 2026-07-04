package me.manga.yamiapk.presentation.common.componants.buttons

import AutoSubtitleText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionButton(
    text: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    isLoading: Boolean = false
) {
    Surface(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = Color.Transparent,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    modifier = Modifier.size(24.dp),
                    tint = color
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            AutoSubtitleText(
                text = text,
                fontSize = 10.sp,
                maxSize = 10.sp,
                minSize = 4.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                color = color
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewActionButton() {
    MaterialTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                ActionButton(
                    text = "Like",
                    icon = Icons.Default.Favorite,
                    color = Color.Red,
                    onClick = {},
                    onLongClick = {},
                    isLoading = false
                )
                Spacer(modifier = Modifier.height(8.dp))
                ActionButton(
                    text = "Loading",
                    icon = Icons.Default.Info,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = {},
                    isLoading = true
                )
            }
        }
    }
}
