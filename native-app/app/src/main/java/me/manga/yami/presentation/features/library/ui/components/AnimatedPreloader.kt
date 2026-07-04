package me.manga.yamiapk.presentation.features.library.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import me.manga.yamiapk.R

@Composable
fun AnimatedPreloader(
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    iconColor: Color
) {
    // 1. Load the composition
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.download_anim)
    )
    val progress by animateLottieCompositionAsState(
        composition,
        iterations = LottieConstants.IterateForever
    )
    val bgKeyPath = remember { arrayOf("**", "Layer 1", "Group 1", "Fill 1") }
    val iconKeyPath = remember { arrayOf("**", "Group 1", "Stroke 1") }

    val dynamicProperties = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR,
            value = backgroundColor.toArgb(),
            keyPath = bgKeyPath
        ),
        rememberLottieDynamicProperty(
            property = LottieProperty.STROKE_COLOR,
            value = iconColor.toArgb(),
            keyPath = iconKeyPath
        )
    )

    // 3. Render with both props
    LottieAnimation(
        composition        = composition,
        progress = { progress },  // Use lambda to defer reads
        modifier           = modifier,
        dynamicProperties = dynamicProperties

    )
}

@Composable
fun AnimatedNew(
    modifier: Modifier = Modifier,

) {
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.new_ani)
    )
    val progress by animateLottieCompositionAsState(
        composition,
        iterations = LottieConstants.IterateForever
    )
    LottieAnimation(
        composition        = composition,
        progress = { progress },  // Use lambda to defer reads
        modifier           = modifier,

    )

}

@Composable
fun AnimatedCompressing(
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    iconColor: Color
) {
    // 1. Load the composition
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(R.raw.filemoving)
    )
    val progress by animateLottieCompositionAsState(
        composition,
        iterations = LottieConstants.IterateForever
    )
    val bgKeyPath = remember { arrayOf("**", "Layer 1", "Group 1", "Fill 1") }
    val iconKeyPath = remember { arrayOf("**", "Group 1", "Stroke 1") }

    val dynamicProperties = rememberLottieDynamicProperties(
        rememberLottieDynamicProperty(
            property = LottieProperty.COLOR,
            value = backgroundColor.toArgb(),
            keyPath = bgKeyPath
        ),
        rememberLottieDynamicProperty(
            property = LottieProperty.STROKE_COLOR,
            value = iconColor.toArgb(),
            keyPath = iconKeyPath
        )
    )

    // 3. Render with both props
    LottieAnimation(
        composition        = composition,
        progress = { progress },  // Use lambda to defer reads
        modifier           = modifier,
        dynamicProperties = dynamicProperties

    )
}