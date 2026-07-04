package me.manga.yamiapk.presentation.features.library.ui.components

import AutoSubtitleText
import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun IconWithCount(
    icon: ImageVector,
    count: Int,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val availableWidth = maxWidth

        // Define default and min sizes:
        val defaultIconDp = 24.dp
        val minIconDp = 1.dp
        val defaultFontSp = 12.sp
        val minFontSp = 1.sp
        val defaultPaddingDp = 4.dp
        val minPaddingDp = 0.2.dp

        // Compute icon size: a fraction of availableWidth, clamped to [min, default]
        val iconDp = (availableWidth * 0.4f).coerceIn(minIconDp, defaultIconDp)
        // Compute padding: smaller when width is small
        val paddingDp = (availableWidth * 0.1f).coerceIn(minPaddingDp, defaultPaddingDp)

        // Compute raw TextUnit, then clamp via its .value and convert back to sp:
        val rawFontSp = with(LocalDensity.current) { (availableWidth * 0.3f).toSp() }
        val fontSp = rawFontSp.value
            .coerceIn(minFontSp.value, defaultFontSp.value)
            .sp

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(iconDp)
            )
            AutoSubtitleText(
                text = count.toString(),
                color = Color.White,
                fontSize = fontSp,
                maxSize = fontSp,
                minSize = minFontSp,
                maxLines = 1,
                modifier = Modifier.padding(start = paddingDp)
            )
        }
    }
}