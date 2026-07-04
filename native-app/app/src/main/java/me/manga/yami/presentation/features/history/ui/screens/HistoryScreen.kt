package me.manga.yamiapk.presentation.features.history.ui.screens

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.request.ImageRequest
import me.manga.yamiapk.R
import me.manga.yamiapk.data.local.entity.HistoryItemD
import me.manga.yamiapk.presentation.common.componants.app_bars.TopAppBarCom
import me.manga.yamiapk.presentation.features.history.ui.viewmodel.HistoryViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onMangaClick: (HistoryItemD) -> Unit,
    onChapterClick: (HistoryItemD) -> Unit,
    buildImageRequest: (Context, String, String) -> ImageRequest

) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val groupedItems = groupItemsByDate(uiState.historyItems)

    Scaffold(
        topBar = {


            TopAppBarCom(
                title = stringResource(id = R.string.title_history),
                actions = {
                    IconButton(onClick = { viewModel.deleteAllHistory() }) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = stringResource(R.string.content_description_clear_history),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )

        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = 8.dp
            )
        ) {
            groupedItems.forEach { (date, items) ->

                stickyHeader {
                    Text(
                        text = formatGroupLabel(date),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                items(items, key = { it.id }) { historyItem ->
                    HistoryItem(
                        item = historyItem,
                        onMangaClick = { onMangaClick(historyItem) },
                        onChapterClick = { onChapterClick(historyItem) },
                        onDeleteClick = { viewModel.deleteHistory(historyItem) },
                        buildImageRequest = buildImageRequest
                    )
                }
            }
        }
    }
}

/**
 * Groups history items by their read date, sorted descending.
 */
private fun groupItemsByDate(items: List<HistoryItemD>): Map<LocalDate, List<HistoryItemD>> {
    return items
        .groupBy { it.lastReadDate.toLocalDate() }
        .toSortedMap(compareByDescending { it })
}

/**
 * Formats the group header label: "Today", "Yesterday", "X days ago" for dates within the past week,
 * otherwise a formatted date like "Apr 15, 2025".
 */
@Composable
private fun formatGroupLabel(date: LocalDate): String {
    val today = LocalDate.now()
    val daysAgo = ChronoUnit.DAYS.between(date, today)

    return when {
        daysAgo == 0L -> stringResource(R.string.today)
        daysAgo == 1L -> stringResource(R.string.yesterday)
        daysAgo in 2..6 -> stringResource(R.string.days_ago, daysAgo.toInt())
        else -> date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }
}
