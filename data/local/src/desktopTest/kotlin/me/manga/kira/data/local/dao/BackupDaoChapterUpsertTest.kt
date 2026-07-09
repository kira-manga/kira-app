package me.manga.kira.data.local.dao

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-database tests of [BackupDao.importMangaMerging] against an in-memory [MangaDatabase] —
 * centered on the owner's explicit chapter-upsert guarantee: a backup carrying chapters 1–100
 * imported over a local library holding 1–95 must end with local chapters 1–100 (96–100 created
 * from the backup), with 1–95's read/bookmark state merged, nothing deleted, and a re-run of the
 * same import converging to the identical state.
 *
 * The merge lambdas passed here replicate :data's BackupMergePolicy rules (OR flags, newer
 * lastReadDate) — the policy itself is unit-tested in :data; this class tests the DAO mechanics.
 */
class BackupDaoChapterUpsertTest {

    private lateinit var db: MangaDatabase
    private lateinit var dao: BackupDao

    @BeforeTest
    fun open() {
        db = Room
            .inMemoryDatabaseBuilder<MangaDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()
        dao = db.backupDao()
    }

    @AfterTest
    fun close() = db.close()

    private val mergeManga: (SavedMangaEntity, SavedMangaEntity) -> SavedMangaEntity =
        { local, incoming ->
            local.copy(
                isLiked = local.isLiked || incoming.isLiked,
                isWatchingNow = local.isWatchingNow || incoming.isWatchingNow,
                lastOpenTimestamp = maxOf(local.lastOpenTimestamp, incoming.lastOpenTimestamp),
            )
        }

    private val mergeChapter: (SavedChapterEntity, SavedChapterEntity) -> SavedChapterEntity =
        { local, incoming ->
            local.copy(
                isRead = local.isRead || incoming.isRead,
                isBookmarked = local.isBookmarked || incoming.isBookmarked,
                lastReadDate = maxOf(local.lastReadDate, incoming.lastReadDate),
            )
        }

    private fun manga(
        url: String = "https://azora/manga/1",
        api: String = "azora",
        title: String = "Solo Leveling",
    ) = SavedMangaEntity(
        id = 0,
        api = api,
        language = "ar",
        url = url,
        imageUrl = "https://azora/img.png",
        title = title,
        description = "desc",
        status = "Ongoing",
        rating = null,
        genres = listOf("action"),
        savedTimestamp = 100,
        lastOpenTimestamp = 100,
        isLiked = false,
        isWatchingNow = false,
    )

    /** Incoming rows carry id = 0 / mangaId = 0 — the DAO resolves both. */
    private fun incomingChapter(
        n: Int,
        isRead: Boolean = false,
        isBookmarked: Boolean = false,
        lastReadDate: Long = 0,
    ) = SavedChapterEntity(
        id = 0,
        mangaId = 0,
        name = "Chapter $n",
        number = "$n",
        url = "https://azora/ch/$n",
        date = LocalDate(2026, 1, 1),
        isDownloaded = false,
        isBookmarked = isBookmarked,
        isRead = isRead,
        isNew = false,
        lastReadPage = 0,
        lastReadDate = lastReadDate,
        localImagePaths = emptyList(),
        fetchedAt = 0,
    )

    private suspend fun seedLocalLibrary(chapterCount: Int): Long {
        val mangaId = dao.insertMangaRow(manga())
        for (n in 1..chapterCount) {
            dao.insertChapterRow(
                incomingChapter(
                    n = n,
                    // Local device: 1..50 read, ch 10 bookmarked, ch 95 has a recent read stamp.
                    isRead = n <= 50,
                    isBookmarked = n == 10,
                    lastReadDate = if (n == 95) 5_000 else 0,
                ).copy(
                    mangaId = mangaId,
                    // ch 20 is downloaded on this device — the merge must not touch that.
                    isDownloaded = n == 20,
                    localImagePaths = if (n == 20) listOf("/device/ch20.cbz") else emptyList(),
                ),
            )
        }
        return mangaId
    }

    @Test
    fun backup_with_chapters_1_to_100_over_local_1_to_95_ends_at_1_to_100() = runTest {
        val mangaId = seedLocalLibrary(chapterCount = 95)

        // The backup device read everything up to ch 100.
        val backupChapters = (1..100).map { n ->
            incomingChapter(n = n, isRead = true, lastReadDate = 9_000)
        }

        val result = dao.importMangaMerging(manga(), backupChapters, mergeManga, mergeChapter)

        assertEquals(mangaId, result.mangaId)
        assertFalse(result.mangaWasNew)
        assertEquals(5, result.chaptersAdded, "96-100 created from the backup")
        assertEquals(95, result.chaptersMerged)

        val chapters = dao.getChaptersForManga(mangaId)
        assertEquals(100, chapters.size, "local library now holds 1-100, nothing deleted")
        val byNumber = chapters.associateBy { it.number.toInt() }
        for (n in 96..100) {
            val created = assertNotNull(byNumber[n], "chapter $n was inserted from the backup")
            assertTrue(created.isRead, "backup read state carried onto the new row")
            assertEquals(9_000, created.lastReadDate)
            assertEquals(mangaId, created.mangaId, "resolved local mangaId overwrote the placeholder")
        }
        // Existing rows merged, never regressed.
        assertTrue(byNumber.getValue(10).isBookmarked, "local bookmark survives")
        assertTrue(byNumber.getValue(70).isRead, "locally-unread chapter picked up the backup's read flag")
        assertEquals(9_000, byNumber.getValue(95).lastReadDate, "newer backup read stamp wins")
        assertTrue(byNumber.getValue(20).isDownloaded, "local download state untouched by the merge")
        assertEquals(listOf("/device/ch20.cbz"), byNumber.getValue(20).localImagePaths)

        // Per-chapter results expose what the caller needs for resume-page restoration.
        val ch95 = assertNotNull(result.chaptersByUrl["https://azora/ch/95"])
        assertFalse(ch95.wasNew)
        assertEquals(5_000, ch95.localLastReadDateBefore, "pre-merge value, unrecoverable after the write")
        val ch100 = assertNotNull(result.chaptersByUrl["https://azora/ch/100"])
        assertTrue(ch100.wasNew)
        assertEquals(0, ch100.localLastReadDateBefore)
    }

    @Test
    fun rerunning_the_same_import_is_idempotent() = runTest {
        val mangaId = seedLocalLibrary(chapterCount = 95)
        val backupChapters = (1..100).map { n ->
            incomingChapter(n = n, isRead = true, lastReadDate = 9_000)
        }

        dao.importMangaMerging(manga(), backupChapters, mergeManga, mergeChapter)
        val afterFirst = dao.getChaptersForManga(mangaId)

        val second = dao.importMangaMerging(manga(), backupChapters, mergeManga, mergeChapter)

        assertEquals(0, second.chaptersAdded, "every chapter already present on the re-run")
        assertEquals(100, second.chaptersMerged)
        assertEquals(afterFirst, dao.getChaptersForManga(mangaId), "second import converges to the identical state")
    }

    @Test
    fun manga_absent_locally_is_created_with_all_its_chapters() = runTest {
        val backupChapters = (1..3).map { n -> incomingChapter(n = n, isRead = n == 1) }

        val result = dao.importMangaMerging(manga(), backupChapters, mergeManga, mergeChapter)

        assertTrue(result.mangaWasNew)
        assertEquals(3, result.chaptersAdded)
        assertEquals(0, result.chaptersMerged)
        val saved = assertNotNull(dao.getMangaByUrl("https://azora/manga/1"))
        assertEquals(result.mangaId, saved.id)
        assertEquals(3, dao.getChaptersForManga(result.mangaId).size)
    }

    @Test
    fun manga_resolution_falls_back_to_api_and_title_when_url_moved() = runTest {
        val mangaId = seedLocalLibrary(chapterCount = 1)

        // Same manga exported from a device that saved it under a moved host url.
        val result = dao.importMangaMerging(
            manga(url = "https://azora-new-host/manga/1"),
            listOf(incomingChapter(n = 1, isRead = true)),
            mergeManga,
            mergeChapter,
        )

        assertEquals(mangaId, result.mangaId, "resolved by (api, title), no duplicate manga row")
        assertFalse(result.mangaWasNew)
        assertEquals(1, dao.getChaptersForManga(mangaId).size)
        assertTrue(assertNotNull(dao.getChapterByMangaAndUrl(mangaId, "https://azora/ch/1")).isRead)
    }

    @Test
    fun chapter_lookup_is_mangaId_scoped_so_shared_urls_do_not_cross_mangas() = runTest {
        // Two mangas whose chapters share the same url (relative-path sources make this real).
        val otherId = dao.insertMangaRow(manga(url = "https://other/manga", api = "other", title = "Other"))
        dao.insertChapterRow(incomingChapter(n = 1).copy(mangaId = otherId))

        val result = dao.importMangaMerging(
            manga(),
            listOf(incomingChapter(n = 1, isRead = true)),
            mergeManga,
            mergeChapter,
        )

        assertEquals(1, result.chaptersAdded, "same url under a different manga does not count as present")
        val otherChapter = assertNotNull(dao.getChapterByMangaAndUrl(otherId, "https://azora/ch/1"))
        assertFalse(otherChapter.isRead, "the other manga's row was not touched")
    }

    @Test
    fun markChapterRestored_flips_only_the_download_columns() = runTest {
        val mangaId = seedLocalLibrary(chapterCount = 1)
        val before = assertNotNull(dao.getChapterByMangaAndUrl(mangaId, "https://azora/ch/1"))

        dao.markChapterRestored(
            RestoredChapterUpdate(
                id = before.id,
                isDownloaded = true,
                localImagePaths = listOf("/restored/ch1.cbz"),
            ),
        )

        val after = assertNotNull(dao.getChapterByMangaAndUrl(mangaId, "https://azora/ch/1"))
        assertTrue(after.isDownloaded)
        assertEquals(listOf("/restored/ch1.cbz"), after.localImagePaths)
        assertEquals(
            before.copy(isDownloaded = true, localImagePaths = listOf("/restored/ch1.cbz")),
            after,
            "partial-entity update left every other column alone",
        )
    }

    // --- history merge ---------------------------------------------------------------------------

    private fun historyRow(
        readAt: LocalDateTime,
        chapterUrl: String = "https://azora/ch/95",
        page: Int = 4,
    ) = HistoryItemD(
        id = 0,
        api = "azora",
        language = "ar",
        mangaId = 1,
        mangaUrl = "https://azora/manga/1",
        mangaTitle = "Solo Leveling",
        mangaImageUrl = "https://azora/img.png",
        chapterUrl = chapterUrl,
        chapterTitle = "Chapter",
        isDownloaded = false,
        localImagePaths = emptyList(),
        lastReadDate = readAt,
        lastReadPage = page,
        totalPages = 20,
    )

    @Test
    fun history_absent_inserts_newer_replaces_position_older_keeps_local() = runTest {
        val older = LocalDateTime(2026, 1, 1, 10, 0)
        val newer = LocalDateTime(2026, 2, 1, 10, 0)
        val newerWins: (HistoryItemD, HistoryItemD) -> Boolean =
            { local, incoming -> incoming.lastReadDate > local.lastReadDate }

        // Absent -> insert.
        dao.importHistoryMerging(historyRow(older, chapterUrl = "https://azora/ch/90", page = 2), newerWins)
        val inserted = assertNotNull(dao.getHistoryByMangaUrl("https://azora/manga/1"))
        assertEquals("https://azora/ch/90", inserted.chapterUrl)

        // Newer backup read -> position fields replaced, row identity kept.
        dao.importHistoryMerging(historyRow(newer, chapterUrl = "https://azora/ch/95", page = 7), newerWins)
        val replaced = assertNotNull(dao.getHistoryByMangaUrl("https://azora/manga/1"))
        assertEquals(inserted.id, replaced.id, "same row, merged in place")
        assertEquals("https://azora/ch/95", replaced.chapterUrl)
        assertEquals(7, replaced.lastReadPage)
        assertEquals(newer, replaced.lastReadDate)

        // Older backup read -> local kept.
        dao.importHistoryMerging(historyRow(older, chapterUrl = "https://azora/ch/1", page = 1), newerWins)
        assertEquals(replaced, assertNotNull(dao.getHistoryByMangaUrl("https://azora/manga/1")))
    }
}
