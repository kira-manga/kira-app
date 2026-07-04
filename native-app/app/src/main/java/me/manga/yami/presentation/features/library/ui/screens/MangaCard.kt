package me.manga.yamiapk.presentation.features.library.ui.screens

import AutoSubtitleText
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import me.manga.yamiapk.R
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.di.coli.getImageLoader
import me.manga.yamiapk.domain.model.MangaDisplayItem
import me.manga.yamiapk.presentation.features.library.ui.components.IconWithCount
import me.manga.yamiapk.sources_repositry.data.COLORS
import me.manga.yamiapk.sources_repositry.data.isDark
import me.manga.yamiapk.theme.YamiMangaTheme

// Replace the entire MangaCard composable with this version

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun MangaCard(
    itemsPerRow: Int,
    showButtons : Boolean,
    mangaDisplayItem: MangaDisplayItem,
    onMangaClick: (Long) -> Unit,
    showDetails :Boolean,
    showSource: Boolean,
    buildImageRequest: suspend (Context, String, String) -> ImageRequest,
    onToggleLike: (SavedMangaEntity) -> Unit,
    onToggleWatchLater: (SavedMangaEntity) -> Unit,
    onToggleDelete: (Long) -> Unit

) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val placeholderColor = ColorPainter(
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    )

    val errorColor = ColorPainter(
        MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
    )
    val badgeText =stringResource(R.string.source_badge_format, mangaDisplayItem.manga.api, mangaDisplayItem.manga.language)

    val imageRequest by produceState<ImageRequest?>(
        initialValue = null,
        key1 = mangaDisplayItem.manga.imageUrl,
        key2 = mangaDisplayItem.manga.api
    ) {
        value = buildImageRequest(
            context,
            mangaDisplayItem.manga.imageUrl,
            mangaDisplayItem.manga.api
        )
    }
    val badge = remember(mangaDisplayItem.manga.api, mangaDisplayItem.manga.language) {
        badgeText
    }
    val bgColor = remember(mangaDisplayItem.manga.api) { mangaDisplayItem.manga.api.COLORS }
    val textColor = remember(bgColor) {
        if (bgColor.isDark()) Color.White else Color.Black
    }

    val placeholderPainter = remember { placeholderColor }
    val errorPainter = remember { errorColor }

    Card(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth()
            .aspectRatio(1f / 1.5f)
            .widthIn(120.dp)
            .clickable { onMangaClick(mangaDisplayItem.manga.id) },
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            // Calculate adaptive sizes based on actual card width
            val cardWidth = maxWidth
            val buttonSize = remember(cardWidth) {
                // Scale button size based on card width: 15-20% of width
                (cardWidth * 0.22f).coerceIn(4.dp, 40.dp)
            }

            val iconSize = remember(buttonSize) {
                buttonSize * 0.55f  // Icon is 55% of button size
            }

            val spacerSize = remember(cardWidth) {
                (cardWidth * 0.01f).coerceIn(1.dp, 3.dp)
            }

            AsyncImage(
                model = imageRequest,
                contentDescription = stringResource(
                    R.string.manga_cover_description,
                    mangaDisplayItem.manga.title
                ),
                imageLoader = getImageLoader(),

                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                placeholder = placeholderPainter,
                error = errorPainter
            )

            // Top area: badge at top-start, buttons below it aligned to top-end
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(4.dp)
            ) {
                // Row for badge (keeps it at start)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Source Badge
                    if (showSource) {
                        Card(
                            modifier = Modifier.padding(0.dp),
                            shape = RoundedCornerShape(4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = bgColor.copy(alpha = 0.8f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            AutoSubtitleText(
                                text = badge,
                                color = textColor,
                                fontSize = 8.sp,
                                maxSize = 10.sp,
                                minSize = 2.sp,
                                maxLines = 1,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                }

                // Buttons row placed below badge, aligned to end
                if (showButtons) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.padding(end = 4.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(buttonSize)
                                    .background(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable {
                                        coroutineScope.launch {
                                            onToggleWatchLater(mangaDisplayItem.manga)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (mangaDisplayItem.manga.isWatchingNow) Icons.Filled.WatchLater else Icons.Filled.Schedule,
                                    contentDescription = "Watch Later",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(iconSize)
                                )
                            }

                            Spacer(modifier = Modifier.padding(spacerSize))

                            Box(
                                modifier = Modifier
                                    .size(buttonSize)
                                    .background(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable {
                                        coroutineScope.launch {
                                            onToggleLike(mangaDisplayItem.manga)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (mangaDisplayItem.manga.isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = Color.Red,
                                    modifier = Modifier.size(iconSize)
                                )
                            }

                            Spacer(modifier = Modifier.padding(spacerSize))

                            Box(
                                modifier = Modifier
                                    .size(buttonSize)
                                    .background(
                                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable {
                                        coroutineScope.launch {
                                            onToggleDelete(mangaDisplayItem.manga.id)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = Color.White,
                                    modifier = Modifier.size(iconSize)
                                )
                            }
                        }
                    }
                }
            }

            // overlay gradient + info
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
                    .padding(8.dp)
            ) {
                AutoSubtitleText(
                    text = mangaDisplayItem.manga.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    fontSize = 14.sp,
                    maxSize = 16.sp,
                    minSize = 2.sp,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                if (showDetails){
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconWithCount(
                            icon = Icons.Outlined.List, count = mangaDisplayItem.totalChapters,
                            modifier = Modifier.weight(1f)
                        )
                        IconWithCount(
                            icon = Icons.Outlined.RemoveRedEye, count = mangaDisplayItem.readCount,
                            modifier = Modifier.weight(1f)
                        )
                        IconWithCount(
                            icon = Icons.Outlined.Download, count = mangaDisplayItem.downloadedCount,
                            modifier = Modifier.weight(1f)
                        )
                        IconWithCount(
                            icon = Icons.Outlined.BookmarkAdd, count = mangaDisplayItem.bookmarkedCount,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

private val sampleManga = SavedMangaEntity(
    id = 1L,
    api = "MangaDex",
    language = "EN",
    url = "https://example.com/manga/1",
    imageUrl = "https://via.placeholder.com/300x450.png?text=Cover",
    title = "Sample Manga Title That Is Quite Long So We Can Test Wrapping",
    description = "A short sample description",
    status = "Ongoing",
    rating = "4.5",
    genres = listOf("Action", "Adventure"),
    savedTimestamp = System.currentTimeMillis(),
    lastOpenTimestamp = System.currentTimeMillis(),
    isLiked = true,
    isWatchingNow = false
)

private val sampleItem = MangaDisplayItem(
    manga = sampleManga,
    totalChapters = 128,
    readCount = 42,
    downloadedCount = 12,
    bookmarkedCount = 3
)

// Simple suspend image request builder for preview — uses a placeholder URL above.
// In your real app you keep your real builder.
private val previewBuildImageRequest: suspend (Context, String, String) -> ImageRequest =
    { ctx, url, api ->
        ImageRequest.Builder(ctx)
            .data(url)
            .build()
    }

@Preview(
    name = "MangaCard — Light (with buttons, details)",
    showBackground = true,
    widthDp = 200,
    heightDp = 320
)
@Composable
fun MangaCardPreview_Light_WithButtons() {
    YamiMangaTheme(true) {
        MangaCard(
            itemsPerRow = 2,
            showButtons = true,
            mangaDisplayItem = sampleItem,
            onMangaClick = {},
            showDetails = true,
            showSource = true,
            buildImageRequest = previewBuildImageRequest,
            onToggleLike = {},
            onToggleWatchLater = {},
            onToggleDelete = {}
        )
    }
}

@Preview(
    name = "MangaCard — Dark (no buttons, details hidden)",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 200,
    heightDp = 320
)
@Composable
fun MangaCardPreview_Dark_NoButtons() {
    YamiMangaTheme(true) {

        MangaCard(
            itemsPerRow = 3,
            showButtons = false,
            mangaDisplayItem = sampleItem.copy(
                manga = sampleManga.copy(isLiked = false, isWatchingNow = true)
            ),
            onMangaClick = {},
            showDetails = false,
            showSource = true,
            buildImageRequest = previewBuildImageRequest,
            onToggleLike = {},
            onToggleWatchLater = {},
            onToggleDelete = {}
        )
    }
}