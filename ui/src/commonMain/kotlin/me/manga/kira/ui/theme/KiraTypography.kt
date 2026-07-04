package me.manga.kira.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.gellix_bold
import me.manga.kira.ui.generated.resources.gellix_regular
import me.manga.kira.ui.generated.resources.gellix_semibold
import org.jetbrains.compose.resources.Font

/**
 * Gellix font family + Material 3 [Typography] for the Yami design system (Phase 11.ui.UP-1).
 *
 * compose-resources [Font] is a `@Composable`, so the family and type scale are produced by a
 * composable factory rather than a top-level `val` (the legacy Android `Type.kt` used the
 * non-composable resource `Font` overload). Both the design-system [KiraTheme] and the applied
 * `composeApp` `KiraMangaTheme` call [kiraTypography] — one source of truth, no duplication.
 *
 * **Parity note (deliberate):** the legacy `Type.kt` overrode ONLY `bodyLarge`, `titleMedium`,
 * and `titleSmall` with Gellix and left every other slot on the Material 3 default. That is
 * reproduced here byte-for-byte. Gellix is a Latin-only face; keeping the system default on the
 * remaining slots preserves glyph fallback for the app's primary Arabic locale (and other
 * non-Latin scripts). Blanket-Gellix would regress Arabic rendering, so it is intentionally avoided.
 */
@Composable
private fun gellixFontFamily(): FontFamily = FontFamily(
    Font(Res.font.gellix_regular, weight = FontWeight.Normal),
    Font(Res.font.gellix_semibold, weight = FontWeight.Medium),
    Font(Res.font.gellix_bold, weight = FontWeight.Bold),
)

/**
 * Material 3 [Typography] with the legacy Gellix overrides on the three legacy slots; all other
 * slots inherit the Material 3 defaults (see the parity note above).
 */
@Composable
fun kiraTypography(): Typography {
    val gellix = gellixFontFamily()
    return Typography(
        bodyLarge = TextStyle(fontFamily = gellix, fontWeight = FontWeight.Bold, fontSize = 16.sp),
        titleMedium = TextStyle(fontFamily = gellix, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        titleSmall = TextStyle(fontFamily = gellix, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    )
}
