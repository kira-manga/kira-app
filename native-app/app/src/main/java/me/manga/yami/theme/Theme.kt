package me.manga.yamiapk.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp


private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF002D6E),
    primaryContainer = Color(0xFF00429B),
    onPrimaryContainer = Color(0xFFD7E2FF),

    secondary = Color(0xFFB0C6FF),
    onSecondary = Color(0xFF002D6E),
    secondaryContainer = Color(0xFF00429B),
    onSecondaryContainer = Color(0xFFD7E2FF),

    tertiary = Color(0xFFB8D0FF),
    onTertiary = Color(0xFF003063),
    tertiaryContainer = Color(0xFF2C2C2F),
    onTertiaryContainer = Color(0xFFD6E3FF),

//    background = Color(0xFF1B1B1F),
    background = Color(0xFF15202B),

    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF15202B),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC4C6D0),

    outline = Color(0xFF8E9099),
    inverseOnSurface = Color(0xFF1B1B1F),
    inverseSurface = Color(0xFFE3E2E6),
    inversePrimary = Color(0xFF0058CA),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0058CA),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E2FF),
    onPrimaryContainer = Color(0xFF001945),

    secondary = Color(0xFF0058CA),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E2FF),
    onSecondaryContainer = Color(0xFF001945),

    tertiary = Color(0xFF0061A3),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF2C2C2F),
    onTertiaryContainer = Color(0xFF001D36),

    background = Color(0xFFFEFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFEFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE3E2EC),
    onSurfaceVariant = Color(0xFF44464F),

    outline = Color(0xFF757780),
    inverseOnSurface = Color(0xFFF2F0F4),
    inverseSurface = Color(0xFF303034),
    inversePrimary = Color(0xFFB0C6FF),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFF93000A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)



val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(12.dp),
    large      = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(0.dp)
)

@Composable
fun YamiMangaTheme(
    darkTheme: Boolean ,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    pureBlack: Boolean = false,            // ← new

    content: @Composable () -> Unit
) {
    // 1) pick your base scheme exactly as you do today
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    // 2) if we’re in darkTheme AND pureBlack, override just background & surface
    val colorScheme = if (darkTheme && pureBlack) {
        baseScheme.copy(
            background = Color.Black,
            surfaceContainer =Color.Black,

        )
    } else {
        baseScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,

        content = content
    )
}