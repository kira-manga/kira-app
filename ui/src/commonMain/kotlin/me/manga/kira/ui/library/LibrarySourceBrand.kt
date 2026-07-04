package me.manga.kira.ui.library

import androidx.compose.ui.graphics.Color

/**
 * Source brand-color map for the Library card source badge (GAP-LIB-17).
 *
 * Mirrors the legacy `me.manga.kira.sources_repositry.data.String.COLORS` extension verbatim
 * (the per-source brand hex + the `else -> black` fallthrough). It is duplicated here rather than
 * imported because the original lives in `:shared`, and `:ui` is forbidden from depending on
 * `:shared` (contract §4 / §6 — `:ui` imports only `:presentation`). `androidx.compose.ui.graphics.Color`
 * is multiplatform, so the verbatim hex literals compile unchanged across Android / iOS / Desktop.
 *
 * Keyed on the source `api` string (e.g. "Lekmanga", "Team X") exactly as the legacy
 * `MangaSource.{X}.API` literals resolve. Unknown / unmapped sources fall through to opaque black —
 * identical graceful-degradation to the legacy `else` branch.
 */
internal val String.libraryBrandColor: Color
    get() = when (this) {
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
        else -> Color(0xFF000000)
    }

/**
 * BT.601 luminance test — mirrors the legacy `Color.isDark()` helper in `MangaSource.kt` verbatim
 * (the CCIR-601 luma weights `0.299 / 0.587 / 0.114`, mid-gray 0.5 threshold). Drives the
 * contrast-aware badge text color (white on dark brand colors, black on light).
 */
internal fun Color.isDarkBrand(): Boolean {
    val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
    return luminance < 0.5
}
