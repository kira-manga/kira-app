package me.manga.kira.ui.common

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import org.jetbrains.compose.resources.DrawableResource

/**
 * How a source's brand icon resolves for rendering (MangaSource decoupling, 2026-07). Derived at
 * the app root from the validated source-config document — packaged drawable first, remote HTTPS
 * URL second, [None] last (call sites render their own deterministic fallback: the Sources
 * medallion draws displayName initials, the Home pill falls back to its neutral glyph).
 *
 * Icons are render-only: a missing key, a dead URL, or an offline device only ever degrades the
 * glyph, never the source's discovery/enabling/routing.
 */
sealed interface SourceIconResolution {
    data class Packaged(
        val drawable: DrawableResource,
    ) : SourceIconResolution

    data class Remote(
        val url: String,
    ) : SourceIconResolution

    data object None : SourceIconResolution
}

/**
 * Resolver from a source `api` to its brand-icon resolution. Defaults to [SourceIconResolution.None];
 * the app root provides the real resolver (config document + `SourceIconRegistry` — both live in
 * :composeApp, out of :ui's module reach), so :ui renders source icons without depending on
 * :composeApp. Replaces the former api→`DrawableResource?` seam, adding the remote-URL tier.
 */
val LocalSourceIconResolver =
    staticCompositionLocalOf<(api: String) -> SourceIconResolution> { { SourceIconResolution.None } }

/**
 * Remote brand icon with a graceful in-place fallback: [fallback] renders until (and unless) the
 * image actually loads, so a slow network shows the deterministic avatar, a failed/offline load
 * keeps it, and a successful load replaces it. Uses the app's singleton Coil loader (memory + disk
 * cache — no re-download per composition; the ~icon-sized bounds keep decodes trivial).
 */
@Composable
fun RemoteSourceIcon(
    url: String,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
    fallback: @Composable () -> Unit,
) {
    val painter = rememberAsyncImagePainter(model = url)
    val state by painter.state.collectAsState()
    if (state is AsyncImagePainter.State.Success) {
        Image(
            painter = painter,
            contentDescription = null, // decorative — the row/pill text names the source
            modifier = modifier,
            colorFilter = colorFilter,
        )
    } else {
        fallback()
    }
}

/**
 * Deterministic per-source fallback color: FNV-1a over [stableKey] (the api string) into a fixed
 * palette — stable across launches, processes, and platforms by construction (never
 * `hashCode()`, whose value is process-dependent on some targets). Used for the library badge's
 * unmapped-source branch and available to any avatar-style fallback.
 */
fun sourceFallbackColor(stableKey: String): Color {
    var hash = FNV_OFFSET_BASIS
    for (ch in stableKey) {
        hash = hash xor ch.code
        hash *= FNV_PRIME
    }
    val index = ((hash % FALLBACK_PALETTE.size) + FALLBACK_PALETTE.size) % FALLBACK_PALETTE.size
    return FALLBACK_PALETTE[index]
}

/** FNV-1a 32-bit offset basis (0x811C9DC5) as a signed Int literal — `const` forbids `.toInt()`. */
private const val FNV_OFFSET_BASIS = -2128831035

/** FNV-1a 32-bit prime (0x01000193). */
private const val FNV_PRIME = 16777619

/** Mid-saturation hues that keep white badge text readable (all pass the isDark contrast check). */
private val FALLBACK_PALETTE =
    listOf(
        Color(0xFF6750A4),
        Color(0xFF3949AB),
        Color(0xFF00695C),
        Color(0xFF2E7D32),
        Color(0xFF827717),
        Color(0xFFB05A00),
        Color(0xFFAD1457),
        Color(0xFF8E24AA),
        Color(0xFF00838F),
        Color(0xFF5D4037),
        Color(0xFF455A64),
        Color(0xFFC62828),
    )
