package me.manga.yamiapk.presentation.features.reader.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R
import me.manga.yamiapk.domain.model.ReaderChapters
import kotlin.math.roundToInt

@Composable
fun ControlOverlay(
    currentChapter: ReaderChapters,
    show: Boolean,
    currentPage: Int,
    pageCount: Int,
    isBookmarked: Boolean,
    hasNext: Boolean,
    hasPrevious: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPageChange: (Int) -> Unit,
    onBackPressed: () -> Unit,
    onSettings: () -> Unit,
    onBookmark: () -> Unit,
    onShare: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = show,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { -it },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it }
        ) {
            TopAppBar(
                title = currentChapter.mangaName,
                number = currentChapter.chapterNumber,
                onBack = onBackPressed,
                onMenu = { /* ... */ }
            )
        }

        AnimatedVisibility(
            visible = show,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {


                SeekBarContainer(
                    progress = currentPage.toFloat(),
                    total = (pageCount).toFloat(),
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeekChange = { floatPos ->
                        onPageChange(floatPos.roundToInt())
                    },
                    hasPrevious = hasPrevious,
                    hasNext = hasNext
                )


                Spacer(modifier = Modifier.height(8.dp))
                BottomActionBar(
                    onSettings = onSettings,
                    onBookmark = onBookmark,
                    onShare = onShare,
                    isBookmarked = isBookmarked
                )
            }
        }
    }
}

@Composable
private fun TopAppBar(
    title: String,
    number: String,
    onBack: () -> Unit,
    onMenu: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(id = R.drawable.ic_back_),
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = number,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                maxLines = 1
            )
        }
        IconButton(onClick = onMenu) {
            Icon(
                painter = painterResource(id = R.drawable.dots),
                contentDescription = "Menu",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun BottomActionBar(
    onSettings: () -> Unit,
    onBookmark: () -> Unit,
    onShare: () -> Unit,
    isBookmarked: Boolean,

    ) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onSettings, modifier = Modifier.weight(1f)) {
            Icon(
                painter = painterResource(id = R.drawable.ic_reader_setting),
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        IconButton(onClick = onBookmark, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = if (isBookmarked) "Remove Bookmark" else "Add Bookmark",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
        IconButton(onClick = onShare, modifier = Modifier.weight(1f)) {
            Icon(
                painter = painterResource(id = R.drawable.ic_panal_shera),
                contentDescription = "Share",
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

