package me.manga.kira.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

/**
 * Shared manga-cover thumbnail for the Yami design system (Phase 11.ui.UP-4).
 *
 * Consolidates the cover-rendering boilerplate that the grid/list cards had each duplicated
 * (aspect-ratio + rounded clip + tinted placeholder background + Coil [AsyncImage]) and adds the
 * two parity affordances the rework's bare `AsyncImage` was missing versus the native app:
 *
 *  1. **Bottom gradient scrim** (opt-in via [scrim]) — a full-height `Transparent -> Black @ 0.8`
 *     `verticalGradient`, value-for-value the native `MangaCard` bottom info overlay. Restores the
 *     native card's polished cover treatment and lifts the contrast of anything the parent overlays
 *     on the cover (action-row icons, badges, title).
 *  2. **Native placeholder / error fills** — matches the native `MangaCard` Coil treatment
 *     value-for-value: during load a flat `onSurface @ 0.12` placeholder fill shows; on a terminal
 *     Coil *failure* a flat `error @ 0.24` fill replaces it (NO broken-image glyph — native shows a
 *     solid error-tinted fill). Deliberately **no loading spinner**: a grid scroll recycles 50+
 *     cells and 50 simultaneous spinners read as noise; the calm "tinted box fills in when the image
 *     lands" is the loading signal. The error fill only appears on a *terminal* error, not during
 *     loading.
 *
 * Backed by Coil's singleton [coil3.ImageLoader] (AVIF decoder, OkHttp fetcher, the high-quality
 * Skia decoder, source-header interceptor) — same inheritances as the other rework cover sites.
 * Uses [AsyncImage] (not `SubcomposeAsyncImage`) so a grid of these adds no per-cell subcomposition.
 *
 * A blank [coverUrl] renders only the [placeholderTint] fill (no [AsyncImage], no error fill) — a
 * missing URL is "no cover provided", not a load failure.
 *
 * @param scrim when true, draws the bottom gradient scrim over the cover.
 * @param placeholderTint background tint shown through while loading / when the URL is blank.
 *   Defaults to `onSurface` (drawn at 0.12 alpha) to match native's `ColorPainter` placeholder;
 *   callers may override (e.g. the Library card tints it primary while selected).
 * @param contentScale how the cover fills its box — defaults to [ContentScale.Crop]. The Home /
 *   Search grid poster cells pass [ContentScale.FillBounds] to match the legacy `SearchItems`
 *   cell, which stretched the cover to fill a fixed-height box (GAP-HOME-10 / GAP-SRCH-02).
 */
@Composable
fun KiraCoverImage(
    coverUrl: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    aspectRatio: Float? = 0.7f,
    // Redesign 2026-06: on-spec default cover radius (was 6.dp). All current callers override with
    // 14-20.dp; this keeps the fallback on-language if a future caller doesn't.
    shape: Shape = RoundedCornerShape(14.dp),
    scrim: Boolean = false,
    placeholderTint: Color = MaterialTheme.colorScheme.onSurface,
    contentScale: ContentScale = ContentScale.Crop,
    // #32: optional pre-built Coil model (e.g. a source-aware ImageRequest carrying per-source
    // Cloudflare auth headers, built by the :composeApp route adapter). When null, the plain
    // [coverUrl] string is used — preserving every existing call site unchanged.
    model: Any? = null,
) {
    // Prefer the caller-supplied model (ImageRequest); else fall back to the plain URL when present.
    val effectiveModel: Any? = model ?: coverUrl.takeIf { it.isNotBlank() }
    // Keyed on both so a recycled grid cell bound to a new manga / model resets the error flag.
    var isError by remember(coverUrl, model) { mutableStateOf(false) }
    Box(
        // A null [aspectRatio] lets the caller size the box itself (e.g. a fixed 250.dp poster cell —
        // GAP-HOME-10 / GAP-SRCH-02); otherwise the box derives its height from the width via ratio.
        // The base fill is the native `onSurface @ 0.12` placeholder (overridden by `placeholderTint`).
        modifier = modifier
            .fillMaxWidth()
            .then(if (aspectRatio != null) Modifier.aspectRatio(aspectRatio) else Modifier)
            .clip(shape)
            .background(placeholderTint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        if (effectiveModel != null) {
            AsyncImage(
                model = effectiveModel,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onState = { state -> isError = state is AsyncImagePainter.State.Error },
            )
        }
        if (isError) {
            // Native `MangaCard` error treatment: a flat `error @ 0.24` fill over the cell (no glyph).
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.24f)),
            )
        }
        if (scrim) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        // Matches the native MangaCard bottom info overlay value-for-value: a
                        // full-height verticalGradient(Transparent -> Black @ 0.8). Both current
                        // callers (Library card, Featured carousel) rely on this built-in scrim as
                        // the title / action-icon backdrop, so the end alpha is the native 0.8
                        // (was a weaker 0.45 with a 0.55 start stop).
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.8f),
                        ),
                    ),
            )
        }
    }
}
