package me.manga.kira.data.repository

import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterIdUrl
import me.manga.kira.data.local.dao.HistoryDao
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral tests for the Epic R reader-strangler `:data` impls
 * ([ChapterBookmarkRepositoryImpl], [MarkChapterReadRepositoryImpl], [HistoryRepositoryImpl]).
 *
 * These three impls already have `:domain` use-case delegation tests, but their own strangler
 * mapping / url→id branching logic was untested. This class exercises that logic directly.
 *
 * Test-double strategy — after RS-3 (task #738) re-pointed the reader-state seam straight at the
 * Room `ChapterDao`, [ChapterBookmarkRepositoryImpl] / [MarkChapterReadRepositoryImpl] take the
 * [ChapterDao] interface directly as a constructor param, so the tests pass the in-memory
 * [FakeChapterDao] straight in (no legacy `:shared` `LibraryRepository` wrapper, no other-DAO /
 * `FileService` scaffolding needed). [HistoryRepositoryImpl] likewise takes the Room [HistoryDao]
 * directly, so its tests pass a [FakeHistoryDao] straight in.
 *
 * Lives in `desktopTest` (not `commonTest`) to keep parity with the Epic T `:data:desktopTest`
 * gate, alongside the History cases.
 */
class ReaderStranglerDataTest {

    // --- ChapterDao fake (the seam the bookmark / mark-read impls now consume directly) ---------

    /**
     * In-memory [ChapterDao] fake. Backs the `saved_chapters`-keyed surface that
     * [ChapterBookmarkRepositoryImpl] / [MarkChapterReadRepositoryImpl] consume directly post-RS-3:
     *  - [getChapterIdByUrl] resolves url → Room `Long` id (null = not-in-library),
     *  - [getChapterById] is the upstream flow the bookmark observer mirrors via `emitAll` + `map`,
     *  - [toggleChapterBookmark] / [markChapterAsRead] are the mutation calls, recorded for asserts.
     */
    private class FakeChapterDao(
        private val urlToId: Map<String, Long> = emptyMap(),
        private val bookmarkFlow: MutableStateFlow<SavedChapterEntity?> = MutableStateFlow(null),
    ) : ChapterDao {
        val toggledBookmarkIds = mutableListOf<Long>()
        val markedReadIds = mutableListOf<Long>()
        val markedIsNewClearedIds = mutableListOf<Long>()

        override suspend fun getChapterIdByUrl(url: String): Long? = urlToId[url]

        override suspend fun getChapterIdsByUrlsBatch(urls: List<String>): List<Long> = urls.mapNotNull { urlToId[it] }

        override suspend fun getChapterIdUrlPairsBatch(urls: List<String>) =
            urls.mapNotNull { url -> urlToId[url]?.let { ChapterIdUrl(id = it, url = url) } }

        override suspend fun getChapterIdUrlPairsForMangaBatch(mangaId: Long, urls: List<String>) =
            urls.mapNotNull { url -> urlToId[url]?.let { ChapterIdUrl(id = it, url = url) } }

        override fun getChapterById(chapterId: Long): Flow<SavedChapterEntity?> = bookmarkFlow

        // url-keyed bookmark stream the post-r2-hot-8 observer mirrors: back it with bookmarkFlow
        // when the seeded row is for this url, else an absent-row flow (null → false).
        override fun getChapterByUrl(url: String): Flow<SavedChapterEntity?> =
            if (bookmarkFlow.value?.url == url) bookmarkFlow else flowOf(null)

        override suspend fun toggleChapterBookmark(chapterId: Long) {
            toggledBookmarkIds += chapterId
        }

        override suspend fun markChapterAsRead(chapterId: Long, currentTime: Long) {
            markedReadIds += chapterId
        }

        // --- unused by the impls under test: inert stubs -----------------------------------------
        override suspend fun getAllDownloadedChapters(): List<SavedChapterEntity> = TODO()
        override fun getChaptersByMangaId(mangaId: Long): Flow<List<SavedChapterEntity>> = TODO()
        override suspend fun insertChapters(chapters: List<SavedChapterEntity>): List<Long> = TODO()
        override suspend fun insertAll(chapters: List<SavedChapterEntity>) = TODO()
        override suspend fun updateChapterLocalPaths(chapterId: Long, paths: List<String>) = TODO()
        override suspend fun markChapterDownloaded(chapterId: Long) = TODO()
        // Used by MarkChapterReadRepositoryImpl.markRead (clear NEW-chapter flag on open) — record it.
        override suspend fun markChapterIsNew(chapterId: Long) { markedIsNewClearedIds += chapterId }
        override suspend fun getChapterByIdSuspend(chapterId: Long): SavedChapterEntity? = TODO()
        override suspend fun markChaptersNotDownloaded(ids: List<Long>, emptyList: List<String>) = TODO()
        override suspend fun deleteChapterById(chapterId: Long) = TODO()
        override suspend fun markChaptersReadBatch(chapterIds: List<Long>) = TODO()
        override suspend fun toggleChaptersReadBatch(chapterIds: List<Long>) = TODO()
        override suspend fun toggleChaptersBookmarkBatch(chapterIds: List<Long>) = TODO()
        override suspend fun getChaptersByMangaIdR(mangaId: Long): List<SavedChapterEntity> = TODO()
        override suspend fun updateChapter(chapter: SavedChapterEntity) = TODO()
    }

    // --- ChapterBookmarkRepositoryImpl.observeBookmark / toggleBookmark -------------------------

    @Test
    fun observeBookmark_emits_false_when_chapter_not_in_library() = runTest {
        // getChapterIdByUrl returns null → documented #217 not-in-library no-op behavior.
        val dao = FakeChapterDao(urlToId = emptyMap())
        val impl = ChapterBookmarkRepositoryImpl(dao)

        impl.observeBookmark("https://src/c1").test {
            assertFalse(awaitItem())
            // Don't pin the single-emit-then-complete behavior (open re-bind gap: the flow should
            // stay alive and re-bind if the chapter row appears later, e.g. the manga is added to
            // the library while the reader is open). Assert only the initial false here.
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeBookmark_forwards_legacy_bookmark_flow_and_tracks_state_changes() = runTest {
        val url = "https://src/c1"
        // url resolves to a real row id → impl must emitAll the legacy isChapterBookmarkedFlow.
        val flow = MutableStateFlow<SavedChapterEntity?>(savedChapter(id = 42L, url = url, isBookmarked = false))
        val dao = FakeChapterDao(urlToId = mapOf(url to 42L), bookmarkFlow = flow)
        val impl = ChapterBookmarkRepositoryImpl(dao)

        impl.observeBookmark(url).test {
            assertFalse(awaitItem()) // initial: not bookmarked
            flow.value = savedChapter(id = 42L, url = url, isBookmarked = true)
            assertTrue(awaitItem()) // passthrough tracks the legacy column flip
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun toggleBookmark_is_noop_when_chapter_not_in_library() = runTest {
        val dao = FakeChapterDao(urlToId = emptyMap())
        val impl = ChapterBookmarkRepositoryImpl(dao)

        impl.toggleBookmark("https://src/missing")

        assertTrue(dao.toggledBookmarkIds.isEmpty()) // legacy.toggleChapterBookmark never called
    }

    @Test
    fun toggleBookmark_delegates_with_resolved_id() = runTest {
        val url = "https://src/c1"
        val dao = FakeChapterDao(urlToId = mapOf(url to 7L))
        val impl = ChapterBookmarkRepositoryImpl(dao)

        impl.toggleBookmark(url)

        assertEquals(listOf(7L), dao.toggledBookmarkIds)
    }

    // --- MarkChapterReadRepositoryImpl.markRead -------------------------------------------------

    @Test
    fun markRead_is_noop_when_chapter_not_in_library() = runTest {
        val dao = FakeChapterDao(urlToId = emptyMap())
        val impl = MarkChapterReadRepositoryImpl(dao)

        impl.markRead("https://src/missing")

        assertTrue(dao.markedReadIds.isEmpty()) // legacy.markChapterAsRead never called
    }

    @Test
    fun markRead_delegates_with_resolved_id() = runTest {
        val url = "https://src/c9"
        val dao = FakeChapterDao(urlToId = mapOf(url to 99L))
        val impl = MarkChapterReadRepositoryImpl(dao)

        impl.markRead(url)

        assertEquals(listOf(99L), dao.markedReadIds)
        // Opening/reading a chapter also clears its NEW flag (native parity).
        assertEquals(listOf(99L), dao.markedIsNewClearedIds)
    }

    // --- HistoryDao fake + HistoryRepositoryImpl ------------------------------------------------

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
        override suspend fun updateMangaImageUrl(mangaId: Long, newImageUrl: String) = TODO()
        override suspend fun updateMangaImageUrlByUrl(mangaUrl: String, newImageUrl: String) = TODO()
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
    fun record_maps_manga_and_chapter_to_history_entity() = runTest {
        val dao = FakeHistoryDao()
        val impl = HistoryRepositoryImpl(dao)

        val manga = Manga(
            api = "MangaDex",
            language = "en",
            title = "Naruto",
            url = "https://md/naruto",
            coverUrl = "https://md/naruto.jpg",
            rating = 9,
            genres = listOf("Action"),
        )
        val chapter = Chapter(
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
    fun observeHistory_maps_entities_to_domain() = runTest {
        val entity = HistoryItemD(
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
    fun deleteEntry_round_trips_through_toEntity_and_delegates() = runTest {
        val dao = FakeHistoryDao()
        val impl = HistoryRepositoryImpl(dao)

        // observeHistory().toDomain() is the source of HistoryEntry instances; build one via the
        // same mapping by recording then reading back through observeHistory.
        val entity = HistoryItemD(
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
        val domainEntry = seededImpl.observeHistory().let { flow ->
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
    fun deleteAll_delegates_to_dao() = runTest {
        val dao = FakeHistoryDao()
        val impl = HistoryRepositoryImpl(dao)

        impl.deleteAll()

        assertEquals(1, dao.deleteAllCalls)
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun savedChapter(id: Long, url: String, isBookmarked: Boolean): SavedChapterEntity =
        SavedChapterEntity(
            id = id,
            mangaId = 1L,
            number = "1",
            name = "",
            url = url,
            isDownloaded = false,
            isBookmarked = isBookmarked,
            isRead = false,
        )
}
