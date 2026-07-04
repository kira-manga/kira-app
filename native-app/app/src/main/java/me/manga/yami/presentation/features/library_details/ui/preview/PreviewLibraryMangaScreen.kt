package me.manga.yamiapk.presentation.features.library_details.ui.preview

import androidx.compose.material.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.presentation.features.home.data.ApiTitle
import me.manga.yamiapk.presentation.features.library_details.ui.screens.LibraryMangaScreen
import me.manga.yamiapk.presentation.features.library_details.ui.viewmodel.LibraryDetailsViewModel
import me.manga.yamiapk.theme.YamiMangaTheme


@Composable
@Preview(showBackground = true)
fun PreviewLibraryMangaDetailsScreenClean() {
    YamiMangaTheme(darkTheme = true, pureBlack = true) {
        val mockManga = SavedMangaEntity(
            id = 1L,
            api = "MockAPI",
            language = "EN",
            url = "https://example.com/manga/1",
            imageUrl = "https://via.placeholder.com/200x250.png?text=Manga+Image",
            title = "Sample Manga Title",
            description = "This is a sample manga description used for preview purposes.",
            status = "Ongoing",
            rating = "8.5",
            genres = listOf("Action", "Adventure", "Fantasy")
        )

        val mockChapters = List(5) { index ->
            SavedChapterEntity(
                id = index.toLong(),
                mangaId = 1L,
                name = "Chapter ${index + 1}",
                number = " ${index + 265.5}",
                url = "https://example.com/chapter/${index + 1}",
                isDownloaded = true,
                isBookmarked = index == 2,
                isRead = index == 1,
            )
        }

        LibraryMangaScreen(
            manga = mockManga,
            chapters = mockChapters,
            savedTitles = setOf(ApiTitle(api = "", title = "")),
            onBackClick = {},
            onChapterClick = { manga, chapter, _ ->

            },
            onChapterDownloadClick = { ds, sd -> },
            onChapterBookmarkClick = {},
            downloadAllManga = {},
            cancelAllDownloads = {},
            isDownloadingAll = false,
            downloadingChapters = listOf(),
            onChapterReadClick = {},
            onRefreshClick = {},
            isRefreshing = true,
            onCustomDownload = { m, f -> },
            sortAscending = true,
            toggleSort = {},
            onBookmarkAll = {},
            onMarkAllRead = {},
            runningChapter = null,
            onOpenInWebView = { _, _ -> },
            snackbarHostState = SnackbarHostState(),
            onMangaBookmarkClick = { _, _ -> },
            onCancelRunningChapter = { _, _ -> },
            onCancelChapter = { _, _ -> },
            onMarkAllDownRead = {},
            onDeleteAll = {},
            onFilterItemSelected = { },
            selectedFilter = LibraryDetailsViewModel.FilterType.BOOKMARKED,
            selectedSort = LibraryDetailsViewModel.SortType.ID,
            onSortItemSelected = {}
        )
    }
}