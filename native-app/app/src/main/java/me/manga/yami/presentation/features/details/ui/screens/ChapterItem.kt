package me.manga.yamiapk.presentation.features.details.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.core.util.date.Date.toRelativeString
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo

@Composable
fun ChapterItem(
    manga: MangaInfo,
    chapter: ChapterItem,
    chapters: MutableList<ChapterItem>,
    isSaved: Boolean,
    onRequestAddBookmark: () -> Unit,
    onDownloadClick :()->Unit,
    onChapterClick: (ChapterItem, MangaInfo, List<ChapterItem>) -> Unit,



    ) {

    val context = LocalContext.current
    Card(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onChapterClick(chapter, manga, chapters) }
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(8.dp),
                clip = false,
                ambientColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                spotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)

            )

        ,
        shape = RoundedCornerShape(8.dp),
//        elevation = CardDefaults.cardElevation(
//            defaultElevation = 8.dp,
//            pressedElevation = 12.dp,
//        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.background,    // cardBackgroundColor
            contentColor = MaterialTheme.colorScheme.onBackground,

            )
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {

                Text(
                    text = chapter.number.ifBlank { chapter.name }, fontWeight = FontWeight.Bold)

                Text(
                    chapter.date?.toRelativeString(context).orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }


            IconButton(onClick = { if (isSaved)  onDownloadClick() else onRequestAddBookmark()
            }) {
                Icon(
                    if (chapter.isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                    contentDescription = null,
                    tint = if (chapter.isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

        }
    }
}