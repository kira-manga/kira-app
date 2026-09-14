package me.manga.kira.presentation.details

import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.MangaDetails
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins read-time ordering without changing the existing directional ties or Resume target. */
class DetailsStateLastReadSortTest {
    @Test
    fun lastReadSort_usesStoredTimes_andReversesTiesWithDirection() {
        val descending =
            state(
                chapter("c/6", lastReadAtEpochMillis = 200L),
                chapter("c/5", isRead = true),
                chapter("c/4", lastReadAtEpochMillis = 300L, isRead = true),
                chapter("c/3", lastReadAtEpochMillis = 200L),
                chapter("c/2", isRead = true),
                chapter("c/1", lastReadAtEpochMillis = 100L, isRead = true),
            )
        val ascending = descending.copy(sortAscending = true)

        assertEquals(
            listOf("c/4", "c/6", "c/3", "c/1", "c/5", "c/2"),
            descending.displayChapters.map(Chapter::url),
        )
        assertEquals(
            listOf("c/2", "c/5", "c/1", "c/3", "c/6", "c/4"),
            ascending.displayChapters.map(Chapter::url),
        )
        assertEquals("c/3", descending.firstUnreadChapter?.url)
        assertEquals("c/3", ascending.firstUnreadChapter?.url)
    }

    @Test
    fun allUnknownReadTimes_matchIdOrder_andResumeInBothDirections() {
        val unknown =
            state(
                chapter("c/4"),
                chapter("c/3", isRead = true),
                chapter("c/2"),
                chapter("c/1"),
            )

        for (ascending in listOf(false, true)) {
            val byId = unknown.copy(chapterSort = ChapterSortType.ID, sortAscending = ascending)
            val byLastRead = byId.copy(chapterSort = ChapterSortType.LAST_READ_DATE)
            assertEquals(
                byId.displayChapters.map(Chapter::url),
                byLastRead.displayChapters.map(Chapter::url),
            )
            assertEquals(byId.firstUnreadChapter?.url, byLastRead.firstUnreadChapter?.url)
            assertEquals("c/1", byLastRead.firstUnreadChapter?.url)
        }
    }

    private fun state(vararg chapters: Chapter) =
        DetailsState(
            details =
                MangaDetails(
                    api = "src",
                    language = "en",
                    title = "Manga",
                    url = "https://example/manga",
                    coverUrl = "",
                    description = "",
                    author = "",
                    rating = "",
                    status = "",
                    genres = emptyList(),
                    chapters = chapters.toList(),
                ),
            chapterSort = ChapterSortType.LAST_READ_DATE,
        )

    private fun chapter(
        url: String,
        lastReadAtEpochMillis: Long = 0,
        isRead: Boolean = false,
    ) = Chapter(
        number = url.substringAfterLast('/'),
        name = "",
        url = url,
        date = null,
        isDownloaded = false,
        isBookmarked = false,
        isRead = isRead,
        lastReadAtEpochMillis = lastReadAtEpochMillis,
    )
}
