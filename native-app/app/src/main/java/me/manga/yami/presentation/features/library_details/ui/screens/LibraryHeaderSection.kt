package me.manga.yamiapk.presentation.features.library_details.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.ad_mob.bannars.BannerAdView
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.presentation.features.details.ui.components.GenresAndDescriptionSection
import me.manga.yamiapk.presentation.features.library_details.ui.components.ActionsRow
import me.manga.yamiapk.presentation.features.library_details.ui.components.CoverImage
import me.manga.yamiapk.presentation.features.library_details.ui.components.DownloadMenu
import me.manga.yamiapk.presentation.features.library_details.ui.components.InfoSection
import me.manga.yamiapk.presentation.features.library_details.ui.components.TitleSection
import me.manga.yamiapk.presentation.features.library_details.ui.components.TotalSizeDisplay

@Composable
fun LibraryHeaderSection(
    manga: SavedMangaEntity,
    chapters: List<SavedChapterEntity>,
    isSaved: Boolean,
    onMangaBookmarkClick: (List<SavedChapterEntity>, SavedMangaEntity) -> Unit,
    onRequestBookmark: (Boolean) -> Unit,
    isDownloadingAll: Boolean,
    downloadAll: (List<SavedChapterEntity>) -> Unit,
    onOpenInWebView: (String, String) -> Unit,
    topPadding: Dp = 0.dp,
    selectedChapters: Set<SavedChapterEntity>,
    showChaptersCheckBox: MutableState<Boolean>,
    onCustomDownload: (Set<SavedChapterEntity>, SavedMangaEntity) -> Unit,
    onSelectedChaptersChange: (Set<SavedChapterEntity>) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = 16.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CoverImage(manga.imageUrl, manga.title)
        Spacer(Modifier.height(16.dp))

        TitleSection(manga.title)
        Spacer(Modifier.height(8.dp))

        InfoSection(manga)
        Spacer(Modifier.height(8.dp))

        // Add total size display
        TotalSizeDisplay(
            manga = manga,
            chapters = chapters,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Spacer(Modifier.height(8.dp))

        BannerAdView()
        Spacer(Modifier.height(8.dp))

        GenresAndDescriptionSection(manga.genres, manga.description.orEmpty())
        Spacer(Modifier.height(8.dp))

        ActionsRow(
            isSaved = isSaved,
            chapters = chapters,
            onMangaBookmarkClick = onMangaBookmarkClick,
            onRequestBookmark = onRequestBookmark,
            isDownloadingAll = isDownloadingAll,
            onDownloadMenuClick = { menuExpanded = true },
            onOpenInWebView = onOpenInWebView,
            manga
        )

        DownloadMenu(
            expanded = menuExpanded,
            manga = manga,
            onDismiss = { menuExpanded = false },
            chapters = chapters,
            showChaptersCheckBox = showChaptersCheckBox,
            selectedChapters = selectedChapters,
            downloadAll = { downloadAll(chapters) },
            onCustomDownload = onCustomDownload,
            onSelectedChaptersChange = onSelectedChaptersChange
        )
    }
}