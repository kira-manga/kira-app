package me.manga.kira.data.repository

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.local.dao.HistoryDao
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.mapper.toDomainManga
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Shared-mobile adapter contracts: real Room for ownership, unchanged history mapping fixtures. */
class ReaderStranglerDataTest {
    private val owner = libraryParent().toDomainManga()

    @Test
    fun observeBookmark_emits_false_when_chapter_not_in_library() = runTest {
        LibraryIdentityFixture().use { f ->
            ChapterBookmarkRepositoryImpl(f.owners, f.db.chapterDao()).observeBookmark(owner, "https://current.test/c1").test {
                assertFalse(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun observeBookmark_forwards_legacy_bookmark_flow_and_tracks_state_changes() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val row = f.chapter(librarySavedChapter(parent).copy(isBookmarked = false))
            ChapterBookmarkRepositoryImpl(f.owners, f.db.chapterDao()).observeBookmark(owner, row.url).test {
                assertFalse(awaitItem())
                f.db.chapterDao().toggleChapterBookmark(row.id)
                assertTrue(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun toggleBookmark_is_noop_when_chapter_not_in_library() = runTest {
        LibraryIdentityFixture().use { f ->
            val impl = ChapterBookmarkRepositoryImpl(f.owners, f.db.chapterDao())
            assertFalse(impl.toggleBookmark(owner, "https://current.test/missing"))
            assertTrue(f.db.backupDao().getAllSavedManga().isEmpty())
        }
    }

    @Test
    fun toggleBookmark_delegates_with_resolved_id() = runTest {
        LibraryIdentityFixture().use { f ->
            val row = f.chapter(librarySavedChapter(f.parent()).copy(isBookmarked = false))
            val impl = ChapterBookmarkRepositoryImpl(f.owners, f.db.chapterDao())
            assertTrue(impl.toggleBookmark(owner, row.url))
            assertEquals(row.copy(isBookmarked = true), f.db.chapterDao().getChapterByIdSuspend(row.id))
        }
    }

    @Test
    fun markRead_is_noop_when_chapter_not_in_library() = runTest {
        LibraryIdentityFixture().use { f ->
            MarkChapterReadRepositoryImpl(f.owners, f.db.chapterDao()).markRead(owner, "https://current.test/missing")
            assertTrue(f.db.backupDao().getAllSavedManga().isEmpty())
        }
    }

    @Test
    fun markRead_delegates_with_resolved_id() = runTest {
        LibraryIdentityFixture().use { f ->
            val row = f.chapter(librarySavedChapter(f.parent()).copy(isRead = false))
            MarkChapterReadRepositoryImpl(f.owners, f.db.chapterDao()).markRead(owner, row.url)
            val after = requireNotNull(f.db.chapterDao().getChapterByIdSuspend(row.id))
            assertTrue(after.isRead)
            assertFalse(after.isNew)
            assertTrue(after.lastReadDate > row.lastReadDate)
        }
    }

    /** In-memory [HistoryDao] fake backing [HistoryRepositoryImpl] directly. */
    private class FakeHistoryDao(
        private val allHistory: MutableStateFlow<List<HistoryItemD>> = MutableStateFlow(emptyList()),
    ) : HistoryDao {
        val inserted = mutableListOf<HistoryItemD>()
        val deleted = mutableListOf<HistoryItemD>()
        var deleteAllCalls = 0

        override fun getAllHistory(): Flow<List<HistoryItemD>> = allHistory

        override suspend fun insertHistory(historyItemD: HistoryItemD) {
            inserted += historyItemD
        }

        override suspend fun deleteHistory(historyItemD: HistoryItemD) {
            deleted += historyItemD
        }

        override suspend fun deleteAllHistory() {
            deleteAllCalls++
        }

        // insertOrUpdateHistory is a default-bodied @Transaction on the interface that upserts by
        // mangaUrl: it calls getHistoryItemByMangaUrl then either updateHistory or insertHistory.
        // We leave the lookup returning null so the record() path routes to insertHistory (captured).
        override suspend fun getHistoryItemByMangaUrl(mangaUrl: String): HistoryItemD? = null

        // --- unused by the impl under test: inert stubs ------------------------------------------
        override suspend fun updateMangaImageUrl(
            mangaId: Long,
            newImageUrl: String,
        ) = TODO()

        override suspend fun updateMangaImageUrlByUrl(
            mangaUrl: String,
            newImageUrl: String,
        ) = TODO()

        override suspend fun updateHistory(historyItemD: HistoryItemD) = TODO()

        override suspend fun updateHistoryItem(
            id: Long,
            chapterUrl: String,
            chapterTitle: String,
            isDownloaded: Boolean,
            localImagePaths: List<String>,
            lastReadDate: LocalDateTime,
            lastReadPage: Int,
            totalPages: Int,
        ) = TODO()

        override suspend fun getHistoryByApi(api: String): List<HistoryItemD> = TODO()
    }

    @Test
    fun record_maps_manga_and_chapter_to_history_entity() =
        runTest {
            val dao = FakeHistoryDao()
            val impl = HistoryRepositoryImpl(dao)

            val manga =
                Manga(
                    api = "MangaDex",
                    language = "en",
                    title = "Naruto",
                    url = "https://md/naruto",
                    coverUrl = "https://md/naruto.jpg",
                    rating = 9,
                    genres = listOf("Action"),
                )
            val chapter =
                Chapter(
                    number = "700",
                    name = "The End",
                    url = "https://md/c700",
                    date = LocalDate(2024, 1, 1),
                    isDownloaded = true,
                    isBookmarked = false,
                )

            impl.record(manga, chapter)

            assertEquals(1, dao.inserted.size)
            val row = dao.inserted.single()
            assertEquals("MangaDex", row.api)
            assertEquals("en", row.language)
            assertEquals(0L, row.mangaId) // rework Manga has no surrogate id; upsert keys on mangaUrl
            assertEquals("https://md/naruto", row.mangaUrl)
            assertEquals("Naruto", row.mangaTitle)
            assertEquals("https://md/naruto.jpg", row.mangaImageUrl)
            assertEquals("https://md/c700", row.chapterUrl)
            assertEquals("700", row.chapterTitle) // chapter.number is stored in chapterTitle (parity)
            assertTrue(row.isDownloaded)
        }

    @Test
    fun observeHistory_maps_entities_to_domain() =
        runTest {
            val entity =
                HistoryItemD(
                    id = 5L,
                    api = "src",
                    language = "en",
                    mangaId = 3L,
                    mangaUrl = "https://src/m",
                    mangaTitle = "Title",
                    mangaImageUrl = "https://src/m.jpg",
                    chapterUrl = "https://src/c",
                    chapterTitle = "12",
                    isDownloaded = false,
                    lastReadDate = LocalDateTime(2024, 2, 3, 4, 5),
                    lastReadPage = 7,
                    totalPages = 20,
                )
            val dao = FakeHistoryDao(MutableStateFlow(listOf(entity)))
            val impl = HistoryRepositoryImpl(dao)

            impl.observeHistory().test {
                val list = awaitItem()
                assertEquals(1, list.size)
                val domain = list.single()
                assertEquals(5L, domain.id)
                assertEquals("src", domain.api)
                assertEquals(3L, domain.mangaId)
                assertEquals("https://src/c", domain.chapterUrl)
                assertEquals("12", domain.chapterTitle)
                assertEquals(7, domain.lastReadPage)
                assertEquals(20, domain.totalPages)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun deleteEntry_round_trips_through_toEntity_and_delegates() =
        runTest {
            val dao = FakeHistoryDao()
            val impl = HistoryRepositoryImpl(dao)

            // observeHistory().toDomain() is the source of HistoryEntry instances; build one via the
            // same mapping by recording then reading back through observeHistory.
            val entity =
                HistoryItemD(
                    id = 11L,
                    api = "src",
                    language = "ar",
                    mangaId = 1L,
                    mangaUrl = "https://src/m1",
                    mangaTitle = "M1",
                    mangaImageUrl = "https://src/m1.jpg",
                    chapterUrl = "https://src/c1",
                    chapterTitle = "1",
                    isDownloaded = true,
                    lastReadDate = LocalDateTime(2024, 5, 6, 7, 8),
                    lastReadPage = 0,
                    totalPages = 0,
                )
            val seeded = FakeHistoryDao(MutableStateFlow(listOf(entity)))
            val seededImpl = HistoryRepositoryImpl(seeded)
            val domainEntry =
                seededImpl.observeHistory().let { flow ->
                    var captured: List<me.manga.kira.domain.model.history.HistoryEntry>? = null
                    flow.test {
                        captured = awaitItem()
                        cancelAndIgnoreRemainingEvents()
                    }
                    captured!!.single()
                }

            impl.deleteEntry(domainEntry)

            assertEquals(1, dao.deleted.size)
            val back = dao.deleted.single()
            assertEquals(11L, back.id) // entry → entity round-trip preserves the primary key
            assertEquals("https://src/c1", back.chapterUrl)
            assertEquals(true, back.isDownloaded)
        }

    @Test
    fun deleteAll_delegates_to_dao() =
        runTest {
            val dao = FakeHistoryDao()
            val impl = HistoryRepositoryImpl(dao)

            impl.deleteAll()

            assertEquals(1, dao.deleteAllCalls)
        }

}
