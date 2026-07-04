package me.manga.yamiapk.presentation.features.reader.ui.components

import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import me.manga.yamiapk.R
import kotlin.math.max
import kotlin.math.min


@Composable
fun Modifier.drawFallbackOnOOM(
    @DrawableRes fallbackIconRes: Int,
    fallbackText: String = stringResource(R.string.image_too_large_tap_here_to_open_the_chapter_in_webview),
    onOpenInWebView: () -> Unit,
): Modifier {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightDp = configuration.screenHeightDp.dp

    // Convert Dp → px
    val screenHeightPx = remember(screenHeightDp) { with(density) { screenHeightDp.toPx() } }
    val iconPx = remember { with(density) { 62.dp.toPx() } }
    val marginPx = remember { with(density) { 8.dp.toPx() } }
    var isError by remember { mutableStateOf(false) }

    // Pre‑render your vector drawable into ImageBitmap once
    val iconBitmap: ImageBitmap = remember(fallbackIconRes) {
        val drawable = ContextCompat.getDrawable(context, fallbackIconRes)!!
        val bmp = createBitmap(iconPx.toInt(), iconPx.toInt())
        android.graphics.Canvas(bmp).apply {
            drawable.setBounds(0, 0, bmp.width, bmp.height)
            drawable.draw(this)
        }
        bmp.asImageBitmap()
    }

    // Now this is the Android Paint that *does* have measureText(), descent(), ascent()
    val paint = remember {
        android.graphics.Paint().apply {
            val primaryArgb = ContextCompat.getColor(context, R.color.yami_manga_primary)
            typeface = Typeface.DEFAULT_BOLD
            color = primaryArgb
            // textSize must be a Float
            textSize = with(density) { 14.sp.toPx() }
        }
    }
    var textBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    return this
        // 1) intercept taps
        .pointerInput(isError) {
            if (isError) {
                detectTapGestures { offset ->
                    textBounds
                        ?.takeIf { it.contains(offset) }
                        ?.let { onOpenInWebView() }
                }
            }
        }


        // 2) draw content + fallback, recording text position
         .drawWithContent {

             runCatching { drawContent() }
                 .onFailure {
                     isError = true
                     // background
                     drawRect(color = Color.DarkGray, size = size)

                     val iconTop = (screenHeightPx / 2f - iconPx) - 14f - marginPx
                     drawImage(
                         iconBitmap,
                         topLeft = Offset((size.width - iconPx) / 2f, iconTop)
                     )


                     // text
                     val lines = fallbackText.split("\n")
                     val lineH = paint.descent() - paint.ascent()
                     val blockH = lineH * lines.size


                     var y = iconTop + iconPx + marginPx - paint.ascent()

                     // We'll build a manual union of all the line‑rects
                     var uL = Float.MAX_VALUE
                     var uT = Float.MAX_VALUE
                     var uR = Float.MIN_VALUE
                     var uB = Float.MIN_VALUE

                     lines.forEach { line ->
                         val w = paint.measureText(line)
                         val x = (size.width - w) / 2f

                         // Draw this line
                         drawContext.canvas.nativeCanvas.drawText(line, x, y, paint)

                         // Compute this line's bounds
                         val top = y + paint.ascent()
                         val bottom = y + paint.descent()
                         val left = x
                         val right = x + w

                         // Expand our union bounds
                         uL = min(uL, left - marginPx)
                         uT = min(uT, top - marginPx)
                         uR = max(uR, right + marginPx)
                         uB = max(uB, bottom + marginPx)

                         // Advance to next line
                         y += lineH
                     }

                     // Store the full text‑block rect for hit‑testing
                     textBounds = Rect(uL, uT, uR, uB)

                 }
         }
}



