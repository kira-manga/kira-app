package me.manga.kira.data.local

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.dao.READER_API
import me.manga.kira.data.local.dao.READER_CHAPTER_URL
import me.manga.kira.data.local.dao.READER_WORK_URL
import me.manga.kira.data.local.dao.ReaderProgressFixture
import me.manga.kira.data.local.dao.ReaderProgressSnapshot
import me.manga.kira.data.local.dao.readerReceipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Retains the original v13 controls through the accepted 14–16 chain and the new App32 v17 migration. */
class Migration13To14RoomTest {
    @Test
    fun retainedVersion13MigratesAndReopensWithoutChangingExistingTablesOrRows() = runTest {
        assertRetainedVersion(13)
    }

    @Test
    fun retainedVersion16MigratesAndReopensWithoutChangingExistingTablesOrRows() = runTest {
        assertRetainedVersion(16)
    }

    private suspend fun assertRetainedVersion(version: Int) {
        ReaderProgressFixture().use { fixture ->
            val oldTables = createReaderVersion(fixture.file, version)
            assertOldLibrary(fixture)
            assertEquals(17L, fixture.number("PRAGMA user_version"))
            val migratedTables = fixture.definitions("table")
            oldTables.forEach { (name, sql) -> assertEquals(sql, migratedTables[name], name) }
            assertNull(fixture.progress.findWork(READER_API, READER_WORK_URL))
            assertEquals(0L, fixture.number("SELECT COUNT(*) FROM reader_chapter_state"))
            assertEquals(0L, fixture.number("SELECT COUNT(*) FROM reader_legacy_cleanup"))
            fixture.reopen()
            assertOldLibrary(fixture)
            assertEquals(17L, fixture.number("PRAGMA user_version"))
            assertNull(fixture.progress.findSnapshot(READER_API, READER_WORK_URL, READER_CHAPTER_URL))
        }
    }

    @Test
    fun freshAndMigratedCreationHaveIdenticalGuardsAndAbortTheWholeWriter() = runTest {
        listOf(13, 16).forEach { version ->
            ReaderProgressFixture().use { fresh ->
                ReaderProgressFixture().use { migrated ->
                    createReaderVersion(migrated.file, version)
                    assertValidationAndRollback(fresh)
                    assertValidationAndRollback(migrated)
                    val triggers = fresh.definitions("trigger")
                    assertEquals(10, triggers.size)
                    assertEquals(triggers, migrated.definitions("trigger"))
                    fresh.reopen()
                    migrated.reopen()
                    assertEquals(triggers, fresh.definitions("trigger"))
                    assertEquals(triggers, migrated.definitions("trigger"))
                }
            }
        }
    }

    private suspend fun assertOldLibrary(fixture: ReaderProgressFixture) {
        val work = assertNotNull(fixture.db.mangaDao().getMangaById(7))
        assertEquals("Preserved title", work.title)
        assertEquals("Preserved description", work.description)
        assertEquals("Preserved author", work.author)
        assertEquals(listOf("action"), work.genres)
        assertEquals(101L, work.savedTimestamp)
        assertEquals(202L, work.lastOpenTimestamp)
        assertTrue(work.isLiked && work.isWatchingNow)
        val chapter = assertNotNull(fixture.db.chapterDao().getChapterByIdSuspend(11))
        assertEquals(work.id, chapter.mangaId)
        assertEquals("Preserved chapter", chapter.name)
        assertEquals(4, chapter.lastReadPage)
        assertEquals(303L, chapter.lastReadDate)
        assertEquals(404L, chapter.fetchedAt)
        assertTrue(chapter.isRead && chapter.isBookmarked && chapter.isDownloaded && chapter.isNew)
        assertEquals(listOf("/offline/chapter.cbz"), chapter.localImagePaths)
    }

    private suspend fun assertValidationAndRollback(fixture: ReaderProgressFixture) {
        val before = fixture.anchor()
        val receipt = fixture.cleanup.recordOnce(readerReceipt(before))
        assertTrue(fixture.progress.savePosition(before, 0))
        invalidProgressSql(before).forEach { sql ->
            assertFails(sql) {
                fixture.writer {
                    assertTrue(fixture.progress.savePosition(before, 8))
                    fixture.execute(sql)
                }
            }
            assertEquals(before.copy(pageIndex = 0), fixture.anchor())
            assertEquals(receipt, fixture.cleanup.find(receipt.legacyKey, receipt.capturedPayload))
        }
        assertEquals(1L, fixture.number("SELECT COUNT(*) FROM reader_work_state"))
        assertEquals(1L, fixture.number("SELECT COUNT(*) FROM reader_chapter_state"))
        assertEquals(1L, fixture.number("SELECT COUNT(*) FROM reader_legacy_cleanup"))
        assertTrue(fixture.progress.savePosition(before, Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, fixture.anchor().pageIndex)
    }

    private fun invalidProgressSql(snapshot: ReaderProgressSnapshot): List<String> =
        listOf(
            "INSERT INTO reader_work_state (api, workUrl, workGeneration) VALUES ('bad', 'bad', -1)",
            "UPDATE reader_work_state SET workGeneration = ${Long.MAX_VALUE} + 1",
            "INSERT INTO reader_chapter_state (workId, chapterUrl, chapterGeneration) " +
                "VALUES (${snapshot.workId}, 'bad', 1.5)",
            "UPDATE reader_chapter_state SET chapterGeneration = -1",
            "UPDATE reader_chapter_state SET pageIndex = -1",
            "UPDATE reader_chapter_state SET pageIndex = 1.5",
            "UPDATE reader_chapter_state SET pageIndex = ${Int.MAX_VALUE.toLong() + 1}",
            "INSERT INTO reader_legacy_cleanup (legacyKey, capturedPayload, chapterId, capturedWorkGeneration, " +
                "capturedChapterGeneration, disposition, state) " +
                "VALUES ('bad', 'bad', ${snapshot.chapterId}, -1, 0, 'COPIED', 'PENDING')",
            "UPDATE reader_legacy_cleanup SET capturedChapterGeneration = 1.5",
            "UPDATE reader_legacy_cleanup SET disposition = 'UNRECOGNIZED'",
            "UPDATE reader_legacy_cleanup SET state = 'UNRECOGNIZED'",
        )
}
