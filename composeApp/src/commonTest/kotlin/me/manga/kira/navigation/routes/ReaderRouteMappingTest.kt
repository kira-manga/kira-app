package me.manga.kira.navigation.routes

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.domain.model.history.HistoryEntry
import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderRouteMappingTest {
    @Test
    fun historyReaderRoutePreservesIdentityWithoutLocalPaths() {
        val entry = historyEntryForReaderRoute(loosePagePathsForReaderRoute())
        val expected =
            Screen.ChapterImagesFragment(
                isHome = false,
                api = "history-source",
                language = "ar",
                mangaId = 13L,
                chapterId = 17L,
                mangatitle = "History manga & title",
                mangaUrl = "https://history.example/manga/story?source=history",
                mangaImgUrl = "https://history.example/covers/13.webp",
                chapterNumber = "History chapter 2.5",
                chapterUrl = "https://history.example/chapter/17?part=1",
                paths = null,
                isDownload = true,
            )

        assertEquals(expected, entry.toReaderRoute())
        assertEquals(expected, entry.copy(localImagePaths = emptyList()).toReaderRoute())
        assertEquals(
            expected.copy(isDownload = false),
            entry.copy(isDownloaded = false).toReaderRoute(),
        )
    }

    @Test
    fun updatesReaderRoutePreservesChapterIdentityWithoutLocalPaths() {
        val entry = updateEntryForReaderRoute(loosePagePathsForReaderRoute())
        val expected =
            Screen.ChapterImagesFragment(
                isHome = false,
                api = "updates-source",
                language = "en",
                mangaId = 41L,
                chapterId = 53L,
                mangatitle = "Updates manga #2",
                mangaUrl = "https://updates.example/manga/other?source=updates",
                mangaImgUrl = "https://updates.example/covers/41.webp",
                chapterNumber = "7.25",
                chapterUrl = "https://updates.example/chapter/53?part=2",
                paths = null,
                isDownload = true,
            )

        assertEquals(expected, entry.toReaderRoute())
        assertEquals(expected, entry.copy(localImagePaths = emptyList()).toReaderRoute())
        assertEquals(
            expected.copy(isDownload = false),
            entry.copy(isDownloaded = false).toReaderRoute(),
        )
    }
}

internal fun historyEntryForReaderRoute(localImagePaths: List<String> = emptyList()): HistoryEntry =
    HistoryEntry(
        id = 17L,
        api = "history-source",
        language = "ar",
        mangaId = 13L,
        mangaUrl = "https://history.example/manga/story?source=history",
        mangaTitle = "History manga & title",
        mangaImageUrl = "https://history.example/covers/13.webp",
        chapterUrl = "https://history.example/chapter/17?part=1",
        chapterTitle = "History chapter 2.5",
        isDownloaded = true,
        localImagePaths = localImagePaths,
        lastReadDate = LocalDateTime(2026, 9, 11, 17, 45),
        lastReadPage = 5,
        totalPages = 4_000,
    )

internal fun updateEntryForReaderRoute(localImagePaths: List<String> = emptyList()): UpdateEntry =
    UpdateEntry(
        id = 31L,
        api = "updates-source",
        language = "en",
        mangaId = 41L,
        mangaTitle = "Updates manga #2",
        mangaImageUrl = "https://updates.example/covers/41.webp",
        mangaUrl = "https://updates.example/manga/other?source=updates",
        chapterId = 53L,
        chapterNumber = "7.25",
        chapterUrl = "https://updates.example/chapter/53?part=2",
        notificationDate = LocalDate(2026, 9, 12),
        isRead = true,
        isDownloaded = true,
        localImagePaths = localImagePaths,
    )

internal fun loosePagePathsForReaderRoute(): List<String> =
    List(4_000) { index ->
        "/data/user/0/me.manga.kira/files/manga/13/chapter_17/$index.webp"
    }
