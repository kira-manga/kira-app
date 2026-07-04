package me.manga.yamiapk.presentation.features.history.ui.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.manga.yamiapk.R
import me.manga.yamiapk.data.local.entity.HistoryItemD
import me.manga.yamiapk.di.coli.getImageLoader
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit


@Composable
fun HistoryItem(
    item: HistoryItemD,
    onMangaClick: () -> Unit,
    onChapterClick: () -> Unit,
    onDeleteClick: () -> Unit,
    buildImageRequest: (Context, String, String) -> ImageRequest,

    modifier: Modifier = Modifier
) {

    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onChapterClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                modifier = Modifier
                    .width(80.dp)
                    .height(120.dp)
                    .clickable(onClick = onMangaClick),
                shape = MaterialTheme.shapes.small
            ) {
                AsyncImage(
                    model = buildImageRequest(
                        context,
                        item.mangaImageUrl,
                        item.api,
                    ),
                    imageLoader = getImageLoader(),
                    contentDescription = item.mangaTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = item.mangaTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = item.chapterTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formatDate(item.lastReadDate),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.content_description_delete),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@Composable
private fun formatDate(date: LocalDateTime): String {
    val now = LocalDateTime.now()
    val days = ChronoUnit.DAYS.between(date, now)

    return when {
        days == 0L -> stringResource(R.string.today)
        days == 1L -> stringResource(R.string.yesterday)
        days < 7 -> stringResource(R.string.days_ago, days.toInt())
        days < 30 -> stringResource(R.string.weeks_ago, (days / 7).toInt())
        days < 365 -> stringResource(R.string.months_ago, (days / 30).toInt())
        else -> stringResource(R.string.years_ago, (days / 365).toInt())
    }
}