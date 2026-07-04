package me.manga.kira.presentation.features.download.domain.clean

import me.manga.kira.presentation.features.download.data.DownloadingState

/**
 * Pure state-classification rules for the download queue's crash-recovery edges (B1,
 * COMPRESSING-orphan). No I/O — fully unit-tested, mirroring [BackgroundReconciler].
 *
 * Two recovery edges keep a process kill from stranding a row:
 *  - **Enqueue dedup** ([isActiveDownloadState]): `enqueueChapterDownload` treats an existing row in
 *    QUEUED / RUNNING / COMPRESSING as already owned by the queue and no-ops, because the DAO insert
 *    is `OnConflictStrategy.REPLACE` on the unique chapterId index — re-inserting an active row would
 *    rewrite it to QUEUED/progress=0, which the worker's cooperative state check treats as a
 *    mid-chapter cancel. Absent rows and terminal SUCCESS / FAILED rows proceed (retry path).
 *    DOWNLOADED is deliberately NOT active here: it is produced only by the iOS
 *    background-URLSession engine (never by the coroutine engine this rule guards), and that engine
 *    finalizes it via its own manifest reconciler.
 *  - **Startup reconcile** ([shouldReEnqueueInterrupted] / [reconcileExcludeChapterId]): rows a dead
 *    process left in RUNNING or COMPRESSING (e.g. killed mid-CBZ-encode) are reset to QUEUED so the
 *    worker re-pulls them; the one chapter the *current* process is actively downloading is excluded
 *    so a live job is not aborted back to page 0.
 *
 * [shouldReEnqueueInterrupted] is the pure mirror of the `ChapterDownloadDao.reEnqueueInterrupted`
 * SQL WHERE clause (`state IN (RUNNING, COMPRESSING) AND chapterId != :excludeChapterId`) — Room SQL
 * cannot call Kotlin, so keep the two in lockstep if either ever changes.
 */
object DownloadRecovery {

    /**
     * True when an existing row means the chapter is already queued or in-flight, so a re-enqueue
     * must be a no-op. Exactly {QUEUED, RUNNING, COMPRESSING}; `null` = no row = proceed.
     */
    fun isActiveDownloadState(state: DownloadingState?): Boolean =
        state == DownloadingState.QUEUED ||
            state == DownloadingState.RUNNING ||
            state == DownloadingState.COMPRESSING

    /**
     * The `excludeChapterId` passed to `ChapterDownloadDao.reEnqueueInterrupted`: the current
     * process's active chapter if any, else -1 (Room rowids are positive, so -1 matches no row and
     * every orphan from a dead process is reset).
     */
    fun reconcileExcludeChapterId(activeChapterId: Long?): Long = activeChapterId ?: -1L

    /**
     * Pure mirror of the `reEnqueueInterrupted` WHERE clause: a row is reset to QUEUED iff it was
     * left in RUNNING or COMPRESSING and it is not the chapter the current process is downloading.
     */
    fun shouldReEnqueueInterrupted(
        state: DownloadingState,
        chapterId: Long,
        excludeChapterId: Long,
    ): Boolean =
        (state == DownloadingState.RUNNING || state == DownloadingState.COMPRESSING) &&
            chapterId != excludeChapterId
}
