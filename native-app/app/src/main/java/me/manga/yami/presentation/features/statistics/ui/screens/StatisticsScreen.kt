package me.manga.yamiapk.presentation.features.statistics.ui.screens


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FileDownloadDone
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.NotStarted
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.common.componants.ItemsGroup
import me.manga.yamiapk.presentation.common.componants.titles.SectionTitle
import me.manga.yamiapk.presentation.common.componants.list_items.StatsItem
import me.manga.yamiapk.presentation.common.componants.app_bars.TopAppBarCom
import me.manga.yamiapk.presentation.features.statistics.ui.components.StatsOverview

@Composable
fun StatisticsScreen(
    inLibrary: Int,
    readDuration: String,
    completedEntries: Int,
    entriesStarted: Int,
    chaptersTotal: Int,
    chaptersRead: Int,
    chaptersDownloaded: Int,
    chaptersBookmarked: Int,
    onBack: () -> Unit = {}
) {
    Scaffold(

        topBar = {
            TopAppBarCom(title = stringResource(R.string.title_statistics), navigationIcon = { IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription =  stringResource(R.string.desc_back), tint = MaterialTheme.colorScheme.onBackground)
            }})

        },
        contentColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            StatsOverview(
                inLibrary = inLibrary,
                readDuration = readDuration,
                completedEntries = completedEntries
            )

            Spacer(Modifier.height(16.dp))

            SectionTitle(stringResource(R.string.section_entries))
            ItemsGroup {
                StatsItem(
                    title = stringResource(R.string.label_in_library),
                    icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                    value = inLibrary
                )
                Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                StatsItem(
                    title =stringResource(R.string.label_started),
                    icon = Icons.Outlined.NotStarted,
                    value = entriesStarted
                )
                Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                StatsItem(
                    title = stringResource(R.string.label_completed),
                    icon = Icons.Outlined.DoneAll,
                    value = completedEntries
                )
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.section_chapters))
            ItemsGroup {
                StatsItem(
                    title = stringResource(R.string.label_total),
                    icon = Icons.Outlined.SelectAll,
                    value = chaptersTotal
                )
                Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                StatsItem(
                    title =stringResource(R.string.label_read),
                    icon = Icons.Outlined.RemoveRedEye,
                    value = chaptersRead
                )
                Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                StatsItem(
                    title = stringResource(R.string.label_downloaded),
                    icon = Icons.Outlined.FileDownloadDone,
                    value = chaptersDownloaded
                )
                Divider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                StatsItem(
                    title = stringResource(R.string.label_bookmarked),
                    icon = Icons.Outlined.BookmarkAdd,
                    value = chaptersBookmarked
                )
            }
        }
    }
}

















@Preview(showBackground = true)
@Composable
private fun StatsScreenPreview() {
    // Sample values
    StatisticsScreen(
        inLibrary = 10000,
        readDuration = "7h 27m",
        completedEntries = 100000,
        entriesStarted = 100000,
        chaptersTotal = 100000,
        chaptersRead = 100000,
        chaptersDownloaded = 100000,
        chaptersBookmarked = 100000
    )
}