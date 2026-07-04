package me.manga.kira.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Yami brand accents (redesign 2026-06). The vivid coral→amber gradient is the single brand signal —
 * used for the selected source pill, the active bottom-nav tab, primary CTAs and the favorite toggle.
 * Kept as fixed literal colors (not theme-mapped) so the brand reads identically vivid in light, dark
 * and pure-black, with white content on top. Public so the `:composeApp` nav/chrome can reuse it.
 */
object KiraBrand {
    val Coral: Color = Color(0xFFFF5B6E)
    val Amber: Color = Color(0xFFFF8A5B)

    /** Primary brand gradient (coral → amber). */
    val Gradient: Brush = Brush.linearGradient(listOf(Coral, Amber))
}
