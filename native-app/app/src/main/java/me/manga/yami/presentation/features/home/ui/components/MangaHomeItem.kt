package me.manga.yamiapk.presentation.features.home.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import me.manga.yamiapk.theme.GellixFontFamily


@Composable
fun MangaHomeItem(
    item: MangaItem,
    isSaved: Boolean,
    onMangaClick: (String, String, String,Boolean) -> Unit,
    onChapterClick: (ChapterItem, MangaItem, List<ChapterItem>) -> Unit,
    onSaveClick: (MangaItem) -> Unit,
    buildImageRequest:(Context, String, String) -> ImageRequest

) {
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(false) }
    // observe external isSaved to clear loading
    LaunchedEffect(isSaved) {
        if (isLoading && isSaved) {
            isLoading = false
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(8.dp),
                clip = false,
                ambientColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                spotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)

            )
            .clickable { onMangaClick(item.url,item.api,item.title,isSaved) },
        elevation = CardDefaults.cardElevation(
            defaultElevation = 8.dp,
            pressedElevation = 12.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.background,    // cardBackgroundColor
            contentColor = MaterialTheme.colorScheme.onBackground
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Card(
                modifier = Modifier.align(Alignment.CenterVertically)
                    .size(width = 100.dp, height = 130.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
            ) {
                AsyncImage(
                    model = buildImageRequest(
                        context,
                        item.imageUrl,
                        item.api,
                    ),
                    imageLoader = getImageLoader(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Text(
                    text = item.title,
                    fontFamily = GellixFontFamily, // Assuming this is your gellix_bold font
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground, // Matching ?attr/colorOnBackground
                    fontWeight = FontWeight.Bold, // Because you set android:textStyle="bold"
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start, // textAlignment="textStart"

                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // First two chapters
                    Column(
                        modifier = Modifier
                            .weight(4f)               // ← constrain its width
                            .padding(end = 8.dp, bottom = 8.dp)      // ← keep a gap from the icon
                    ) {

                        item.chapters?.take(3)?.forEachIndexed { index, chapter ->
                            Card(
                                modifier = Modifier
                                    .padding(4.dp)

                                    .background(Color.Transparent)
                                    .clickable {

                                        onChapterClick(chapter, item,item.chapters?: listOf()) },
                                shape = RoundedCornerShape(6.dp),                                // ← corner radius

                                elevation = CardDefaults.cardElevation(
                                    defaultElevation = 8.dp,
                                    pressedElevation = 12.dp
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,    // cardBackgroundColor
                                    contentColor = MaterialTheme.colorScheme.onBackground
                                )
                            ) {
                                Text(
                                    text = "${chapter.number}",
                                    fontFamily = GellixFontFamily,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    modifier = Modifier.padding(4.dp),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        enabled = !isLoading,
                        onClick = {
                            if (!isSaved) {
                                isLoading = true
                            }
                            onSaveClick(item)
                        }
                    ) {
                        if (isLoading) {
                            // spinner sized like icon
                            CircularProgressIndicator(
                                modifier = Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.inversePrimary

                            )
                        } else {
                            val iconRes = if (isSaved) R.drawable.ic_bookmark_bold else R.drawable.ic_bookmark
                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = if (isSaved) "Saved" else "Save"
                            )
                        }
                    }

                }
            }
        }
    }
}