package me.manga.kira.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Yami color schemes — Material 3 ColorScheme tokens.
 *
 * **Redesign (2026-06, owner-approved full visual redesign).** The palette was deliberately changed
 * from the legacy Twitter-night-blue (`#15202B` / primary `#B0C6FF`) to the new **coral** brand
 * identity (primary `#FF5B6E`, warm near-black canvas in dark, soft-white in light) — see
 * `design/redesign/BRIEF.md`. This is an intentional appearance change for all users, replacing the
 * prior "parity / out-of-scope" posture. Brand gradients (coral→amber) are applied via `Brush` at the
 * component layer; the slot-based `primary` here is the flat accent used for tints/text/icons (darker
 * on light for WCAG-AA legibility, brighter on dark).
 *
 * Pure-black (AMOLED) mode is still applied at the [KiraTheme] layer by `ColorScheme.copy` — the base
 * palettes stay un-blacked so feature screens see the same tokens regardless of the OLED preference.
 * `error` stays a distinct red (not coral) so error states never read as brand accent.
 */
internal val KiraDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFF5B6E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4A2228),
    onPrimaryContainer = Color(0xFFFFD9DD),

    secondary = Color(0xFFFF8A5B),
    onSecondary = Color(0xFF3A1606),
    secondaryContainer = Color(0xFF4A2E1E),
    onSecondaryContainer = Color(0xFFFFDCC7),

    tertiary = Color(0xFFFFB3A0),
    onTertiary = Color(0xFF5A1A10),
    tertiaryContainer = Color(0xFF3A2A26),
    onTertiaryContainer = Color(0xFFFFD9CF),

    background = Color(0xFF0E1014),
    onBackground = Color(0xFFF3F4F7),
    surface = Color(0xFF161A23),
    onSurface = Color(0xFFF3F4F7),
    surfaceVariant = Color(0xFF2A3040),
    onSurfaceVariant = Color(0xFF9AA1AF),

    surfaceContainerLowest = Color(0xFF0B0D11),
    surfaceContainerLow = Color(0xFF13161E),
    surfaceContainer = Color(0xFF161A23),
    surfaceContainerHigh = Color(0xFF1C2130),
    surfaceContainerHighest = Color(0xFF232838),

    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFF2A3040),
    inverseOnSurface = Color(0xFF1B1B1F),
    inverseSurface = Color(0xFFF3F4F7),
    inversePrimary = Color(0xFFFF7A88),
    surfaceTint = Color(0xFFFF5B6E),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

internal val KiraLightColorScheme = lightColorScheme(
    // Darker coral on light so primary-as-text/icon clears WCAG-AA; brand fills use the coral→amber
    // Brush at the component layer regardless of this flat slot.
    primary = Color(0xFFE0394F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDADE),
    onPrimaryContainer = Color(0xFF5A1620),

    secondary = Color(0xFFC25A2E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBC9),
    onSecondaryContainer = Color(0xFF3A1606),

    tertiary = Color(0xFFB23A2A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD0),
    onTertiaryContainer = Color(0xFF3A0E06),

    background = Color(0xFFF5F6F9),
    onBackground = Color(0xFF15171C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF15171C),
    surfaceVariant = Color(0xFFE7EAF0),
    onSurfaceVariant = Color(0xFF5F6B7A),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F4F8),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0F2F6),
    surfaceContainerHighest = Color(0xFFE9ECF2),

    outline = Color(0xFFC2C8D2),
    outlineVariant = Color(0xFFDDE1E9),
    inverseOnSurface = Color(0xFFF2F2F6),
    inverseSurface = Color(0xFF2A2E38),
    inversePrimary = Color(0xFFFF8A9A),
    surfaceTint = Color(0xFFE0394F),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)
