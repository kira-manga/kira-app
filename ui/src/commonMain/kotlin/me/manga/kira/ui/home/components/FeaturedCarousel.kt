package me.manga.kira.ui.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.feedKey
import me.manga.kira.ui.components.KiraCoverImage

/**
 * Horizontal "Popular now" hero carousel for the Home list (Home redesign 2026-06).
 *
 * Cinematic poster cards (158×212, 20.dp corners) with a bottom scrim and the title overlaid in
 * white — a premium upgrade from the prior plain cover strip. The signature one-item-per-fling snap
 * plus the continuous per-position scale (cards ease toward the edges) are preserved via
 * [rememberSnapFlingBehavior] + [itemCenterScale]. Public signature unchanged.
 *
 * Per-cover image flows through the shared [KiraCoverImage]; [coverModel] lets the route adapter pass
 * a source-aware request (falls back to [FeaturedManga.coverUrl]). [FeaturedManga] carries no rating/
 * genre, so the card shows the title only — no fabricated metadata.
 */
@Composable
fun FeaturedCarousel(
    items: List<FeaturedManga>,
    onItemClick: (FeaturedManga) -> Unit,
    modifier: Modifier = Modifier,
    coverModel: ((FeaturedManga) -> Any?)? = null,
) {
    if (items.isEmpty()) return
    BoxWithConstraints(modifier = modifier) {
        // Responsive poster sizing: ~42% of the viewport width (clamped) so the hero scales across
        // small and large phones instead of using one fixed size. Height keeps the ~3:4 poster ratio.
        val cardWidth = (maxWidth * 0.42f).coerceIn(150.dp, 188.dp)
        val cardHeight = cardWidth * 1.34f
        val listState = rememberLazyListState()
        val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
        LazyRow(
            state = listState,
            flingBehavior = flingBehavior,
            modifier = Modifier.height(cardHeight),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.feedKey() }) { index, item ->
                Box(
                    modifier = Modifier
                        .width(cardWidth)
                        .height(cardHeight)
                        .graphicsLayer {
                            val s = listState.itemCenterScale(index)
                            scaleX = s
                            scaleY = s
                        }
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onItemClick(item) },
                ) {
                    KiraCoverImage(
                        coverUrl = item.coverUrl,
                        model = coverModel?.invoke(item),
                        contentDescription = item.title,
                        aspectRatio = null,
                        shape = RoundedCornerShape(20.dp),
                        scrim = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * Continuous per-position scale for the item at [index] — dead-centre scales to [MAX_SCALE] (1f),
 * a full viewport-half away (or not laid out) scales to [MIN_SCALE], linear between. Mirrors the
 * legacy carousel's `carouselItemDrawInfo.size / maxSize` parallax without the experimental M3 API.
 */
private fun LazyListState.itemCenterScale(index: Int): Float {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return MIN_SCALE
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    val itemCenter = item.offset + item.size / 2f
    val half = (info.viewportEndOffset - info.viewportStartOffset) / 2f
    if (half <= 0f) return MAX_SCALE
    val distanceFraction = (abs(itemCenter - viewportCenter) / half).coerceIn(0f, 1f)
    return MAX_SCALE - distanceFraction * (MAX_SCALE - MIN_SCALE)
}

private const val MAX_SCALE = 1f
private const val MIN_SCALE = 0.92f
