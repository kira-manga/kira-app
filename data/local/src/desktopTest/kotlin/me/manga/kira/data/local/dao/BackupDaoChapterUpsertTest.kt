package me.manga.kira.data.local.dao

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Policy-free Room primitives and unchanged download-publication controls. Owned merge, volume,
 * alias, idempotence and history-date controls live in :data's BackupOwnedMergeTest.
 */
class BackupDaoChapterUpsertTest {
    private lateinit var db: MangaDatabase
    private lateinit var dao: BackupDao

    @BeforeTest
    fun open() {
        db =
            Room
                .inMemoryDatabaseBuilder<MangaDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.Default)
                .build()
        dao = db.backupDao()
    }

    @AfterTest
    fun close() = db.close()

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

    /** Test drafts carry id0; callers must supply the checked receiver-local parent ID. */
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

    private suspend fun seedLocalLibrary(): Long {
        val mangaId = dao.insertMangaRow(manga())
        check(mangaId > 0)
        check(dao.insertChapterRow(incomingChapter(1, isRead = true).copy(mangaId = mangaId)) > 0)
        return mangaId
    }

    @Test
    fun chapter_lookup_is_mangaId_scoped_so_shared_urls_do_not_cross_mangas() = runTest {
        val first = seedLocalLibrary()
        val other = dao.insertMangaRow(manga(url = "https://other/manga", api = "other", title = "Other"))
        val secondChapter = dao.insertChapterRow(incomingChapter(1).copy(mangaId = other))
        val original = assertNotNull(dao.getChapterByMangaAndUrl(first, "https://azora/ch/1"))
        assertTrue(original.id != secondChapter)
        assertEquals(secondChapter, dao.getChapterByMangaAndUrl(other, original.url)?.id)
        assertEquals(original, dao.getChaptersForManga(first).single())
    }

    @Test
    fun checked_engagement_and_reading_updates_preserve_all_unowned_columns() = runTest {
        val id = seedLocalLibrary()
        val manga = assertNotNull(dao.getMangaByUrl("https://azora/manga/1"))
        val chapter = dao.getChaptersForManga(id).single()
        assertEquals(1, dao.updateMangaEngagement(BackupMangaUpdate(id, true, true, 900, 50)))
        assertEquals(1, dao.updateChapterReading(BackupChapterUpdate(chapter.id, true, true, 800)))
        val expectedManga = manga.copy(isLiked = true, isWatchingNow = true, lastOpenTimestamp = 900, savedTimestamp = 50)
        val expectedChapter = chapter.copy(isRead = true, isBookmarked = true, lastReadDate = 800)
        assertEquals(expectedManga, dao.getMangaByUrl(manga.url))
        assertEquals(expectedChapter, dao.getChaptersForManga(id).single())
        assertEquals(0, dao.updateMangaEngagement(BackupMangaUpdate(-1, true, true, 900, 50)))
        assertEquals(0, dao.updateChapterReading(BackupChapterUpdate(-1, true, true, 800)))
    }

    @Test
    fun markChapterRestored_flips_only_the_download_columns() =
        runTest {
            val mangaId = seedLocalLibrary()
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

    // --- restore + download row ------------------------------------------------------------------

    private val activeStates =
        setOf(
            DownloadingState.QUEUED,
            DownloadingState.RUNNING,
            DownloadingState.DOWNLOADED,
            DownloadingState.COMPRESSING,
        )

    private fun downloadRow(
        chapterId: Long,
        mangaId: Long,
        state: DownloadingState = DownloadingState.SUCCESS,
        sizeBytes: Long = 0,
    ) = ChapterDownloadEntity(
        number = "1",
        chapterId = chapterId,
        mangaId = mangaId,
        api = "azora",
        mangaTitle = "Solo Leveling",
        url = "https://azora/ch/1",
        state = state,
        progress = if (state == DownloadingState.SUCCESS) 100 else 0,
        sizeBytes = sizeBytes,
    )

    @Test
    fun restore_with_download_row_flips_the_chapter_and_writes_the_sized_success_row() =
        runTest {
            val mangaId = seedLocalLibrary()
            val chapter = assertNotNull(dao.getChapterByMangaAndUrl(mangaId, "https://azora/ch/1"))
            assertNull(dao.getDownloadRowByChapter(chapter.id), "restored chapters start with no queue row")

            val restored =
                dao.markChapterRestoredWithDownloadRow(
                    update =
                        RestoredChapterUpdate(
                            id = chapter.id,
                            isDownloaded = true,
                            localImagePaths = listOf("/restored/ch1.cbz"),
                        ),
                    downloadRow = downloadRow(chapter.id, mangaId, sizeBytes = 12_345),
                    activeStates = activeStates,
                )

            assertTrue(restored)
            val after = assertNotNull(dao.getChapterByMangaAndUrl(mangaId, "https://azora/ch/1"))
            assertTrue(after.isDownloaded)
            val row = assertNotNull(dao.getDownloadRowByChapter(chapter.id), "the size display reads this row")
            assertEquals(DownloadingState.SUCCESS, row.state)
            assertEquals(100, row.progress)
            assertEquals(12_345, row.sizeBytes)
        }

    @Test
    fun restore_is_refused_while_the_engine_owns_the_chapter() =
        runTest {
            val mangaId = seedLocalLibrary()
            val chapter = assertNotNull(dao.getChapterByMangaAndUrl(mangaId, "https://azora/ch/1"))
            dao.upsertDownloadRow(downloadRow(chapter.id, mangaId, state = DownloadingState.RUNNING))

            val restored =
                dao.markChapterRestoredWithDownloadRow(
                    update =
                        RestoredChapterUpdate(
                            id = chapter.id,
                            isDownloaded = true,
                            localImagePaths = listOf("/restored/ch1.cbz"),
                        ),
                    downloadRow = downloadRow(chapter.id, mangaId, sizeBytes = 12_345),
                    activeStates = activeStates,
                )

            assertFalse(restored)
            val after = assertNotNull(dao.getChapterByMangaAndUrl(mangaId, "https://azora/ch/1"))
            assertFalse(after.isDownloaded, "nothing flipped — the running download keeps ownership")
            val row = assertNotNull(dao.getDownloadRowByChapter(chapter.id))
            assertEquals(DownloadingState.RUNNING, row.state, "the engine's row was not clobbered")
        }

    @Test
    fun restore_supersedes_a_stale_failed_row_in_place() =
        runTest {
            val mangaId = seedLocalLibrary()
            val chapter = assertNotNull(dao.getChapterByMangaAndUrl(mangaId, "https://azora/ch/1"))
            dao.upsertDownloadRow(downloadRow(chapter.id, mangaId, state = DownloadingState.FAILED))
            val staleId = assertNotNull(dao.getDownloadRowByChapter(chapter.id)).id

            val restored =
                dao.markChapterRestoredWithDownloadRow(
                    update =
                        RestoredChapterUpdate(
                            id = chapter.id,
                            isDownloaded = true,
                            localImagePaths = listOf("/restored/ch1.cbz"),
                        ),
                    downloadRow = downloadRow(chapter.id, mangaId, sizeBytes = 777),
                    activeStates = activeStates,
                )

            assertTrue(restored)
            val row = assertNotNull(dao.getDownloadRowByChapter(chapter.id))
            assertEquals(staleId, row.id, "the failed row is superseded in place, keeping its ordering slot")
            assertEquals(DownloadingState.SUCCESS, row.state)
            assertEquals(777, row.sizeBytes)
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
    fun history_position_update_preserves_identity_metadata_and_downloads() = runTest {
        val older = LocalDateTime(2026, 1, 1, 10, 0)
        val newer = LocalDateTime(2026, 2, 1, 10, 0)
        val draft = historyRow(older).copy(isDownloaded = true, localImagePaths = listOf("/local.cbz"))
        val id = dao.insertHistoryRow(draft)
        assertTrue(id > 0)
        assertEquals(1, dao.updateHistoryPosition(id, "https://azora/ch/99", "New chapter", newer, 0, 40))
        val expected = draft.copy(
            id = id,
            chapterUrl = "https://azora/ch/99",
            chapterTitle = "New chapter",
            lastReadDate = newer,
            lastReadPage = 0,
            totalPages = 40,
        )
        assertEquals(expected, dao.getAllHistoryOnce().single())
        assertEquals(0, dao.updateHistoryPosition(-1, "https://azora/ch/99", "New chapter", newer, 0, 40))
    }
}
