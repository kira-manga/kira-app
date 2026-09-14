package me.manga.kira.presentation.features.download.ui.test2

import android.app.Application
import kotlinx.coroutines.withTimeout
import me.manga.kira.presentation.features.download.data.DownloadingState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Android worker/service + generated Room transactions; not scheduler or device qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ANDROID_TEST_SDK], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DownloadWorkerCancellationTest {
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
