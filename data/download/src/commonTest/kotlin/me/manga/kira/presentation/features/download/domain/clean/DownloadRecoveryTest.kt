package me.manga.kira.presentation.features.download.domain.clean

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.manga.kira.presentation.features.download.data.DownloadingState

/**
 * Pure-logic regression tests for [DownloadRecovery] (B1, COMPRESSING-orphan). No I/O — locks the two
 * crash-recovery classification rules used by `CoroutineDownloadRepositoryImpl`:
 *
 *  1. `enqueueChapterDownload` dedup: an existing QUEUED / RUNNING / COMPRESSING row makes re-enqueue
 *     a no-op (a REPLACE-insert would reset a live row to QUEUED/progress=0 = accidental cancel);
 *     absent rows and terminal SUCCESS / FAILED rows proceed.
 *  2. Startup reconcile: exactly the rows a dead process left in {RUNNING, COMPRESSING} are reset to
 *     QUEUED (mirrors `ChapterDownloadDao.reEnqueueInterrupted`'s WHERE clause), excluding the one
 *     chapter the current process is actively downloading.
 */
class DownloadRecoveryTest {

    // ---- (i) enqueue dedup: isActiveDownloadState ----

    @Test
    fun activeStates_areExactlyQueuedRunningCompressing() {
        // Exhaustive over the enum so a future DownloadingState addition forces a deliberate decision.
        val active = DownloadingState.entries.filter { DownloadRecovery.isActiveDownloadState(it) }.toSet()
        assertEquals(
            setOf(DownloadingState.QUEUED, DownloadingState.RUNNING, DownloadingState.COMPRESSING),
            active,
        )
    }

    @Test
    fun enqueueIsNoOp_forQueuedRunningCompressing() {
        assertTrue(DownloadRecovery.isActiveDownloadState(DownloadingState.QUEUED))
        assertTrue(DownloadRecovery.isActiveDownloadState(DownloadingState.RUNNING))
        assertTrue(DownloadRecovery.isActiveDownloadState(DownloadingState.COMPRESSING))
    }

    @Test
    fun enqueueProceeds_forAbsentRowAndTerminalStates() {
        assertFalse(DownloadRecovery.isActiveDownloadState(null)) // no row yet → fresh enqueue
        assertFalse(DownloadRecovery.isActiveDownloadState(DownloadingState.SUCCESS)) // re-download
        assertFalse(DownloadRecovery.isActiveDownloadState(DownloadingState.FAILED)) // retry
    }

    @Test
    fun enqueueProceeds_forDownloaded() {
        // DOWNLOADED is produced only by the iOS background-URLSession engine (its own reconciler
        // finalizes it); the coroutine engine never writes it, so it is deliberately not "active".
        assertFalse(DownloadRecovery.isActiveDownloadState(DownloadingState.DOWNLOADED))
    }

    // ---- (ii) startup reconcile: shouldReEnqueueInterrupted + reconcileExcludeChapterId ----

    @Test
    fun interruptedSet_isExactlyRunningAndCompressing() {
        // Exhaustive over the enum, with an exclude id that matches no row.
        val reEnqueued = DownloadingState.entries
            .filter { DownloadRecovery.shouldReEnqueueInterrupted(it, chapterId = 7L, excludeChapterId = -1L) }
            .toSet()
        assertEquals(setOf(DownloadingState.RUNNING, DownloadingState.COMPRESSING), reEnqueued)
    }

    @Test
    fun compressingOrphan_isReEnqueued() {
        // B1 core: a process killed mid-CBZ-encode strands a COMPRESSING row → reconcile must reset it.
        assertTrue(
            DownloadRecovery.shouldReEnqueueInterrupted(
                DownloadingState.COMPRESSING, chapterId = 10L, excludeChapterId = -1L,
            ),
        )
    }

    @Test
    fun activeChapter_isExcludedFromReEnqueue() {
        // The chapter the CURRENT process is downloading must not be reset back to page 0...
        assertFalse(
            DownloadRecovery.shouldReEnqueueInterrupted(
                DownloadingState.RUNNING, chapterId = 11L, excludeChapterId = 11L,
            ),
        )
        assertFalse(
            DownloadRecovery.shouldReEnqueueInterrupted(
                DownloadingState.COMPRESSING, chapterId = 11L, excludeChapterId = 11L,
            ),
        )
        // ...while a different interrupted chapter still is.
        assertTrue(
            DownloadRecovery.shouldReEnqueueInterrupted(
                DownloadingState.RUNNING, chapterId = 12L, excludeChapterId = 11L,
            ),
        )
    }

    @Test
    fun excludeChapterId_isActiveIdOrMinusOne() {
        // -1 matches no Room rowid (always positive), so with no active job every orphan is reset.
        assertEquals(-1L, DownloadRecovery.reconcileExcludeChapterId(null))
        assertEquals(42L, DownloadRecovery.reconcileExcludeChapterId(42L))
    }

    @Test
    fun reconcileScenario_resetsOrphansOnly() {
        // Table a dead process could leave behind: a COMPRESSING orphan (B1), a RUNNING orphan, and
        // rows the reconcile must never touch (QUEUED / SUCCESS / FAILED / DOWNLOADED).
        val rows = listOf(
            10L to DownloadingState.COMPRESSING, // killed mid-CBZ-encode (B1)
            11L to DownloadingState.RUNNING, // killed mid-page-transfer
            12L to DownloadingState.QUEUED, // init-block QUEUED recovery handles this, not reconcile
            13L to DownloadingState.SUCCESS,
            14L to DownloadingState.FAILED,
            15L to DownloadingState.DOWNLOADED, // iOS bg-engine state, owned by its manifest reconciler
        )

        fun reEnqueued(activeChapterId: Long?): Set<Long> {
            val exclude = DownloadRecovery.reconcileExcludeChapterId(activeChapterId)
            return rows
                .filter { (id, state) -> DownloadRecovery.shouldReEnqueueInterrupted(state, id, exclude) }
                .map { (id, _) -> id }
                .toSet()
        }

        // Fresh process, nothing active yet → both orphans reset.
        assertEquals(setOf(10L, 11L), reEnqueued(activeChapterId = null))
        // The in-process worker already picked chapter 11 back up → only the COMPRESSING orphan resets.
        assertEquals(setOf(10L), reEnqueued(activeChapterId = 11L))
    }
}
