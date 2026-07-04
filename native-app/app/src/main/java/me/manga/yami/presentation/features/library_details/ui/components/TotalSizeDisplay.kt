package me.manga.yamiapk.presentation.features.library_details.ui.components


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.manga.yamiapk.R
import me.manga.yamiapk.core.file.FileSizeUtils
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity

@Composable
fun TotalSizeDisplay(
    manga: SavedMangaEntity,
    chapters: List<SavedChapterEntity>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var totalSize by remember { mutableStateOf("") }

    LaunchedEffect(manga.id, chapters) {
        totalSize = withContext(Dispatchers.IO) {
            FileSizeUtils.getFormattedMangaSize(context, manga.id)
        }
    }

    val downloadedCount = remember(chapters) {
        chapters.count { it.isDownloaded }
    }

    if (totalSize.isNotEmpty() && downloadedCount > 0) {
        Row(
            modifier = modifier
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )

            Text(
                text = stringResource(R.string.downloaded_header, totalSize, downloadedCount),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
