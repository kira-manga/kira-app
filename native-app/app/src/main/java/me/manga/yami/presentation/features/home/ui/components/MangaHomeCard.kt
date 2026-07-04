package me.manga.yamiapk.presentation.features.home.ui.components


import AutoSubtitleText
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.manga.yamiapk.R
import me.manga.yamiapk.di.coli.getImageLoader
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.sources_repositry.data.COLORS
import me.manga.yamiapk.sources_repositry.data.isDark
import me.manga.yamiapk.theme.GellixFontFamily

@Composable
fun MangaHomeCard(
    item: MangaItem,
    isSaved: Boolean,
    showSource: Boolean = true,
    onMangaClick: (String, String, String, Boolean) -> Unit,
    onChapterClick: (ChapterItem, MangaItem, List<ChapterItem>) -> Unit,
    onSaveClick: (MangaItem) -> Unit,
    buildImageRequest: suspend (Context, String, String) -> ImageRequest
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(false) }

    // Observe external isSaved to clear loading
    LaunchedEffect(isSaved) {
        if (isLoading && isSaved) {
            isLoading = false
        }
    }

    val placeholderColor = ColorPainter(
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    )

    val errorColor = ColorPainter(
        MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
    )

    val imageRequest by produceState<ImageRequest?>(
        initialValue = null,
        key1 = item.imageUrl,
        key2 = item.api
    ) {
        value = buildImageRequest(context, item.imageUrl, item.api)
    }

    val badgeText = remember(item.api, item.language) {
        "${item.api} - ${item.language}"
    }
    val bgColor = remember(item.api) { item.api.COLORS }
    val textColor = remember(bgColor) {
        if (bgColor.isDark()) Color.White else Color.Black
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(12.dp),
                clip = false,
                ambientColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                spotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            )
            .clickable { onMangaClick(item.url, item.api, item.title, isSaved) },
        elevation = CardDefaults.cardElevation(
            defaultElevation = 8.dp,
            pressedElevation = 12.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Cover Image
            Box(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .width(90.dp)
                    .height(130.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    AsyncImage(
                        model = imageRequest,
                        imageLoader = getImageLoader(),
                        contentDescription = stringResource(
                            R.string.manga_cover_description,
                            item.title
                        ),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = placeholderColor,
                        error = errorColor
                    )
                }

                // Source Badge
                if (showSource) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = bgColor.copy(alpha = 0.9f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        AutoSubtitleText(
                            text = badgeText,
                            color = textColor,
                            fontSize = 8.sp,
                            maxSize = 10.sp,
                            minSize = 6.sp,
                            maxLines = 1,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Content Column
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Title
                AutoSubtitleText(
                    text = item.title,
//                    fontFamily = GellixFontFamily,
                    fontSize = 16.sp,
                    maxSize = 18.sp,
                    minSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Chapters and Save Button Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Chapters Column
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        item.chapters?.take(3)?.forEach { chapter ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable {
                                        onChapterClick(
                                            chapter,
                                            item,
                                            item.chapters ?: listOf()
                                        )
                                    },
                                shape = RoundedCornerShape(6.dp),
                                elevation = CardDefaults.cardElevation(
                                    defaultElevation = 2.dp,
                                    pressedElevation = 4.dp
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                AutoSubtitleText(
                                    text = "Chapter ${chapter.number}",
//                                    fontFamily = GellixFontFamily,
                                    fontSize = 11.sp,
                                    maxSize = 12.sp,
                                    minSize = 8.sp,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Save Button
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = if (isSaved)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(50)
                            )
                            .clickable(enabled = !isLoading) {
                                if (!isSaved) {
                                    isLoading = true
                                }
                                onSaveClick(item)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (isSaved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = if (isSaved) "Saved" else "Save",
                                tint = if (isSaved) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}