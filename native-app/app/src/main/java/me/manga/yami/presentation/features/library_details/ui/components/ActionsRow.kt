package me.manga.yamiapk.presentation.features.library_details.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.manga.yamiapk.R
import me.manga.yamiapk.core.util.date.Date.daysSince
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.presentation.common.componants.buttons.ActionButton


@Composable
 fun ActionsRow(
    isSaved: Boolean,
    chapters: List<SavedChapterEntity>,
    onMangaBookmarkClick: (List<SavedChapterEntity>, SavedMangaEntity) -> Unit,
    onRequestBookmark: (Boolean) -> Unit,
    isDownloadingAll: Boolean,
    onDownloadMenuClick: () -> Unit,
    onOpenInWebView: (String, String) -> Unit,
    manga: SavedMangaEntity? = null
) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth()
    ) {
        ActionButton(
            text = if (isSaved) stringResource(R.string.action_remove) else stringResource(R.string.action_bookmark),
            icon = if (isSaved) Icons.Default.BookmarkRemove else Icons.Default.BookmarkBorder,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            onClick = {
                if (isSaved) onRequestBookmark(true) else manga?.let { onMangaBookmarkClick(chapters, it) }
            },
            modifier = Modifier.weight(1f)
        )
        val days = chapters.firstOrNull()?.date?.daysSince()

        ActionButton(
            text = when (days) {
                null          -> stringResource(R.string.action_no_chapter_yet)
                0L             -> stringResource(R.string.action_today)
                1L             -> stringResource(R.string.action_yesterday)
                else          -> stringResource(R.string.day_since_format, days)
            },
            icon = Icons.Default.Schedule,
            onClick = {},
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        ActionButton(
            text = if (isDownloadingAll) stringResource(R.string.action_downloading) else stringResource(R.string.action_download_all),
            icon = Icons.Default.Download,
            onClick = onDownloadMenuClick,
            modifier = Modifier.weight(1f),
            isLoading = isDownloadingAll,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        ActionButton(
            text = stringResource(R.string.action_open_in_browser),
            icon = Icons.Default.Language,
            onClick = { manga?.let { onOpenInWebView(it.url, it.api) } },
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )


    }
}
