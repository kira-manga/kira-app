package me.manga.kira.presentation.features.download.domain.clean

import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.presentation.features.download.data.DownloadingState

/**
 * Pure computation of the iOS background-work signal from a downloads-table snapshot (B8,
 * regressing whole-queue percent). No I/O — mirrors what `BackgroundUrlSessionDownloadRepository`'s
 * observe collector feeds `workSignal.update(...)`, which the Swift host reads synchronously for
 * the BG-task layer and the one-chapter-at-a-time Live Activity.
 *
 * B8: the whole-queue percent is the **lead** chapter's progress (the chapter the Live Activity
 * actually tracks), NOT a `sum / active.size` average — the average REGRESSED as the divisor
 * shrank when a 100% chapter left the active set. Lead-based is monotonic within a chapter, and a
 * finished chapter leaving the set cannot move the reported percent while the lead is unchanged.
 */
object WorkSignalRules {

    /**
     * Rows the signal (and the engine's enqueue dedup / cancel-all snapshot) treat as active —
     * exactly {QUEUED, RUNNING, COMPRESSING, DOWNLOADED}. Note DOWNLOADED **is** active here (the
     * background engine still owes the chapter its finalize), unlike the coroutine engine's
     * [DownloadRecovery.isActiveDownloadState], which never sees DOWNLOADED.
     */
    val ACTIVE_STATES: Set<DownloadingState> = setOf(
        DownloadingState.QUEUED,
        DownloadingState.RUNNING,
        DownloadingState.COMPRESSING,
        DownloadingState.DOWNLOADED,
    )

    /** The signal inputs one downloads-table emission computes to. */
    data class Snapshot(
        /** Any active row exists → the BG-task layer has work. */
        val pending: Boolean,
        /** The lead chapter's progress; 100 when nothing is active (B8: never a shrinking-divisor average). */
        val progressPercent: Int,
        /** Each active chapter's transfer %, keyed by chapterId. */
        val chapterProgress: Map<Long, Int>,
        /** The chapter the Live Activity tracks: the RUNNING front-runner, else any active front-runner. */
        val leadChapterId: Long?,
        /** True while real transfer work remains (a QUEUED chapter, or a RUNNING one under 100%) — vs only finalize-pending chapters. */
        val hasTransferWork: Boolean,
        /** Active-row count (log/diagnostic parity with the previous inline computation). */
        val activeCount: Int,
    )

    fun compute(rows: List<ChapterDownloadEntity>): Snapshot {
        val active = rows.filter { it.state in ACTIVE_STATES }
        val chapterProgress = active.associate { it.chapterId to it.progress }
        val lead = active.filter { it.state == DownloadingState.RUNNING }.maxByOrNull { it.progress }?.chapterId
            ?: active.maxByOrNull { it.progress }?.chapterId
        return Snapshot(
            pending = active.isNotEmpty(),
            progressPercent = lead?.let { chapterProgress[it] } ?: 100,
            chapterProgress = chapterProgress,
            leadChapterId = lead,
            hasTransferWork = active.any {
                it.state == DownloadingState.QUEUED || (it.state == DownloadingState.RUNNING && it.progress < 100)
            },
            activeCount = active.size,
        )
    }
}
