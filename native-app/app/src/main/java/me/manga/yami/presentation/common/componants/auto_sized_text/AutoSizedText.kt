
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp


@Composable
fun AutoSubtitleText(
    text: String,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
    fontSize: TextUnit  = 12.sp,
    maxSize: TextUnit  = 14.sp,
    minSize: TextUnit  = 6.sp,
    maxLines : Int = 2,
    fontWeight: FontWeight? =null,
    overflow: TextOverflow = TextOverflow.Clip,
    style: TextStyle? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        BasicText(
            text = text,
            style = style
                ?: TextStyle(color = color, fontSize = fontSize, textAlign = textAlign, fontWeight = fontWeight,),

            overflow =overflow ,
            maxLines = maxLines,
            autoSize = TextAutoSize.StepBased(
                minFontSize = minSize,
                maxFontSize = maxSize,
                stepSize = 0.1.sp
            )
        )
    }
}

/**
 * Auto‐sized Small/Caption Text
 * - Initial font size: 14.sp
 * - Can scale down to 10.sp, up to 18.sp
 */
@Composable
fun NavigationBarAutoText(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        BasicText(
            text = text,
            style = TextStyle(
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 12.sp
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 8.sp,
                maxFontSize = 14.sp,
                stepSize = 1.sp
            )
        )
    }
}
