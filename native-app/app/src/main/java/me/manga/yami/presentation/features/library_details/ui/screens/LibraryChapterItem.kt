    //package me.manga.yamiapk.presentation.features.library_details.ui.screens
    //
    //import androidx.compose.foundation.background
    //import androidx.compose.foundation.clickable
    //import androidx.compose.foundation.combinedClickable
    //import androidx.compose.foundation.layout.*
    //import androidx.compose.foundation.shape.CircleShape
    //import androidx.compose.foundation.shape.RoundedCornerShape
    //import androidx.compose.material.Checkbox
    //import androidx.compose.material.CheckboxDefaults
    //import androidx.compose.material.icons.Icons
    //import androidx.compose.material.icons.filled.*
    //import androidx.compose.material.icons.outlined.RemoveRedEye
    //import androidx.compose.material3.*
    //import androidx.compose.runtime.*
    //import androidx.compose.ui.Alignment
    //import androidx.compose.ui.Modifier
    //import androidx.compose.ui.draw.clip
    //import androidx.compose.ui.draw.shadow
    //import androidx.compose.ui.graphics.Color
    //import androidx.compose.ui.platform.LocalContext
    //import androidx.compose.ui.res.stringResource
    //import androidx.compose.ui.text.font.FontWeight
    //import androidx.compose.ui.text.style.TextAlign
    //import androidx.compose.ui.unit.dp
    //import androidx.compose.ui.unit.sp
    //import me.manga.yamiapk.R
    //import me.manga.yamiapk.core.file.FileSizeUtils
    //import me.manga.yamiapk.core.util.date.Date.toRelativeString
    //import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
    //import me.manga.yamiapk.data.local.entity.SavedChapterEntity
    //import me.manga.yamiapk.data.local.entity.SavedMangaEntity
    //
    //@Composable
    //fun LibraryChapterItem(
    //    chapter: SavedChapterEntity,
    //    manga: SavedMangaEntity,
    //    isSelected: Boolean,
    //    runningChapter: ChapterDownloadEntity?,
    //    chapters: List<SavedChapterEntity>,
    //    onSelectChanged: (Boolean) -> Unit,
    //    onChapterClick: (SavedChapterEntity, SavedMangaEntity, chapters: List<SavedChapterEntity>) -> Unit,
    //    onChapterBookmarkClick: (SavedChapterEntity) -> Unit,
    //    onChapterDownloadClick: (SavedChapterEntity, SavedMangaEntity) -> Unit,
    //    onChapterReadClick: (SavedChapterEntity) -> Unit,
    //    downloadingChapters: List<Long>,
    //    showChaptersCheckBox: MutableState<Boolean>,
    //    onLongClick: () -> Unit,
    //    onCancelRunningChapter: (SavedChapterEntity, SavedMangaEntity) -> Unit,
    //    onCancelChapter: (SavedChapterEntity, SavedMangaEntity) -> Unit
    //) {
    //    var menuExpanded by remember { mutableStateOf(false) }
    //    val context = LocalContext.current
    //
    //    // Calculate file size if chapter is downloaded
    //    val fileSize = remember(chapter.isDownloaded, chapter.id, manga.id) {
    //        if (chapter.isDownloaded) {
    //            FileSizeUtils.getFormattedChapterSize(context, manga.id, chapter.id)
    //        } else {
    //            ""
    //        }
    //    }
    //
    //    Card(
    //        Modifier
    //            .fillMaxWidth()
    //            .padding(horizontal = 8.dp, vertical = 8.dp)
    //            .shadow(
    //                elevation = 4.dp,
    //                shape = RoundedCornerShape(8.dp),
    //                clip = false,
    //                ambientColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
    //                spotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
    //            )
    //            .combinedClickable(
    //                onClick = {
    //                    if (showChaptersCheckBox.value) {
    //                        onSelectChanged(!isSelected)
    //                    } else {
    //                        onChapterClick(chapter, manga, chapters)
    //                    }
    //                },
    //                onLongClick = onLongClick
    //            ),
    //        shape = RoundedCornerShape(8.dp),
    //        colors = CardDefaults.cardColors(
    //            containerColor = MaterialTheme.colorScheme.background,
    //            contentColor = MaterialTheme.colorScheme.onBackground
    //        )
    //    ) {
    //        Box {
    //            Row(
    //                Modifier.padding(16.dp),
    //                verticalAlignment = Alignment.CenterVertically
    //            ) {
    //                if (showChaptersCheckBox.value) {
    //                    Checkbox(
    //                        checked = isSelected,
    //                        onCheckedChange = onSelectChanged,
    //                        colors = CheckboxDefaults.colors(
    //                            checkedColor = MaterialTheme.colorScheme.primaryContainer,
    //                            uncheckedColor = MaterialTheme.colorScheme.onBackground,
    //                            checkmarkColor = MaterialTheme.colorScheme.onPrimaryContainer
    //                        )
    //                    )
    //                    Spacer(Modifier.width(8.dp))
    //                }
    //
    //                Spacer(modifier = Modifier.width(8.dp))
    //
    //                Column(
    //                    Modifier.weight(1f),
    //                    horizontalAlignment = Alignment.Start,
    //                ) {
    //                    Text(
    //                        text = chapter.number.ifBlank { chapter.name },
    //                        modifier = Modifier.align(Alignment.Start),
    //                        fontWeight = FontWeight.Bold,
    //                        textAlign = TextAlign.Start
    //                    )
    //
    //                    Row(
    //                        modifier = Modifier.align(Alignment.Start),
    //                        verticalAlignment = Alignment.CenterVertically,
    //                        horizontalArrangement = Arrangement.spacedBy(8.dp)
    //                    ) {
    //                        Text(
    //                            chapter.date?.toRelativeString().orEmpty(),
    //                            style = MaterialTheme.typography.bodySmall,
    //                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    //                            textAlign = TextAlign.Start
    //                        )
    //
    //                        // Show file size if downloaded
    //                        if (fileSize.isNotEmpty()) {
    //                            Text(
    //                                "•",
    //                                style = MaterialTheme.typography.bodySmall,
    //                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    //                            )
    //                            Text(
    //                                fileSize,
    //                                style = MaterialTheme.typography.bodySmall,
    //                                color = MaterialTheme.colorScheme.primary,
    //                                fontWeight = FontWeight.Medium,
    //                                textAlign = TextAlign.Start
    //                            )
    //                        }
    //                    }
    //                }
    //
    //                IconButton(onClick = { onChapterReadClick(chapter) }) {
    //                    Icon(
    //                        imageVector = Icons.Outlined.RemoveRedEye,
    //                        contentDescription = null,
    //                        tint = if (chapter.isRead)
    //                            MaterialTheme.colorScheme.primary
    //                        else
    //                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    //                    )
    //                }
    //
    //                if (downloadingChapters.contains(chapter.id) || chapter.id == runningChapter?.chapterId) {
    //                    if (chapter.id == runningChapter?.chapterId) {
    //                        val progressFraction = (runningChapter.progress.coerceIn(0, 100) / 100f)
    //                        Spacer(Modifier.width(8.dp))
    //                        Box(
    //                            modifier = Modifier
    //                                .size(24.dp)
    //                                .clickable { menuExpanded = true },
    //                            contentAlignment = Alignment.Center
    //                        ) {
    //                            CircularProgressIndicator(
    //                                progress = { progressFraction },
    //                                modifier = Modifier.size(24.dp),
    //                                color = MaterialTheme.colorScheme.primary,
    //                                strokeWidth = 2.dp
    //                            )
    //                        }
    //
    //                        DropdownMenu(
    //                            expanded = menuExpanded,
    //                            onDismissRequest = { menuExpanded = false },
    //                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    //                        ) {
    //                            DropdownMenuItem(
    //                                text = { Text("Cancel chapter download") },
    //                                onClick = {
    //                                    onCancelRunningChapter(chapter, manga)
    //                                    menuExpanded = false
    //                                }
    //                            )
    //                        }
    //                    } else {
    //                        Spacer(Modifier.width(8.dp))
    //                        Box(
    //                            modifier = Modifier
    //                                .size(24.dp)
    //                                .clickable { menuExpanded = true },
    //                            contentAlignment = Alignment.Center
    //                        ) {
    //                            CircularProgressIndicator(
    //                                modifier = Modifier.size(24.dp),
    //                                color = MaterialTheme.colorScheme.primary,
    //                                strokeWidth = 2.dp
    //                            )
    //                        }
    //                        DropdownMenu(
    //                            expanded = menuExpanded,
    //                            onDismissRequest = { menuExpanded = false },
    //                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    //                        ) {
    //                            DropdownMenuItem(
    //                                text = { Text("Cancel chapter download") },
    //                                onClick = {
    //                                    onCancelChapter(chapter, manga)
    //                                    menuExpanded = false
    //                                }
    //                            )
    //                        }
    //                    }
    //                    Spacer(Modifier.width(8.dp))
    //                } else {
    //                    IconButton(
    //                        onClick = { onChapterDownloadClick(chapter, manga) },
    //                        enabled = !chapter.isDownloaded
    //                    ) {
    //                        Icon(
    //                            imageVector = if (chapter.isDownloaded)
    //                                Icons.Default.DownloadDone
    //                            else
    //                                Icons.Default.Download,
    //                            contentDescription = null,
    //                            tint = if (chapter.isDownloaded)
    //                                MaterialTheme.colorScheme.primary
    //                            else
    //                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    //                        )
    //                    }
    //                }
    //
    //                IconButton(onClick = { onChapterBookmarkClick(chapter) }) {
    //                    Icon(
    //                        imageVector = if (chapter.isBookmarked)
    //                            Icons.Default.BookmarkRemove
    //                        else
    //                            Icons.Default.BookmarkBorder,
    //                        contentDescription = null,
    //                        tint = if (chapter.isBookmarked)
    //                            MaterialTheme.colorScheme.primary
    //                        else
    //                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    //                    )
    //                }
    //            }
    //
    //            // NEW badge in top-right corner
    //            if (chapter.isNew) {
    //                Card(
    //                    modifier = Modifier
    //                        .align(Alignment.TopEnd)
    //                        .padding(0.dp),
    //                    shape = RoundedCornerShape(4.dp),
    //                    colors = CardDefaults.cardColors(
    //                        containerColor = Color.Red
    //                    )
    //                ) {
    //                    Text(
    //                        text = stringResource(R.string.new_chapter),
    //                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
    //                        style = MaterialTheme.typography.labelSmall,
    //                        fontWeight = FontWeight.Bold,
    //                        color = Color.White
    //                    )
    //                }
    //            }
    //        }
    //    }
    //}

    package me.manga.yamiapk.presentation.features.library_details.ui.screens

    import AutoSubtitleText
    import android.util.Log
    import androidx.compose.foundation.background
    import androidx.compose.foundation.clickable
    import androidx.compose.foundation.combinedClickable
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.shape.CircleShape
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.material.Checkbox
    import androidx.compose.material.CheckboxDefaults
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.filled.*
    import androidx.compose.material.icons.outlined.RemoveRedEye
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.draw.clip
    import androidx.compose.ui.draw.shadow
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.platform.LocalContext
    import androidx.compose.ui.res.stringResource
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.text.style.TextAlign
    import androidx.compose.ui.text.style.TextOverflow
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext
    import me.manga.yamiapk.R
    import me.manga.yamiapk.core.file.FileSizeUtils
    import me.manga.yamiapk.core.util.date.Date.toRelativeString
    import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
    import me.manga.yamiapk.data.local.entity.SavedChapterEntity
    import me.manga.yamiapk.data.local.entity.SavedMangaEntity
    import me.manga.yamiapk.presentation.features.download.data.DownloadingState
    import me.manga.yamiapk.presentation.features.library.ui.components.AnimatedCompressing
    import me.manga.yamiapk.presentation.features.library.ui.components.AnimatedPreloader

    @Composable
    fun LibraryChapterItem(
        chapter: SavedChapterEntity,
        manga: SavedMangaEntity,
        isSelected: Boolean,
        runningChapter: ChapterDownloadEntity?,
        chapters: List<SavedChapterEntity>,
        onSelectChanged: (Boolean) -> Unit,
        onChapterClick: (SavedChapterEntity, SavedMangaEntity, chapters: List<SavedChapterEntity>) -> Unit,
        onChapterBookmarkClick: (SavedChapterEntity) -> Unit,
        onChapterDownloadClick: (SavedChapterEntity, SavedMangaEntity) -> Unit,
        onChapterReadClick: (SavedChapterEntity) -> Unit,
        downloadingChapters: List<Long>,
        showChaptersCheckBox: MutableState<Boolean>,
        onLongClick: () -> Unit,
        onCancelRunningChapter: (SavedChapterEntity, SavedMangaEntity) -> Unit,
        onCancelChapter: (SavedChapterEntity, SavedMangaEntity) -> Unit
    ) {
        var menuExpanded by remember { mutableStateOf(false) }
        val context = LocalContext.current

        var fileSize by remember { mutableStateOf("") }

        LaunchedEffect(chapter.isDownloaded, chapter.id, manga.id) {
            fileSize = if (chapter.isDownloaded) {
                withContext(Dispatchers.IO) {
                    FileSizeUtils.getFormattedChapterSize(context, manga.id, chapter.id)
                }
            } else ""
        }

        Card(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(8.dp),
                    clip = false,
                    ambientColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    spotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                )
                .combinedClickable(
                    onClick = {
                        if (showChaptersCheckBox.value) {
                            onSelectChanged(!isSelected)
                        } else {
                            onChapterClick(chapter, manga, chapters)
                        }
                    },
                    onLongClick = onLongClick
                ),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground
            )
        ) {
            Box {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showChaptersCheckBox.value) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = onSelectChanged,
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primaryContainer,
                                uncheckedColor = MaterialTheme.colorScheme.onBackground,
                                checkmarkColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(
                            text = chapter.number.ifBlank { chapter.name },
                            modifier = Modifier.align(Alignment.Start),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Start
                        )

                        Row(
                            modifier = Modifier
                                .align(Alignment.Start)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AutoSubtitleText(
                                text = chapter.date?.toRelativeString(context).orEmpty(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    fontWeight = FontWeight.Bold
                                ),
                                textAlign = TextAlign.Start,
                                fontSize = 12.sp,
                                maxSize = 12.sp,
                                minSize = 8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            // Show file size if downloaded
                            if (fileSize.isNotEmpty()) {
                                AutoSubtitleText(
                                    text = "•",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    ),
                                    fontSize = 12.sp,
                                    maxSize = 12.sp,
                                    minSize = 8.sp,
                                    maxLines = 1,

                                )
                                AutoSubtitleText(
                                    text = fileSize,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Start
                                    ),
                                    fontSize = 12.sp,
                                    maxSize = 12.sp,
                                    minSize = 8.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)

                                )
                            }
                        }
                    }

                    IconButton(onClick = { onChapterReadClick(chapter) }) {
                        Icon(
                            imageVector = Icons.Outlined.RemoveRedEye,
                            contentDescription = null,
                            tint = if (chapter.isRead)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    // Download/Progress indicator section
                    if (downloadingChapters.contains(chapter.id) || chapter.id == runningChapter?.chapterId) {
                        if (chapter.id == runningChapter?.chapterId) {
                            // Running chapter - check state
                            when (runningChapter.state) {

                                DownloadingState.COMPRESSING -> {

                                    IconButton(onClick = {}) {
                                        AnimatedCompressing(
                                            backgroundColor = MaterialTheme.colorScheme.background,
                                            iconColor = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                DownloadingState.RUNNING -> {
                                    val progressFraction = (runningChapter.progress.coerceIn(0, 100) / 100f)
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable { menuExpanded = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            progress = { progressFraction },
                                            modifier = Modifier.size(24.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 2.dp
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Cancel chapter download") },
                                            onClick = {
                                                onCancelRunningChapter(chapter, manga)
                                                menuExpanded = false
                                            }
                                        )
                                    }
                                }

                                else -> {
                                    // Handle other states (QUEUED, etc.)
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable { menuExpanded = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 2.dp
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false },
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.cancel_chapter_download)) },
                                            onClick = {
                                                onCancelChapter(chapter, manga)
                                                menuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        } else {
                            // Queued chapter (not running yet)
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable { menuExpanded = true },
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.cancel_chapter_download)) },
                                    onClick = {
                                        onCancelChapter(chapter, manga)
                                        menuExpanded = false
                                    }
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    } else {
                        // Not downloading - show download button
                        IconButton(
                            onClick = { onChapterDownloadClick(chapter, manga) },
                            enabled = !chapter.isDownloaded
                        ) {
                            Icon(
                                imageVector = if (chapter.isDownloaded)
                                    Icons.Default.DownloadDone
                                else
                                    Icons.Default.Download,
                                contentDescription = null,
                                tint = if (chapter.isDownloaded)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }

                    IconButton(onClick = { onChapterBookmarkClick(chapter) }) {
                        Icon(
                            imageVector = if (chapter.isBookmarked)
                                Icons.Default.BookmarkRemove
                            else
                                Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            tint = if (chapter.isBookmarked)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }

                // NEW badge in top-right corner
                if (chapter.isNew) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(0.dp),
                        shape = RoundedCornerShape(4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Red
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.new_chapter),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }