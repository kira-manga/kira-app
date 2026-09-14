package me.manga.kira.ui.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import me.manga.kira.ui.common.sourceFallbackColor

/**
 * Source brand-color map for the Library card source badge (GAP-LIB-17).
 *
 * Preserves the legacy named-source brand colors without importing the source implementation
 * into `:ui`. These opaque sRGB literals are shared by Android, iOS and Desktop rendering.
 *
 * Keyed on the source `api` string (e.g. "Lekmanga", "Team X") exactly as the legacy
 * `MangaSource.{X}.API` literals resolve. Unknown sources use the shared deterministic per-api
 * fallback palette, also opaque; neither the source identity nor its fallback hash is changed here.
 */
internal val String.libraryBrandColor: Color
    get() =
        when (this) {
            "Lekmanga" -> Color(0xFF2D75C0)
            "Team X" -> Color(0xFFE73149)
            "Lavatoons" -> Color(0xFFC0C0C0)
            "Azora" -> Color(0xFF867C01)
            "Batoto" -> Color(0xFF13667A)
            "Mangabuddy" -> Color(0xFF0000A2)
            "Manhwatop" -> Color(0xFFF68B20)
            "Comick" -> Color(0xFF1D2836)
            "3asq" -> Color(0xFFEE483A)
            "Dilar" -> Color(0xFF3EC293)
            "Manhwaweb" -> Color(0xFF00F8FF)
            "Taurus Fansub" -> Color(0xFF077214)
            "Komik Cast" -> Color(0xFF03A9F4)
            "Mangamello" -> Color(0xFFFFCC00)
            "Komiku" -> Color(0xFF00BCD4)
            "Manga Origine" -> Color(0xFF3F51B5)
            "Raijinscan" -> Color(0xFF91CEFF)
            "Manhastro" -> Color(0xFF010A65)
            "Flowermanga" -> Color(0xFFA600E8)
            "Desu" -> Color(0xFFCE5A00)
            "Mangahub" -> Color(0xFF00FDC9)
            "Mangapark" -> Color(0xFF0123DC)
            "Promanga" -> Color(0xFFE10711)
            "Mediocretoons" -> Color(0xFF4E9BA9)
            "Inmanga" -> Color(0xFF51F56E)
            "SwatManga" -> Color(0xFF7B2CBF)
            "Olympusbiblioteca" -> Color(0xFFD4A373)
            "Batcave" -> Color(0xFF2B2D42)
            "Demonicscans" -> Color(0xFF5C0029)
            "مانجا بارك" -> Color(0xFF4CC9F0)
            "Mangapark-It" -> Color(0xFF06FFA5)
            "Mangapark-Es" -> Color(0xFFFF006E)
            "Mangapark-Es-La" -> Color(0xFFFB8500)
            "Timenaight" -> Color(0xFF560BAD)
            "Webtoontr" -> Color(0xFF06D6A0)
            "Webtoonhatti" -> Color(0xFFEF476F)
            "Mangaworld" -> Color(0xFF118AB2)
            "Senkuro" -> Color(0xFF9D4EDD)
            "Sussytoons" -> Color(0xFFFF5A5F)
            "Zazamanga" -> Color(0xFF3A86FF)
            // MangaSource decoupling (2026-07): a source without a hand-tuned brand hex (e.g. a new
            // config-only source added via JSON alone) gets a deterministic per-api palette color
            // instead of the old opaque black — stable across launches, no Kotlin edit required.
            else -> sourceFallbackColor(this)
        }

/**
 * Chooses the stronger WCAG black/white contrast pair for an opaque [libraryBrandColor].
 * The two ratios have product 21, so the better choice is always above 4.5:1. The badge must
 * keep its backing opaque: luminance alone does not account for a translucent cover blend.
 */
internal fun Color.libraryBrandContentColor(): Color {
    val relativeLuminance = luminance().toDouble()
    val blackContrast = (relativeLuminance + WCAG_LUMINANCE_OFFSET) / WCAG_LUMINANCE_OFFSET
    val whiteContrast = WCAG_WHITE_LUMINANCE_WITH_OFFSET / (relativeLuminance + WCAG_LUMINANCE_OFFSET)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

private const val WCAG_LUMINANCE_OFFSET: Double = 0.05
private const val WCAG_WHITE_LUMINANCE_WITH_OFFSET: Double = 1.05
