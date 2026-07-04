package me.manga.yamiapk.presentation.features.download.ui.screens

import AutoSubtitleText
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.R
import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
import me.manga.yamiapk.presentation.common.componants.app_bars.TopAppBarCom
import me.manga.yamiapk.presentation.features.download.data.DownloadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
//    downloadsPaged: Flow<PagingData<ChapterDownloadEntity>>,
    activeDownloadsPaged: Flow<PagingData<ChapterDownloadEntity>>,
    failedDownloadsPaged: Flow<PagingData<ChapterDownloadEntity>>,
    completedDownloadsPaged: Flow<PagingData<ChapterDownloadEntity>>,
    onRetry: (ChapterDownloadEntity) -> Unit = {},
    onCancel: (ChapterDownloadEntity) -> Unit = {},
    runningChapterCancel: (ChapterDownloadEntity) -> Unit = {},
    onDelete: (ChapterDownloadEntity) -> Unit = {},
    onBack: () -> Unit = {}
) {
    BackHandler { onBack() }

    var selectedTab by remember { mutableStateOf(2) }
    val tabTitles = listOf(
        stringResource(R.string.active),
        stringResource(R.string.failed),
        stringResource(R.string.completed)
    )

    Scaffold(
        topBar = {
            TopAppBarCom(
                title = stringResource(R.string.downloads),
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    val items = activeDownloadsPaged.collectAsLazyPagingItems()
                    ActiveDownloadsTabPaged(
                        items = items,
                        runningChapterCancel = runningChapterCancel,
                        onCancel = onCancel
                    )
                }
                1 -> {
                    val items = failedDownloadsPaged.collectAsLazyPagingItems()
                    CompletedDownloadsTabPaged(
                        items = items,
                        onRetry = onRetry,
                        onDelete = onDelete
                    )
                }
                2 -> {
                    val items = completedDownloadsPaged.collectAsLazyPagingItems()
                    CompletedDownloadsTabPaged(
                        items = items,
                        onRetry = onRetry,
                        onDelete = onDelete
                    )
                }
            }
        }
    }
}

@Composable
fun ActiveDownloadsTabPaged(
    items: LazyPagingItems<ChapterDownloadEntity>,
    runningChapterCancel: (ChapterDownloadEntity) -> Unit,
    onCancel: (ChapterDownloadEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp)
    ) {
        items(
            count = items.itemCount,
            key = items.itemKey { it.chapterId }
        ) { index ->
            val item = items[index]
            if (item != null) {
                if (item.state == DownloadingState.RUNNING) {
                    RunningDownloadItemCard(item = item, onCancel = runningChapterCancel)
                } else {
                    DownloadItemCard(
                        item = item,
                        showCancel = true,
                        onCancel = { onCancel(item) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Loading indicator
        when (items.loadState.append) {
            is LoadState.Loading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            is LoadState.Error -> {
                item {
                    Text(
                        text = stringResource(R.string.error_loading_more),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            else -> {}
        }
    }
}

@Composable
fun CompletedDownloadsTabPaged(
    items: LazyPagingItems<ChapterDownloadEntity>,
    onRetry: (ChapterDownloadEntity) -> Unit,
    onDelete: (ChapterDownloadEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp)
    ) {
        items(
            count = items.itemCount,
            key = items.itemKey { it.chapterId }
        ) { index ->
            val item = items[index]
            if (item != null) {
                DownloadItemCard(
                    item = item,
                    showRetry = item.state == DownloadingState.FAILED,
                    showDelete = true,
                    onRetry = { onRetry(item) },
                    onDelete = { onDelete(item) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        when (items.loadState.append) {
            is LoadState.Loading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
            is LoadState.Error -> {
                item {
                    Text(
                        text = stringResource(R.string.error_loading_more),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            else -> {}
        }
    }
}

@Composable
fun DownloadItemCard(
    item: ChapterDownloadEntity,
    showCancel: Boolean = false,
    showRetry: Boolean = false,
    showDelete: Boolean = false,
    onCancel: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    Card(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(8.dp),
                clip = false,
                ambientColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                spotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AutoSubtitleText(
                    text = "Ch ${item.number} - ${item.mangaTitle} ",
                    fontSize = 16.sp,
                    maxSize = 16.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                when (item.state) {
                    DownloadingState.RUNNING -> AutoSubtitleText(
                        stringResource(R.string.running),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    DownloadingState.QUEUED -> AutoSubtitleText(
                        stringResource(R.string.queued),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    DownloadingState.SUCCESS -> AutoSubtitleText(
                        stringResource(R.string.downloaded),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    DownloadingState.FAILED -> {
                        val msg = item.errorMsg ?: stringResource(R.string.unknown)
                        AutoSubtitleText(
                            text = stringResource(R.string.download_failed, msg),
                            maxLines = 2,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    DownloadingState.COMPRESSING ->
                        AutoSubtitleText(
                        stringResource(R.string.downloaded),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            if (showCancel) {
                if (item.state != DownloadingState.RUNNING) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = {}) {
                        Text("${item.progress}%", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            if (showRetry) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }

            if (item.state == DownloadingState.SUCCESS) {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Outlined.Done,
                        contentDescription = "done",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (showDelete) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun RunningDownloadItemCard(
    item: ChapterDownloadEntity,
    onCancel: (ChapterDownloadEntity) -> Unit
) {
    val progressFraction = (item.progress.coerceIn(0, 100) / 100f)
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(durationMillis = 300)
    )

    Card(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 6.dp, vertical = 12.dp)
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(8.dp),
                clip = false,
                ambientColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                spotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AutoSubtitleText(
                    modifier = Modifier.weight(1F),
                    text = "Ch ${item.number} - ${item.mangaTitle}",
                    fontSize = 16.sp,
                    maxSize = 16.sp,
                    minSize = 8.sp,
                    maxLines = 1,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                IconButton(onClick = { onCancel(item) }) {
                    Icon(
                        imageVector = Icons.Default.Cancel,
                        contentDescription = "Cancel download",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.CenterStart)
                        .height(8.dp),
                )
                Text(
                    text = "${item.progress.coerceIn(0, 100)}%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}