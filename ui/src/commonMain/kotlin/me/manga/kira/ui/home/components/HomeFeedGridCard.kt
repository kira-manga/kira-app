package me.manga.kira.ui.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.ui.components.KiraCoverImage

/**
 * Home-feed grid poster (Home redesign 2026-06).
 *
 * A rounded (16.dp) poster with a bottom scrim, the title overlaid in white, and — when the feed item
 * carries a rating — a small "★ n" chip top-left (real data only, never fabricated). Replaces the
 * prior flat 250.dp cell with the new poster language. Public signature unchanged.
 */
@Composable
fun HomeFeedGridCard(
    item: HomeFeedItem,
    onClick: (HomeFeedItem) -> Unit,
    modifier: Modifier = Modifier,
    coverModel: ((HomeFeedItem) -> Any?)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.68f)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick(item) },
    ) {
        KiraCoverImage(
            coverUrl = item.coverUrl,
            model = coverModel?.invoke(item),
            contentDescription = item.title,
            aspectRatio = null,
            shape = RoundedCornerShape(16.dp),
            contentScale = ContentScale.Crop,
            scrim = true,
            modifier = Modifier.fillMaxSize(),
        )
        item.rating?.takeIf { it > 0 }?.let { rating ->
            Text(
                text = "★ $rating",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Text(
            text = item.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
        )
    }
}
