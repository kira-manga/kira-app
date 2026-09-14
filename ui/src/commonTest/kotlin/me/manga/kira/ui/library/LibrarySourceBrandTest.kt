package me.manga.kira.ui.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibrarySourceBrandTest {
    @Test
    fun namedMappingsKeepTheirOpaqueContrastPairs() {
        assertEquals(40, namedColors.size)
        namedColors.forEach { (api, argb) -> assertSourcePair(api, argb) }
    }

    @Test
    fun allFallbackSlotsAndCurrentCatalogApisKeepStableContrastPairs() {
        assertEquals(12, fallbackCases.size)
        assertEquals(12, fallbackCases.values.toSet().size)
        assertTrue(fallbackCases.keys.none { it in namedColors })
        fallbackCases.forEach { (api, argb) -> assertSourcePair(api, argb) }
        assertEquals(12, bundledApis.size)
        bundledApis.forEach { (api, argb) -> assertSourcePair(api, argb) }
    }

    @Test
    fun neighboringBoundaryColorsChooseTheStrongerText() {
        val expected =
            mapOf(
                0xFF000000 to Color.White,
                0xFF757575 to Color.White,
                0xFF767676 to Color.Black,
                0xFFFFFFFF to Color.Black,
            )
        expected.forEach { (argb, foreground) ->
            val brand = Color(argb)
            assertEquals(foreground, brand.libraryBrandContentColor(), "boundary $argb")
            assertContrastPair(brand, "boundary $argb")
        }
    }

    private fun assertSourcePair(
        api: String,
        argb: Long,
    ) {
        val brand = api.libraryBrandColor
        assertEquals(Color(argb), brand, api)
        assertContrastPair(brand, api)
    }

    private fun assertContrastPair(
        brand: Color,
        context: String,
    ) {
        val foreground = brand.libraryBrandContentColor()
        val expected =
            if (contrastRatio(brand, Color.Black) >= contrastRatio(brand, Color.White)) {
                Color.Black
            } else {
                Color.White
            }
        assertEquals(1f, brand.alpha, context)
        assertEquals(1f, foreground.alpha, context)
        assertEquals(expected, foreground, context)
        val contrast = contrastRatio(brand, foreground)
        assertTrue(contrast >= 4.5, "$context has unrounded contrast $contrast")
    }

    private fun contrastRatio(
        first: Color,
        second: Color,
    ): Double {
        val firstLuminance = wcagLuminance(first)
        val secondLuminance = wcagLuminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }

    // Independent Double, byte-based WCAG oracle; never calls Compose/production luminance.
    private fun wcagLuminance(color: Color): Double {
        fun linear(channel: Int): Double {
            val encoded = channel / 255.0
            return if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
        }

        val argb = color.toArgb()
        return 0.2126 * linear((argb ushr 16) and 0xFF) +
            0.7152 * linear((argb ushr 8) and 0xFF) +
            0.0722 * linear(argb and 0xFF)
    }

    private companion object {
        // Explicit legacy mappings, including all eight former white-on-brand failures.
        val namedColors =
            mapOf(
                "Lekmanga" to 0xFF2D75C0,
                "Team X" to 0xFFE73149,
                "Lavatoons" to 0xFFC0C0C0,
                "Azora" to 0xFF867C01,
                "Batoto" to 0xFF13667A,
                "Mangabuddy" to 0xFF0000A2,
                "Manhwatop" to 0xFFF68B20,
                "Comick" to 0xFF1D2836,
                "3asq" to 0xFFEE483A,
                "Dilar" to 0xFF3EC293,
                "Manhwaweb" to 0xFF00F8FF,
                "Taurus Fansub" to 0xFF077214,
                "Komik Cast" to 0xFF03A9F4,
                "Mangamello" to 0xFFFFCC00,
                "Komiku" to 0xFF00BCD4,
                "Manga Origine" to 0xFF3F51B5,
                "Raijinscan" to 0xFF91CEFF,
                "Manhastro" to 0xFF010A65,
                "Flowermanga" to 0xFFA600E8,
                "Desu" to 0xFFCE5A00,
                "Mangahub" to 0xFF00FDC9,
                "Mangapark" to 0xFF0123DC,
                "Promanga" to 0xFFE10711,
                "Mediocretoons" to 0xFF4E9BA9,
                "Inmanga" to 0xFF51F56E,
                "SwatManga" to 0xFF7B2CBF,
                "Olympusbiblioteca" to 0xFFD4A373,
                "Batcave" to 0xFF2B2D42,
                "Demonicscans" to 0xFF5C0029,
                "مانجا بارك" to 0xFF4CC9F0,
                "Mangapark-It" to 0xFF06FFA5,
                "Mangapark-Es" to 0xFFFF006E,
                "Mangapark-Es-La" to 0xFFFB8500,
                "Timenaight" to 0xFF560BAD,
                "Webtoontr" to 0xFF06D6A0,
                "Webtoonhatti" to 0xFFEF476F,
                "Mangaworld" to 0xFF118AB2,
                "Senkuro" to 0xFF9D4EDD,
                "Sussytoons" to 0xFFFF5A5F,
                "Zazamanga" to 0xFF3A86FF,
            )

        // Actual unmapped keys for slots 0..11, not a detached copy of the palette.
        // Mangamello Plus/Tapas have negative final Int hashes; 未知😀 pins UTF-16 surrogate use.
        val fallbackCases =
            mapOf(
                "unmapped-4" to 0xFF6750A4,
                "unmapped-3" to 0xFF3949AB,
                "unmapped-2" to 0xFF00695C,
                "unmapped-1" to 0xFF2E7D32,
                "unmapped-0" to 0xFF827717,
                "DilarV2" to 0xFFB05A00,
                "Mangamello Plus" to 0xFFAD1457,
                "unmapped-9" to 0xFF8E24AA,
                "unmapped-8" to 0xFF00838F,
                "未知😀" to 0xFF5D4037,
                "Tapas" to 0xFF455A64,
                "unmapped-5" to 0xFFC62828,
            )

        // Reviewed against revision 6 and BundledSourceCatalogPolicyTest at integration 5b073670.
        // Keep this small fixture in :ui; no composeApp/config-parser dependency is needed.
        val bundledApis =
            mapOf(
                "Azora" to 0xFF867C01,
                "Mangamello" to 0xFFFFCC00,
                "Mangamello Plus" to 0xFFAD1457,
                "SwatManga" to 0xFF7B2CBF,
                "Lekmanga" to 0xFF2D75C0,
                "Team X" to 0xFFE73149,
                "DilarV2" to 0xFFB05A00,
                "3asq" to 0xFFEE483A,
                "Demonicscans" to 0xFF5C0029,
                "Mangabuddy" to 0xFF0000A2,
                "Zazamanga" to 0xFF3A86FF,
                "Tapas" to 0xFF455A64,
            )
    }
}
