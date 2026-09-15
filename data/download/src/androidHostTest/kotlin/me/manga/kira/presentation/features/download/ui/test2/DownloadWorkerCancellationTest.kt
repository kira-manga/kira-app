package me.manga.kira.presentation.features.download.ui.test2

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.presentation.features.download.data.DownloadingState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Android worker/service + generated Room transactions; not scheduler or device qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ANDROID_TEST_SDK], application = DownloadLocaleTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadWorkerCancellationTest {
    @Test
    fun userCancelBeforeClaimRejectsCapturedQueuedRow() =
        cancellationFixture(CancellationSeam.PRECLAIM_CANCEL) {
            start()
            val selected = withTimeout(GATE_TIMEOUT_MILLIS) { dao.queuedSnapshot.await() }
            assertEquals(listOf(rows.original.download), selected)
            assertNull(rows.artifacts.ownership.currentClaim(rows.original.saved.id))

            val cancelled = assertNotNull(rows.artifacts.cancel(rows.original.saved.id, USER_CANCELLED))
            val failed = rows.original.download.copy(state = DownloadingState.FAILED, errorMsg = USER_CANCELLED)
            assertEquals(failed, rows.download())
            assertTrue(rows.artifacts.settle(cancelled))
            assertNull(rows.artifacts.ownership.currentClaim(rows.original.saved.id))
            assertFalse(checkNotNull(worker.job).isCompleted)
            receipt("user-cancel-settled; actual-queued-snapshot-held-before-worker-claim")

            dao.releaseQueuedSnapshot.complete(Unit)
            joinSuccessfulWorker()
            assertEquals(failed, rows.download())
            assertEquals(rows.original.saved, rows.saved())
            assertEquals(0, dao.runningCalls.get())
            assertEquals(0, dao.progressCalls.get())
            assertEquals(0, dao.completionCalls.get())
            assertEquals(0, transport.requests.get())
            assertNull(producer.job)
            assertNull(rows.artifacts.ownership.currentClaim(rows.original.saved.id))
            assertFalse(checkNotNull(File(paths.first()).parentFile).exists())
            receipt("stale-queue-rejected; no-RUNNING-write-or-producer-or-files")
        }

    @Test
    fun cancelAndPurgeFenceBufferedProgressAndCompleteUntilProducerDrains() =
        cancellationFixture(CancellationSeam.DELIVERED_SEND) {
            start()
            awaitSuspendedCompleteSender()
            val cancelled = assertNotNull(rows.artifacts.cancel(rows.original.saved.id, USER_CANCELLED))
            val failed = rows.original.download.copy(state = DownloadingState.FAILED, errorMsg = USER_CANCELLED)
            assertEquals(failed, rows.download())
            assertEquals(rows.original.saved, rows.saved())
            assertFalse(rows.db.chapterArtifactDao().canPublish(cancelled))

            coroutineScope {
                val purgeEntered = CompletableDeferred<Unit>()
                val purge = async(start = CoroutineStart.UNDISPATCHED) {
                    rows.artifacts.ownership.removeChapter(ChapterArtifactOwner.of(rows.original.saved)) {
                        purgeEntered.complete(Unit)
                    }.also {
                        assertTrue(checkNotNull(producer.job).isCompleted, "Purge returned before actual producer drain")
                    }
                }
                try {
                    withTimeout(GATE_TIMEOUT_MILLIS) { purgeEntered.await() }
                    // Deliberately do not stop the worker Job: late real buffered states must run
                    // through its ownership fences, not disappear through flow cancellation.
                    dao.releaseProgress.complete(Unit)
                    withTimeout(GATE_TIMEOUT_MILLIS) {
                        dao.lastProgressReturned.await()
                        sender.queuedResume.await()
                    }
                    assertEquals(FULL_BUFFER_PAGES, dao.progressCalls.get())
                    assertEquals(failed, rows.download())
                    assertEquals(rows.original.saved, rows.saved())
                    assertFalse(purge.isCompleted, "A revocation request is not drained file custody")
                    assertFalse(checkNotNull(producer.job).isCompleted)
                    storage.assertImages(paths)
                    receipt("cancelled-ledger-unchanged-after-late-progress; real-COMPLETE-send-resume-held; purge-pending")

                    sender.release()
                    joinSuccessfulWorker()
                    assertTrue(withTimeout(GATE_TIMEOUT_MILLIS) { purge.await() })
                } finally {
                    dao.releaseProgress.complete(Unit)
                    sender.release()
                }
            }

            assertEquals(1, dao.runningCalls.get())
            assertEquals(0, dao.completionCalls.get())
            // Normal flow completion plus no fallback read excludes a swallowed COMPLETE/file
            // failure or missing-terminal error being mistaken for the fenced completion path.
            assertEquals(0, dao.ownershipReadCalls.get())
            assertEquals(0, dao.requeueCalls.get())
            assertEquals(FULL_BUFFER_PAGES, transport.requests.get())
            assertEquals(1, sender.retainedResumeCount.get())
            assertFalse(checkNotNull(producer.job).isCancelled)
            assertNull(rows.realDao.getDownloadByChapter(rows.original.saved.id))
            assertNull(rows.db.chapterArtifactDao().get(rows.original.saved.id))
            assertEquals(rows.original.saved, rows.saved())
            val notification = assertNotNull(rows.db.notificationDao().getNotificationByChapterId(rows.original.saved.id))
            assertFalse(notification.isDownloaded)
            assertTrue(notification.localImagePaths.isEmpty())
            assertFalse(checkNotNull(File(paths.first()).parentFile).exists())
            receipt("late-COMPLETE-collected-without-publication; actual-jobs-joined; purge-left-no-ledger-or-files")
        }

    @Test
    fun deliveredCompleteSurvivesCancelledSender() =
        cancellationFixture(CancellationSeam.DELIVERED_SEND) {
            start()
            awaitSuspendedCompleteSender()
            awaitCommittedWithRetainedSend()

            stopAndObserveCancellation()
            producer.awaitCancellation()
            assertFalse(checkNotNull(producer.job).isCompleted)
            receipt("worker-and-producer-cancelled-before-send-resume-release")
            sender.release()
            joinJobs()
            assertCommitted()
            assertEquals(rows.completedDownload(paths), dao.ownershipReadAfterCancellation.await())
            assertFalse(dao.completionCancelled.isCompleted)
            assertEquals(0, dao.requeueCalls.get())
            assertEquals(1, sender.retainedResumeCount.get())
        }

    @Test
    fun nativeCommitSurvivesCancelledRoomReturn() =
        cancellationFixture(CancellationSeam.COMMITTED_RETURN) {
            start()
            withTimeout(GATE_TIMEOUT_MILLIS) { commit.nativeCommitted.await() }
            assertEquals(1, commit.hits.get())
            rows.assertCommittedOnIndependentConnection(commit, paths)
            storage.assertImages(paths)
            assertFalse(dao.completionReturned.isCompleted)
            assertFalse(dao.completionCancelled.isCompleted)
            receipt("native-outer-commit-held; independent-physical-reader-sees-both-committed-rows")

            stopAndObserveCancellation()
            receipt("actual-worker-Job-cancelled-before-native-step-return-release")
            commit.release()
            withTimeout(GATE_TIMEOUT_MILLIS) { dao.completionCancelled.await() }
            joinJobs()
            assertFalse(dao.completionReturned.isCompleted)
            assertCommitted()
            assertEquals(rows.completedDownload(paths), dao.ownershipReadAfterCancellation.await())
            assertEquals(0, dao.requeueCalls.get())
            receipt("actual-Room-return-CancellationException; no-normal-completion-return")
        }

    @Test
    fun partialSystemStopCleansFilesAndRequeuesSameLedger() =
        cancellationFixture(CancellationSeam.PARTIAL_SYSTEM) {
            start()
            partialCheckpoint()
            stopAndObserveCancellation()
            joinJobs()
            assertPartialStopped(DownloadingState.QUEUED, rows.original.download.errorMsg)
            assertEquals(0, dao.completionCalls.get())
            assertEquals(1, dao.requeueCalls.get())
            receipt("system-connectivity-stop; partial-file-deleted; same-ledger-queued")
        }

    @Test
    fun existingUserFailureSurvivesPartialSystemStop() =
        cancellationFixture(CancellationSeam.PARTIAL_FAILED) {
            start()
            partialCheckpoint()
            rows.realDao.updateFailure(rows.original.saved.id, USER_CANCELLED)
            val failed = rows.download()
            assertEquals(DownloadingState.FAILED, failed.state)
            assertEquals(USER_CANCELLED, failed.errorMsg)
            receipt("real-user-FAILED-read-back-before-stop")

            stopAndObserveCancellation()
            joinJobs()
            assertPartialStopped(DownloadingState.FAILED, USER_CANCELLED)
            assertEquals(failed, rows.download())
            assertEquals(0, dao.completionCalls.get())
            receipt("partial-file-deleted; existing-FAILED-preserved; no-queued-resurrection")
        }
}

private suspend fun DownloadWorkerCancellationFixture.awaitSuspendedCompleteSender() {
    assertNull(System.getProperty("kotlinx.coroutines.channels.defaultBuffer"))
    withTimeout(GATE_TIMEOUT_MILLIS) {
        dao.firstProgressReturned.await()
        sender.suspendedSend.await()
    }
    assertEquals(FULL_BUFFER_PAGES, transport.requests.get())
    assertEquals(1, dao.progressCalls.get())
    assertEquals(paths, dao.notificationPaths.await())
    storage.assertImages(paths)
    assertFalse(checkNotNull(producer.job).isCompleted)
    receipt("65-real-progress-sends; default-buffer64-full; marked-producer-turn-returned-unfinished")
}

private suspend fun DownloadWorkerCancellationFixture.awaitCommittedWithRetainedSend() {
    dao.releaseProgress.complete(Unit)
    withTimeout(GATE_TIMEOUT_MILLIS) {
        sender.queuedResume.await()
        assertTrue(dao.completionReturned.await())
    }
    assertEquals(FULL_BUFFER_PAGES, dao.progressCalls.get())
    assertEquals(1, sender.retainedResumeCount.get())
    assertFalse(checkNotNull(producer.job).isCompleted)
    assertCommitted()
    receipt("real-completion-returned; same-producer-send-resume-retained")
}

private const val ANDROID_TEST_SDK = 35
