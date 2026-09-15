package me.manga.kira.presentation.features.download.ui.test2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.NotificationDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.presentation.features.download.data.DownloadingState
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/** Every operation forwards to generated Room; gates live strictly outside the real DAO return. */
internal class DownloadWorkerCancellationDao(
    private val rows: DownloadWorkerCancellationRows,
    private val seam: CancellationSeam,
    private val worker: DownloadJobWitness,
    private val sender: CompleteSendDispatcher,
    private val commit: NativeCommitGate,
) : ChapterDownloadDao by rows.realDao {
    val firstProgressReturned = CompletableDeferred<Unit>()
    val lastProgressReturned = CompletableDeferred<Unit>()
    val releaseProgress = CompletableDeferred<Unit>()
    val notificationPaths = CompletableDeferred<List<String>>()
    val completionReturned = CompletableDeferred<Boolean>()
    val completionCancelled = CompletableDeferred<CancellationException>()
    val ownershipReadAfterCancellation = CompletableDeferred<ChapterDownloadEntity?>()
    val progressCalls = AtomicInteger()
    val completionCalls = AtomicInteger()
    val requeueCalls = AtomicInteger()

    val notifications: NotificationDao =
        object : NotificationDao by rows.db.notificationDao() {
            override suspend fun addLocalImagePathByChapterId(
                chapterId: Long,
                newPaths: List<String>,
                downloaded: Boolean,
            ) {
                rows.db.notificationDao().addLocalImagePathByChapterId(chapterId, newPaths, downloaded)
                assertEquals(rows.original.saved.id, chapterId)
                assertEquals(true, downloaded)
                if (seam == CancellationSeam.DELIVERED_SEND) {
                    sender.markAfterRealNotificationReturn(currentCoroutineContext().job)
                }
                notificationPaths.complete(newPaths)
            }
        }

    override suspend fun getQueuedChaptersForWorker(queuedState: DownloadingState): List<ChapterDownloadEntity> {
        // Before entering Room: capture doWork's coroutineScope, not a nested Room/collector Job.
        worker.capture(currentCoroutineContext().job)
        return rows.realDao.getQueuedChaptersForWorker(queuedState)
    }

    override suspend fun updateProgressForArtifact(
        chapterId: Long,
        downloadId: Long,
        token: String,
        progress: Int,
    ) {
        rows.realDao.updateProgressForArtifact(chapterId, downloadId, token, progress)
        assertEquals(rows.original.saved.id, chapterId)
        val count = progressCalls.incrementAndGet()
        if (count == 1) {
            firstProgressReturned.complete(Unit)
            if (seam == CancellationSeam.DELIVERED_SEND) releaseProgress.await()
        }
        if (count == seam.pageCount) lastProgressReturned.complete(Unit)
    }

    override suspend fun completeDownload(
        expected: ChapterDownloadEntity,
        expectedPaths: List<String>,
        sizeBytes: Long,
    ): Boolean {
        completionCalls.incrementAndGet()
        if (seam == CancellationSeam.COMMITTED_RETURN) commit.arm(expected)
        return try {
            // Explicit generated delegate call: never invoke the interface default transaction body.
            rows.realDao.completeDownload(expected, expectedPaths, sizeBytes).also { result ->
                println("APP75 generated-completion-normal-return=$result")
                completionReturned.complete(result)
            }
        } catch (cancelled: CancellationException) {
            println("APP75 generated-completion-cancelled-return=${cancelled.javaClass.name}")
            completionCancelled.complete(cancelled)
            throw cancelled
        } finally {
            if (seam == CancellationSeam.COMMITTED_RETURN) commit.disarm()
        }
    }

    override suspend fun getDownloadByChapter(chapterId: Long): ChapterDownloadEntity? {
        val row = rows.realDao.getDownloadByChapter(chapterId)
        if (worker.job?.isCancelled == true) ownershipReadAfterCancellation.complete(row)
        return row
    }

    override suspend fun requeueIfInFlight(
        chapterId: Long,
        runningState: DownloadingState,
        compressingState: DownloadingState,
        queuedState: DownloadingState,
    ) {
        requeueCalls.incrementAndGet()
        rows.realDao.requeueIfInFlight(chapterId, runningState, compressingState, queuedState)
    }
}
