package me.manga.yamiapk.presentation.features.notifications.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import me.manga.yamiapk.R
import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
import me.manga.yamiapk.data.local.entity.ChapterNotification
import me.manga.yamiapk.di.coli.getImageLoader
import me.manga.yamiapk.presentation.features.download.data.DownloadState
import me.manga.yamiapk.presentation.features.download.data.DownloadingState
import me.manga.yamiapk.theme.YamiMangaTheme

@Composable
 fun NotificationItems(
    notification: ChapterNotification,
    onNotificationClick: (ChapterNotification) -> Unit,
    onNotificationImgClick: (ChapterNotification) -> Unit,
    onNotificationDownloadClick: (ChapterNotification) -> Unit,
    downloadingChapters: List<Long>,
    runningChapter : ChapterDownloadEntity?,

) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNotificationClick(notification) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = notification.mangaImageUrl,
            contentDescription = notification.mangaTitle,
            imageLoader = getImageLoader(),
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onNotificationImgClick(notification) }
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = notification.mangaTitle,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 16.sp,
                maxLines = 1,
                modifier = Modifier.alpha(
                    if (notification.isRead) 0.4f else 1f   // 👈 reduce opacity when read
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!notification.isRead) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = stringResource(R.string.chapter_number, notification.chapterNumber),
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.alpha(
                        if (notification.isRead) 0.4f else 1f   // 👈 reduce opacity when read
                    )
                )
            }
        }
        if (downloadingChapters.contains(notification.chapterId)||notification.chapterId == runningChapter?.chapterId) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(

                modifier = Modifier.size(24.dp),
                color =if (runningChapter != null && notification.chapterId == runningChapter.chapterId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )

            Spacer(Modifier.width(8.dp))
        } else {
            IconButton(
                onClick = { onNotificationDownloadClick(notification) },
                modifier = Modifier.size(48.dp),
                enabled = !notification.isDownloaded
            ) {
                Icon(
                    imageVector = if (notification.isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                    contentDescription = null,
                    tint = if (notification.isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(
                        alpha = 0.7f
                    )
                )
            }
        }
    }
}


@Composable
@Preview(showSystemUi = true)
fun NotificationItemsPreview() {
    val sampleNotification = ChapterNotification(
        chapterId = 12345L,
        mangaTitle = "Solo Leveling",
        mangaImageUrl = "https://placehold.co/200x300",
        chapterNumber = "Chapter 72",
        isRead = true,
        isDownloaded = false,
        api = "mangas",
        language = "sd",
        mangaId = 1,
        mangaUrl = "",
        chapterUrl = "TODO()"
    )

    val runningChapter = ChapterDownloadEntity(
        chapterId = 12345L,
        mangaId = 999L,
        number = "1",
        api = "mangas",
        url = "",
        state = DownloadingState.RUNNING,
        progress = 1,
    )

    YamiMangaTheme(true) {
        Box(
            modifier = Modifier
                .background(Color(0xFF1E88E5)) // 💙 Blue Background
                .fillMaxWidth()
        ) {
            NotificationItems(
                notification = sampleNotification,
                onNotificationClick = {},
                onNotificationImgClick = {},
                onNotificationDownloadClick = {},
                downloadingChapters = listOf(12345L),
                runningChapter = runningChapter
            )
        }
    }
}
