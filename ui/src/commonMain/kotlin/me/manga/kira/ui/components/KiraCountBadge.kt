package me.manga.kira.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact icon + optional count caption for the Yami design system (Phase 11.ui.UP-2).
 *
 * Used for the small inline status badges on cards and top bars (has-downloads, downloaded count,
 * bookmark count, active downloads): a small [Icon] paired with an optional trailing count.
 * Replaces the interim text-glyph captions (`✓`, `↓ N`, `🔖 N`). When [count] is null only the
 * icon renders (boolean-indicator form).
 *
 * **Width-adaptive (native `IconWithCount` parity, P2 components fix).** When [adaptive] is true
 * (the on-cover detail-badge strip lays its four badges out with `Modifier.weight(1f)`), the icon,
 * count font, and inter-element padding scale proportionally to the bounded width exactly as native
 * `IconWithCount.kt` does:
 *  - icon = `(width * 0.4f)` clamped to `1..24.dp`,
 *  - count font = `(width * 0.3f)` clamped to `1..12.sp`, rendered via an auto-shrinking [BasicText]
 *    ([TextAutoSize.StepBased], 0.1.sp step) so a long count never overflows the cell,
 *  - start padding = `(width * 0.1f)` clamped to `0.2..4.dp`.
 *
 * When [adaptive] is false (the default — inline badges such as the standalone download / bookmark
 * indicators), the fixed [iconSize] / [textStyle] / 2.dp spacing fallbacks apply, preserving the
 * original compact inline form. Adaptive scaling is opt-in because a non-weighted inline Row child is
 * also measured with a finite max width, so constraint inspection alone cannot distinguish the two.
 *
 * @param adaptive opt into width-proportional scaling; pass true only from bounded/weighted parents.
 * @param tint applied to BOTH the icon and the count text so a badge reads as one coloured unit.
 *   Over a cover scrim, callers pass [Color.White] (matching native's hardcoded white tint).
 * @param iconSize fixed icon size used only when [adaptive] is false.
 * @param textStyle fixed count text style used only when [adaptive] is false.
 */
@Composable
fun KiraCountBadge(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    count: Int? = null,
    adaptive: Boolean = false,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 14.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelSmall,
) {
    BoxWithConstraints(modifier = modifier) {
        if (adaptive) {
            val availableWidth = maxWidth
            // Mirror IconWithCount.kt: icon 0.4·w (1..24dp), padding 0.1·w (0.2..4dp), font 0.3·w
            // (1..12sp). The font fraction is computed in px→sp via LocalDensity, like native.
            val iconDp = (availableWidth * 0.4f).coerceIn(1.dp, 24.dp)
            val paddingDp = (availableWidth * 0.1f).coerceIn(0.2.dp, 4.dp)
            val rawFontSp = with(LocalDensity.current) { (availableWidth * 0.3f).toSp() }
            val fontSp = rawFontSp.value.coerceIn(1f, 12f).sp

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(iconDp),
                    tint = tint,
                )
                if (count != null) {
                    BasicText(
                        text = count.toString(),
                        style = TextStyle(color = tint, fontSize = fontSp, textAlign = TextAlign.Center),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 1.sp,
                            maxFontSize = fontSp,
                            stepSize = 0.1.sp,
                        ),
                        modifier = Modifier.padding(start = paddingDp),
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(iconSize),
                    tint = tint,
                )
                if (count != null) {
                    Text(text = count.toString(), style = textStyle, color = tint)
                }
            }
        }
    }
}
