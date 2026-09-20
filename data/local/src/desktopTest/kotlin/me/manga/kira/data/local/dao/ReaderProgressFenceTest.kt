package me.manga.kira.data.local.dao

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual Room conditional writes, nested writer rollback and independently durable epochs. */
class ReaderProgressFenceTest {
    private lateinit var fixture: ReaderProgressFixture
    private val dao: ReaderProgressDao get() = fixture.progress

    @BeforeTest
    fun open() {
        fixture = ReaderProgressFixture()
    }

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun chapterClearRejectsOnlyItsOldHandleAndPreservesExplicitZero() = runTest {
        val first = fixture.anchor()
        val second = fixture.anchor(chapterUrl = "$READER_WORK_URL/chapter/two")
        assertTrue(dao.savePosition(first, 7))
        assertTrue(dao.savePosition(second, 9))
        val cleared = dao.clearChapter(READER_API, READER_WORK_URL, READER_CHAPTER_URL)
        assertEquals(first.copy(chapterGeneration = 1, pageIndex = null), cleared)
        assertFalse(dao.savePosition(first, 8))
        assertTrue(dao.savePosition(second, 10))
        assertTrue(dao.savePosition(cleared, 0))
        assertEquals(0, fixture.anchor().pageIndex)
        assertEquals(10, fixture.anchor(chapterUrl = "$READER_WORK_URL/chapter/two").pageIndex)
        assertEquals(0L, assertNotNull(dao.findWork(READER_API, READER_WORK_URL)).workGeneration)
    }

    @Test
    fun workClearFencesDelayedAndSiblingWritesWithoutAutomaticallyReopening() = runTest {
        val captured = CompletableDeferred<ReaderProgressSnapshot>()
        val release = CompletableDeferred<Unit>()
        val delayed = async {
            val held = fixture.anchor()
            captured.complete(held)
            release.await()
            dao.savePosition(held, 99)
        }
        val first = captured.await()
        val second = fixture.anchor(chapterUrl = "$READER_WORK_URL/chapter/two")
        assertTrue(dao.savePosition(first, 7))
        assertTrue(dao.savePosition(second, 9))
        val cleared = dao.clearWork(READER_API, READER_WORK_URL)
        release.complete(Unit)
        assertFalse(delayed.await())
        assertFalse(dao.savePosition(second, 10))
        assertEquals(1L, cleared.workGeneration)
        assertNull(assertNotNull(dao.findSnapshot(READER_API, READER_WORK_URL, READER_CHAPTER_URL)).pageIndex)
        assertEquals(0L, fixture.number("SELECT COUNT(*) FROM reader_chapter_state WHERE pageIndex IS NOT NULL"))
        assertEquals(2L, fixture.number("SELECT COUNT(*) FROM reader_chapter_state"))
        val current = fixture.anchor()
        assertEquals(first.copy(workGeneration = 1), current)
        assertTrue(dao.savePosition(current, 3))
    }

    @Test
    fun sourceScopeAndAnchorIdsRejectCrossWorkAndMissingDestinations() = runTest {
        val first = fixture.anchor()
        val other = fixture.anchor(api = "other-source")
        assertTrue(dao.savePosition(first, 0))
        assertTrue(dao.savePosition(other, 5))
        assertFalse(dao.savePosition(first.copy(workId = other.workId), 8))
        assertFalse(dao.savePosition(first.copy(chapterId = Long.MAX_VALUE), 8))
        assertFailsWith<IllegalArgumentException> { dao.savePosition(first, -1) }
        assertEquals(0, fixture.anchor().pageIndex)
        assertEquals(5, fixture.anchor(api = "other-source").pageIndex)
        assertEquals(2L, fixture.number("SELECT COUNT(*) FROM reader_work_state"))
        assertEquals(2L, fixture.number("SELECT COUNT(*) FROM reader_chapter_state"))
    }

    @Test
    fun workGenerationOverflowRollsBackTheEnclosingSavedParentDeletion() = runTest {
        val saved = fixture.savedFamily()
        val first = fixture.anchor()
        assertTrue(dao.savePosition(first, 7))
        fixture.execute("UPDATE reader_work_state SET workGeneration = ${Long.MAX_VALUE}")
        assertFailsWith<IllegalStateException> {
            fixture.writer {
                assertEquals(1, fixture.db.libraryDeo().deleteMangaById(saved.work.id))
                dao.clearWork(READER_API, READER_WORK_URL)
            }
        }
        fixture.reopen()
        assertEquals(saved.work, fixture.db.mangaDao().getMangaById(saved.work.id))
        assertEquals(saved.chapter, fixture.db.chapterDao().getChapterByIdSuspend(saved.chapter.id))
        assertEquals(first.copy(workGeneration = Long.MAX_VALUE, pageIndex = 7), fixture.anchor())
    }

    @Test
    fun chapterGenerationOverflowRollsBackAnEarlierWorkFenceAndPageClear() = runTest {
        val first = fixture.anchor()
        assertTrue(dao.savePosition(first, 7))
        fixture.execute("UPDATE reader_chapter_state SET chapterGeneration = ${Long.MAX_VALUE}")
        assertFailsWith<IllegalStateException> {
            fixture.writer {
                dao.clearWork(READER_API, READER_WORK_URL)
                dao.clearChapter(READER_API, READER_WORK_URL, READER_CHAPTER_URL)
            }
        }
        fixture.reopen()
        assertEquals(first.copy(chapterGeneration = Long.MAX_VALUE, pageIndex = 7), fixture.anchor())
    }

    @Test
    fun incompletePageClearCountRollsBackTheEpochAndEveryEarlierRow() = runTest {
        val first = fixture.anchor()
        val second = fixture.anchor(chapterUrl = "$READER_WORK_URL/chapter/two")
        assertTrue(dao.savePosition(first, 7))
        assertTrue(dao.savePosition(second, 9))
        fixture.execute(
            """
            CREATE TRIGGER test_block_one_clear BEFORE UPDATE OF pageIndex ON reader_chapter_state
            WHEN OLD.chapterId = ${second.chapterId} BEGIN SELECT RAISE(IGNORE); END
            """.trimIndent(),
        )
        assertFailsWith<IllegalStateException> { dao.clearWork(READER_API, READER_WORK_URL) }
        assertEquals(first.copy(pageIndex = 7), fixture.anchor())
        assertEquals(second.copy(pageIndex = 9), fixture.anchor(chapterUrl = "$READER_WORK_URL/chapter/two"))
    }
}
