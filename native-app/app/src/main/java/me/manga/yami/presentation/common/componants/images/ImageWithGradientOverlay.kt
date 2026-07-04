package me.manga.yamiapk.presentation.common.componants.images

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp


@Composable
fun ImageWithGradientOverlay(
    imageUrl: String,
    headerHeightDp: Dp,
    startColor : Color =MaterialTheme.colorScheme.background.copy(0.4F),
    endColor : Color =MaterialTheme.colorScheme.background,
    blur: Dp = 24.dp,
    parallaxOffset: Float
) {
    Box(
        modifier = Modifier
            .height(headerHeightDp)
            .fillMaxWidth()
            .graphicsLayer {
                translationY = -parallaxOffset
            }
    ) {

            BlurredImageCoil(
                url = imageUrl,
                modifier = Modifier.matchParentSize().padding(bottom = 4.dp),
                contentScale = ContentScale.FillBounds
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                startColor, // semi-transparent black at top
                                endColor   // fully transparent at bottom
                            ),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY
                        )
                    )
            )

        // Gradient overlay: adjust colors and stops as needed

    }
}