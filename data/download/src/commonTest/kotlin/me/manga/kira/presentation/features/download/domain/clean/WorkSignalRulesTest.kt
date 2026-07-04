package me.manga.kira.presentation.features.download.domain.clean

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.presentation.features.download.data.DownloadingState

/**
 * Pure-logic regression tests for [WorkSignalRules] (B8, regressing whole-queue percent). Locks
 * the lead-chapter selection, the B8 no-regression property (a finished chapter leaving the active
 * set cannot move the reported percent while the lead is unchanged), the transfer-work predicate,
 * and the exact active-state set.
 */
class WorkSignalRulesTest {

    private fun row(chapterId: Long, state: DownloadingState, progress: Int): ChapterDownloadEntity =
        ChapterDownloadEntity(
            id = chapterId, number = "1", chapterId = chapterId, mangaId = chapterId * 10,
            api = "src", url = "https://example/$chapterId", state = state, progress = progress,
        )

    @Test
    fun emptyQueue_isIdleSnapshot() {
        val s = WorkSignalRules.compute(emptyList())
        assertFalse(s.pending)
        assertEquals(100, s.progressPercent)
        assertEquals(null, s.leadChapterId)
        assertFalse(s.hasTransferWork)
        assertEquals(0, s.activeCount)
    }

    @Test
    fun activeStates_areExactlyQueuedRunningCompressingDownloaded() {
        // Exhaustive over the enum: terminal SUCCESS/FAILED rows are invisible to the signal;
        // DOWNLOADED IS active here (the bg engine still owes the chapter its finalize).
        val active = DownloadingState.entries.filter { it in WorkSignalRules.ACTIVE_STATES }.toSet()
        assertEquals(
            setOf(
                DownloadingState.QUEUED,
                DownloadingState.RUNNING,
                DownloadingState.COMPRESSING,
                DownloadingState.DOWNLOADED,
            ),
            active,
        )
    }

    @Test
    fun lead_isTheRunningFrontRunner_evenWhenAnotherChapterShowsMoreProgress() {
        // The Live Activity tracks the chapter actually transferring — a 100% COMPRESSING chapter
        // must not steal the lead from the RUNNING one.
        val s = WorkSignalRules.compute(
            listOf(
                row(1, DownloadingState.COMPRESSING, 100),
                row(2, DownloadingState.RUNNING, 40),
                row(3, DownloadingState.QUEUED, 0),
            ),
        )
        assertEquals(2L, s.leadChapterId)
        assertEquals(40, s.progressPercent)
    }

    @Test
    fun lead_fallsBackToAnyActiveFrontRunner_whenNothingIsRunning() {
        val s = WorkSignalRules.compute(
            listOf(
                row(1, DownloadingState.COMPRESSING, 100),
                row(3, DownloadingState.QUEUED, 0),
            ),
        )
        assertEquals(1L, s.leadChapterId)
        assertEquals(100, s.progressPercent)
    }

    @Test
    fun b8_finishedChapterLeavingActiveSet_doesNotMoveTheReportedPercent() {
        // B8 core: with the old sum/active.size average, chapter 1 (100%, finalize-pending) leaving
        // the active set made the percent jump around as the divisor shrank. Lead-based: the percent
        // is chapter 2's own progress both before AND after chapter 1 leaves.
        val before = WorkSignalRules.compute(
            listOf(
                row(1, DownloadingState.COMPRESSING, 100), // will finish and leave the set
                row(2, DownloadingState.RUNNING, 30),
            ),
        )
        val after = WorkSignalRules.compute(
            listOf(
                row(1, DownloadingState.SUCCESS, 100), // terminal → invisible to the signal
                row(2, DownloadingState.RUNNING, 30),
            ),
        )
        assertEquals(2L, before.leadChapterId)
        assertEquals(2L, after.leadChapterId)
        assertEquals(30, before.progressPercent)
        assertEquals(30, after.progressPercent) // no divisor-shrink regression or jump
    }

    @Test
    fun hasTransferWork_distinguishesTransferFromFinalizePending() {
        // QUEUED or RUNNING<100 = real transfer work; RUNNING at 100 / DOWNLOADED / COMPRESSING =
        // only finalize-pending (the continued-task layer must not claim transfer work remains).
        assertTrue(WorkSignalRules.compute(listOf(row(1, DownloadingState.QUEUED, 0))).hasTransferWork)
        assertTrue(WorkSignalRules.compute(listOf(row(1, DownloadingState.RUNNING, 99))).hasTransferWork)
        assertFalse(WorkSignalRules.compute(listOf(row(1, DownloadingState.RUNNING, 100))).hasTransferWork)
        assertFalse(WorkSignalRules.compute(listOf(row(1, DownloadingState.DOWNLOADED, 100))).hasTransferWork)
        assertFalse(WorkSignalRules.compute(listOf(row(1, DownloadingState.COMPRESSING, 100))).hasTransferWork)
        assertFalse(WorkSignalRules.compute(listOf(row(1, DownloadingState.SUCCESS, 100))).hasTransferWork)
    }

    @Test
    fun chapterProgress_carriesEveryActiveChapter() {
        val s = WorkSignalRules.compute(
            listOf(
                row(1, DownloadingState.RUNNING, 55),
                row(2, DownloadingState.QUEUED, 0),
                row(3, DownloadingState.FAILED, 20), // terminal → excluded
            ),
        )
        assertEquals(mapOf(1L to 55, 2L to 0), s.chapterProgress)
        assertTrue(s.pending)
        assertEquals(2, s.activeCount)
    }
}
