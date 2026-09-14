package me.manga.kira.data.local.dao

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StatisticsDaoCompletedTest {
    private lateinit var db: MangaDatabase

    @BeforeTest
    fun open() {
        db =
            Room
                .inMemoryDatabaseBuilder<MangaDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }

    @AfterTest
    fun close() = db.close()

    @Test
    fun completedCountRequiresOwnChaptersAndAllOfThemRead() =
        runBlocking {
            withTimeout(SCENARIO_TIMEOUT_MILLIS) {
                val chapterSets =
                    listOf(emptyList(), listOf(true), listOf(true, true), listOf(true, false), listOf(false, false))
                chapterSets.forEachIndexed { index, readFlags ->
                    val mangaId = saveEmptyManga("case-$index")
                    db.chapterDao().insertChapters(
                        readFlags.mapIndexed { chapterIndex, isRead -> chapter(mangaId, chapterIndex + 1, isRead) },
                    )
                }

                assertEquals(2, db.statisticsDeo().getCompletedMangaCount().first())
            }
        }

    @Test
    fun completedCountReactsToReadChangesAndFirstAndLastChapter() =
        runBlocking {
            withTimeout(SCENARIO_TIMEOUT_MILLIS) {
                val mangaId = saveEmptyManga("reactive")
                val counts = Channel<Int>(Channel.UNLIMITED)
                val collector =
                    launch {
                        db.statisticsDeo().getCompletedMangaCount().distinctUntilChanged().collect { counts.send(it) }
                    }

                try {
                    assertCompletedTransitions(mangaId, counts)
                    assertEquals(mangaId, db.libraryDeo().getMangaIdByUrl("https://statistics.invalid/manga/reactive"))
                } finally {
                    withContext(NonCancellable) { collector.cancelAndJoin() }
                    counts.cancel()
                }
            }
        }

    private suspend fun assertCompletedTransitions(mangaId: Long, counts: ReceiveChannel<Int>) {
        val chapters = db.chapterDao()
        assertEquals(0, counts.receive(), "An empty saved manga is not completed")

        val first = chapters.insertChapters(listOf(chapter(mangaId, 1, isRead = true))).single()
        assertEquals(1, counts.receive(), "The first read chapter makes the manga completed")

        chapters.toggleChaptersRead(listOf(first))
        assertEquals(0, counts.receive(), "An unread chapter makes the manga incomplete")

        chapters.markChapterAsRead(first, currentTime = 100)
        assertEquals(1, counts.receive(), "Reading the last unread chapter completes the manga")

        val second = chapters.insertChapters(listOf(chapter(mangaId, 2, isRead = false))).single()
        assertEquals(0, counts.receive(), "A newly added unread chapter makes the manga incomplete")

        chapters.deleteChapterById(second)
        assertEquals(1, counts.receive(), "Removing the unread chapter leaves a completed manga")

        chapters.deleteChapterById(first)
        assertEquals(0, counts.receive(), "Removing the last chapter must remove completion")
    }

    private suspend fun saveEmptyManga(name: String): Long {
        val manga =
            SavedMangaEntity(
                api = "statistics-test",
                language = "en",
                url = "https://statistics.invalid/manga/$name",
                imageUrl = "https://statistics.invalid/cover.png",
                title = name,
                description = "Statistics fixture",
                status = "Ongoing",
                rating = null,
                genres = emptyList(),
                savedTimestamp = 100,
                lastOpenTimestamp = 100,
            )
        db.libraryDeo().saveMangaWithChapters(manga, emptyList())
        return assertNotNull(db.libraryDeo().getMangaIdByUrl(manga.url), "Empty chapter sets remain valid library entries")
    }

    private fun chapter(mangaId: Long, number: Int, isRead: Boolean) =
        SavedChapterEntity(
            mangaId = mangaId,
            name = "Chapter $number",
            number = number.toString(),
            url = "https://statistics.invalid/manga/$mangaId/chapter/$number",
            date = LocalDate(2026, 1, 1),
            isRead = isRead,
        )

    private companion object {
        const val SCENARIO_TIMEOUT_MILLIS = 15_000L
    }
}
