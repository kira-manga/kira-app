package me.manga.yamiapk.presentation.features.library_details.ui.screens

import AutoSubtitleText
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R
import me.manga.yamiapk.data.local.entity.SavedChapterEntity

@Composable
fun ChapterSelectionActionsRow(
    chapters: Set<SavedChapterEntity>,
    onDownloadAll: (Set<SavedChapterEntity>) -> Unit,
    onBookmarkAll: (Set<SavedChapterEntity>) -> Unit,
    onMarkAllRead: (Set<SavedChapterEntity>) -> Unit,
    onDeleteAll: (Set<SavedChapterEntity>) -> Unit,

    onMarkAllDownRead: (SavedChapterEntity) -> Unit,
    onCancelAll: () -> Unit,

    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AutoSubtitleText(stringResource(
            R.string.chapters_count_format, chapters.size),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight =FontWeight.Bold,
            modifier = Modifier.padding(end = 8.dp)
//                .weight(1f)

        )

        Row(
            modifier = Modifier
                .wrapContentWidth()                         // share remaining space
                .horizontalScroll(                  // allow sideways scrolling
                    rememberScrollState(),
                    enabled = true
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onDownloadAll(chapters) } , enabled = true) {
                Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.action_download_all))
            }
            IconButton(onClick = { onBookmarkAll(chapters) }) {
                Icon(Icons.Filled.BookmarkBorder, contentDescription = stringResource(R.string.action_bookmark))
            }
            if (chapters.size == 1){
                IconButton(onClick = {onMarkAllDownRead(chapters.first())}) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_done_down_arrow), tint = MaterialTheme.colorScheme.onBackground, contentDescription = stringResource(R.string.content_description_close))
                }
            }
            if (chapters.all { it.isDownloaded } && !chapters.isEmpty()) {
                IconButton(onClick = {
                    onDeleteAll(chapters)}) {
                    Icon(Icons.Filled.Delete, tint = MaterialTheme.colorScheme.onBackground, contentDescription = stringResource(R.string.content_description_close))
                }
            }

            IconButton(onClick = { onMarkAllRead(chapters) }) {
                Icon(Icons.Filled.RemoveRedEye, contentDescription = stringResource(R.string.contentDescription_mark_all_as_read))
            }


            IconButton(onClick = onCancelAll) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.content_description_close))
            }

        }
    }
}
