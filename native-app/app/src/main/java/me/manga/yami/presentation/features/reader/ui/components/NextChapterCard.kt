package me.manga.yamiapk.presentation.features.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R
import me.manga.yamiapk.domain.model.ReaderChapters


@Composable
fun NextChapterCard(
    currentChapter: ReaderChapters,
    nextChapter: ReaderChapters,
    onGoToNext: () -> Unit,
    screenHig :Dp,
    isCurrentChapLoading:Boolean,

    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .heightIn(screenHig)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .clickable { onGoToNext() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {

            if (isCurrentChapLoading) {
                // This will cover the entire reader area with a semi‐transparent overlay

                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                )

            }else {
                Text(
                    text = stringResource(R.string.you_are_in),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "\"${currentChapter.chapterNumber}\"",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Text(
                    text = stringResource(R.string.going_to),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "\"${nextChapter.chapterNumber}\"",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}
