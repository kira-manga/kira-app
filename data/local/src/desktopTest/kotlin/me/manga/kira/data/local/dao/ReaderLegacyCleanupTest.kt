package me.manga.kira.data.local.dao

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.entity.ReaderLegacyCleanupState
import me.manga.kira.data.local.entity.ReaderLegacyDisposition
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Receipt persistence only; these are not Settings transfer, CAS or physical-flush tests. */
class ReaderLegacyCleanupTest {
    private lateinit var fixture: ReaderProgressFixture
    private val cleanup: ReaderLegacyCleanupDao get() = fixture.cleanup

    @BeforeTest
    fun open() {
        fixture = ReaderProgressFixture()
    }

    @AfterTest
    fun close() = fixture.close()

    @Test
    fun copyDecisionAndPositionShareOneRoomCommitBoundaryAcrossReopening() = runTest {
        val before = fixture.anchor()
        val receipt = readerReceipt(before)
        assertFailsWith<IllegalStateException> {
            fixture.writer {
                assertTrue(fixture.progress.savePosition(before, 7))
                cleanup.recordOnce(receipt)
                error("Abort before Room commit")
            }
        }
        fixture.reopen()
        assertEquals(before, fixture.anchor())
        assertNull(cleanup.find(receipt.legacyKey, receipt.capturedPayload))
        fixture.writer {
            assertTrue(fixture.progress.savePosition(before, 7))
            assertEquals(receipt, cleanup.recordOnce(receipt))
        }
        fixture.reopen()
        assertEquals(before.copy(pageIndex = 7), fixture.anchor())
        assertEquals(receipt, cleanup.find(receipt.legacyKey, receipt.capturedPayload))
    }

    @Test
    fun cleanupCasNeverRestoresPendingOrChangesTheOriginalCapture() = runTest {
        val receipt =
            cleanup.recordOnce(readerReceipt(fixture.anchor(), disposition = ReaderLegacyDisposition.SUPERSEDED))
        val pending = ReaderLegacyCleanupState.PENDING
        val acked = ReaderLegacyCleanupState.ACKED
        val conflict = ReaderLegacyCleanupState.CONFLICT
        assertTrue(cleanup.markCleanup(receipt.legacyKey, receipt.capturedPayload, pending, acked))
        assertFalse(cleanup.markCleanup(receipt.legacyKey, receipt.capturedPayload, pending, conflict))
        assertTrue(cleanup.markCleanup(receipt.legacyKey, receipt.capturedPayload, acked, conflict))
        assertFailsWith<IllegalArgumentException> {
            cleanup.markCleanup(receipt.legacyKey, receipt.capturedPayload, conflict, pending)
        }
        assertTrue(cleanup.markCleanup(receipt.legacyKey, receipt.capturedPayload, conflict, acked))
        assertFalse(cleanup.markCleanup(receipt.legacyKey, "different full payload", acked, conflict))
        fixture.reopen()
        assertEquals(receipt.copy(state = acked), cleanup.find(receipt.legacyKey, receipt.capturedPayload))
        assertEquals(receipt.copy(state = acked), cleanup.recordOnce(receipt))
        assertEquals(listOf(receipt.copy(state = acked)), cleanup.allReceipts())
    }

    @Test
    fun identicalKeyKeepsFullPayloadsSeparateAndConflictingDestinationAborts() = runTest {
        val first = fixture.anchor()
        val second = fixture.anchor(chapterUrl = "$READER_WORK_URL/chapter/two")
        val original = cleanup.recordOnce(readerReceipt(first))
        val different = cleanup.recordOnce(readerReceipt(second, payload = "$READER_WORK_URL/chapter/two|3"))
        assertFailsWith<IllegalStateException> {
            fixture.writer {
                assertTrue(fixture.progress.savePosition(second, 9))
                cleanup.recordOnce(original.copy(chapterId = second.chapterId))
            }
        }
        fixture.reopen()
        assertEquals(setOf(original, different), cleanup.allReceipts().toSet())
        assertNull(fixture.anchor(chapterUrl = "$READER_WORK_URL/chapter/two").pageIndex)
    }

    @Test
    fun ackedReceiptSurvivesNativeWritesAndBothClearFences() = runTest {
        val before = fixture.anchor()
        val receipt = cleanup.recordOnce(readerReceipt(before))
        assertTrue(fixture.progress.savePosition(before, 9))
        assertTrue(
            cleanup.markCleanup(
                receipt.legacyKey,
                receipt.capturedPayload,
                ReaderLegacyCleanupState.PENDING,
                ReaderLegacyCleanupState.ACKED,
            ),
        )
        fixture.progress.clearChapter(READER_API, READER_WORK_URL, READER_CHAPTER_URL)
        fixture.progress.clearWork(READER_API, READER_WORK_URL)
        fixture.reopen()
        val current = fixture.anchor()
        assertEquals(before.copy(workGeneration = 1, chapterGeneration = 1), current)
        assertEquals(receipt.copy(state = ReaderLegacyCleanupState.ACKED), cleanup.recordOnce(receipt))
        assertEquals(current, fixture.anchor())
        assertFalse(fixture.progress.savePosition(before, 7))
    }

    @Test
    fun nonexistentChapterReceiptRollsBackAnEarlierPositionWrite() = runTest {
        val before = fixture.anchor()
        assertFails {
            fixture.writer {
                assertTrue(fixture.progress.savePosition(before, 7))
                cleanup.recordOnce(readerReceipt(before).copy(chapterId = Long.MAX_VALUE))
            }
        }
        fixture.reopen()
        assertEquals(before, fixture.anchor())
        assertEquals(emptyList(), cleanup.allReceipts())
    }
}
