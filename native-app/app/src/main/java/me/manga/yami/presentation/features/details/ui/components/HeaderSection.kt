package me.manga.yamiapk.presentation.features.details.ui.components

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.manga.yamiapk.R
import me.manga.yamiapk.ad_mob.bannars.BannerAdView
import me.manga.yamiapk.core.util.date.Date.daysSince
import me.manga.yamiapk.di.coli.getImageLoader
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.presentation.common.componants.buttons.ActionButton


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeaderSection(
    manga: MangaInfo,
    isSaved: Boolean,
    onMangaBookmark: (MangaInfo) -> Unit,
    onRequestAddBookmark: (Boolean) -> Unit,
    onDownloadClick: () -> Unit,
    onOpenInWebView: (String, String) -> Unit,
    buildImageRequest:(Context, String, String) -> ImageRequest

) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Log.i("asdasklsfsakdfsadfsad",manga.toString())
        AsyncImage(
            model  = buildImageRequest(context,manga.imageUrl,manga.api),
            contentDescription = manga.title,
            imageLoader = getImageLoader(),
            modifier = Modifier
                .size(200.dp, 250.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = manga.title,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .combinedClickable(
                    onClick = { /* if you want a normal click */ },
                    onLongClick = {
                        // copy to clipboard
                        clipboardManager.setText(AnnotatedString(manga.title))
                        Toast.makeText(context,
                            context.getString(R.string.title_copied), Toast.LENGTH_SHORT).show()
                    }
                )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${manga.api} ${manga.language} - ${manga.status}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        BannerAdView()
        Spacer(Modifier.height(8.dp))

        GenresAndDescriptionSection(manga.genres, manga.description.orEmpty())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            ActionButton(
                text = if (isSaved) stringResource(R.string.action_remove) else stringResource(R.string.action_bookmark),
                icon = if (isSaved) Icons.Default.BookmarkRemove else Icons.Default.BookmarkBorder,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                onClick = { if (isSaved)  onMangaBookmark(manga) else onRequestAddBookmark(true) },
                modifier = Modifier.weight(1f)
            )

            val days = manga.chapters.firstOrNull()?.date?.daysSince()

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
                text = stringResource(R.string.action_download_all),
                icon = Icons.Default.Download,
                onClick = {   if (isSaved)  onDownloadClick() else onRequestAddBookmark(true) },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            ActionButton(
                text = stringResource(R.string.action_open_in_browser),
                icon = Icons.Default.Language,
                onClick = {onOpenInWebView(manga.url,manga.api)},
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}