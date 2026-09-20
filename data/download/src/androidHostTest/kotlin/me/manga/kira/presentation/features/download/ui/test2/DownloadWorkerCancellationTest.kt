package me.manga.kira.presentation.features.download.ui.test2

import androidx.work.WorkInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.platform.download.DownloadOperationBusy
import me.manga.kira.presentation.features.download.data.DownloadingState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Android adapter/worker + generated Room; test-scheduler queue evidence, not device qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ANDROID_TEST_SDK], application = DownloadLocaleTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadWorkerCancellationTest {
    @Test
    fun androidCancelAllBeforeClaimAndDeletedRetryCannotScheduleWork() =
        cancellationFixture(CancellationSeam.PRECLAIM_CANCEL) {
            val repository = androidRepository()
            assertTrue(uniqueDownloadWork().isEmpty())
            start()
            assertEquals(
                listOf(rows.original.download),
                withTimeout(GATE_TIMEOUT_MILLIS) { dao.queuedSnapshot.await() },
            )
            assertNull(rows.artifacts.ownership.currentClaim(rows.original.saved.id))
            assertExclusiveBusy()

            repository.cancelAllDownloads()
            val captured = rows.download()
            assertEquals(
                rows.original.download.copy(state = DownloadingState.FAILED, errorMsg = USER_CANCELLED),
                captured,
            )
            assertNull(rows.artifacts.ownership.currentClaim(captured.chapterId))
            assertTrue(uniqueDownloadWork().isEmpty())
            assertFalse(checkNotNull(worker.job).isCompleted)
            assertExclusiveBusy()
            dao.releaseQueuedSnapshot.complete(Unit)
            joinSuccessfulWorker()
            assertExclusiveAvailable()
            assertEquals(captured, rows.download())

            repository.deleteDownload(captured.chapterId)
            assertNull(rows.realDao.getDownloadByChapter(captured.chapterId))
            assertTrue(uniqueDownloadWork().isEmpty())
            assertFalse(
                repository.retryChapterDownload(captured),
                "A deleted FAILED snapshot is not new enqueue authority",
            )
            assertNull(rows.realDao.getDownloadByChapter(captured.chapterId))
            assertNull(rows.artifacts.ownership.currentClaim(captured.chapterId))
            assertEquals(rows.original.saved, rows.saved())
            assertTrue(uniqueDownloadWork().isEmpty(), "Rejected Retry must not schedule the Android drain worker")
            assertEquals(0, dao.runningCalls.get())
            assertEquals(0, dao.progressCalls.get())
            assertEquals(0, dao.completionCalls.get())
            assertEquals(0, transport.requests.get())
            assertNull(producer.job)
            assertFalse(checkNotNull(File(paths.first()).parentFile).exists())
            receipt("actual-Android-cancelAll-before-claim; completed-delete; stale-Retry-false; unique-work-empty")
        }

    @Test
    fun androidDeleteWaitsForExclusiveBeforeCapturingItsRow() =
        cancellationFixture(CancellationSeam.PRECLAIM_CANCEL) {
            val captureEntered = CompletableDeferred<Unit>()
            val captured = CompletableDeferred<ChapterDownloadEntity?>()
            val releaseCapture = CompletableDeferred<Unit>()
            val deleteDao = object : ChapterDownloadDao by rows.realDao {
                override suspend fun getDownloadByChapter(chapterId: Long): ChapterDownloadEntity? {
                    check(captureEntered.complete(Unit)) { "Delete must retain its original capture" }
                    val selected = rows.realDao.getDownloadByChapter(chapterId)
                    captured.complete(selected)
                    releaseCapture.await() // Hold the real generated Room return, not a replacement row.
                    return selected
                }
            }
            val repository = androidRepository(deleteDao)
            coroutineScope {
                // Launch from the outside scope, not one inheriting the writer's exclusive context.
                val caller = this
                val deletion = rows.operations.withExclusive {
                    val pending = caller.async(start = CoroutineStart.UNDISPATCHED) {
                        repository.deleteDownload(rows.original.saved.id)
                    }
                    assertFalse(captureEntered.isCompleted, "Exclusive ownership must block the first DAO capture")
                    assertFalse(pending.isCompleted)
                    assertEquals(rows.original.download, rows.download())
                    pending
                }
                try {
                    assertEquals(rows.original.download, withTimeout(GATE_TIMEOUT_MILLIS) { captured.await() })
                    assertFalse(deletion.isCompleted)
                    assertExclusiveBusy()
                    releaseCapture.complete(Unit)
                    withTimeout(GATE_TIMEOUT_MILLIS) { deletion.await() }
                } finally {
                    releaseCapture.complete(Unit)
                }
            }
            assertExclusiveAvailable()
            assertNull(rows.realDao.getDownloadByChapter(rows.original.saved.id))
            assertNull(rows.artifacts.ownership.currentClaim(rows.original.saved.id))
            assertEquals(rows.original.saved, rows.saved())
            assertTrue(uniqueDownloadWork().isEmpty())
            assertNull(producer.job)
            assertEquals(0, transport.requests.get())
            receipt("actual-Android-Delete-blocked-before-first-capture; shared-gate-retained-through-cleanup")
        }

    @Test
    fun capturedAndroidDeleteCannotCancelAdmittedRetryWork() =
        cancellationFixture(CancellationSeam.PRECLAIM_CANCEL) {
            val selectedDelete = CompletableDeferred<ChapterDownloadEntity?>()
            val releaseDelete = CompletableDeferred<Unit>()
            val deleteDao =
                object : ChapterDownloadDao by rows.realDao {
                    override suspend fun getDownloadByChapter(chapterId: Long): ChapterDownloadEntity? {
                        val selected = rows.realDao.getDownloadByChapter(chapterId)
                        check(selectedDelete.complete(selected)) { "Delete unexpectedly refreshed its captured row" }
                        releaseDelete.await() // Hold only the return of the real generated Room read.
                        return selected
                    }
                }
            val repository = androidRepository(deleteDao)
            repository.cancelAllDownloads()
            val captured = rows.download()
            assertEquals(DownloadingState.FAILED, captured.state)
            assertTrue(uniqueDownloadWork().isEmpty())

            coroutineScope {
                val deletion = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        repository.deleteDownload(captured.chapterId)
                        null
                    } catch (rejected: IllegalStateException) {
                        rejected
                    }
                }
                try {
                    assertEquals(captured, withTimeout(GATE_TIMEOUT_MILLIS) { selectedDelete.await() })
                    assertTrue(
                        repository.retryChapterDownload(captured),
                        "Current FAILED Retry must reach actual WorkManager",
                    )
                    val retried = rows.download()
                    assertNotEquals(captured.id, retried.id)
                    assertEquals(
                        captured.copy(
                            id = retried.id,
                            state = DownloadingState.QUEUED,
                            progress = 0,
                            errorMsg = null,
                            sizeBytes = 0,
                        ),
                        retried,
                    )
                    val admitted = assertNotNull(rows.artifacts.ownership.currentClaim(captured.chapterId))
                    assertEquals(retried.id, admitted.downloadId)
                    val work = uniqueDownloadWork().single()
                    assertEquals(WorkInfo.State.ENQUEUED, work.state)
                    assertTrue(DownloadWorkerV2::class.java.name in work.tags)
                    assertFalse(deletion.isCompleted)

                    releaseDelete.complete(Unit)
                    val rejection = assertNotNull(withTimeout(GATE_TIMEOUT_MILLIS) { deletion.await() })
                    assertEquals("Download cleanup could not be settled", rejection.message)
                    assertEquals(retried, rows.download())
                    assertEquals(admitted, rows.artifacts.ownership.currentClaim(captured.chapterId))
                    assertTrue(rows.db.chapterArtifactDao().canPublish(admitted))
                    assertEquals(listOf(work), uniqueDownloadWork(), "Stale Delete must neither cancel nor append work")
                    assertEquals(rows.original.saved, rows.saved())
                    assertEquals(0, transport.requests.get())
                    assertNull(producer.job)
                    assertFalse(checkNotNull(File(paths.first()).parentFile).exists())
                    receipt("actual-Android-Retry-admitted-new-ledger-token-and-work; captured-Delete-rejected; work-unchanged")
                } finally {
                    releaseDelete.complete(Unit)
                }
            }
        }

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
    fun libraryRemovalWaitsForRealWorkerDrainAndFencesLateCompletion() =
        cancellationFixture(CancellationSeam.DELIVERED_SEND) {
            val readProgress = rows.db.readerProgressDao()
            val progressEpoch = readProgress.ensureSnapshot(rows.manga.api, rows.manga.url, rows.original.saved.url)
            assertTrue(readProgress.savePosition(progressEpoch, rows.original.saved.lastReadPage))
            val savedProgress = assertNotNull(readProgress.findSnapshot(rows.manga.api, rows.manga.url, rows.original.saved.url))
            assertEquals(progressEpoch.copy(pageIndex = rows.original.saved.lastReadPage), savedProgress)
            // Explicit lower-path regression control; this is not the production removal adapter.
            val library = AndroidLibraryDrainRegression(this)
            start()
            awaitSuspendedCompleteSender()
            val claim = assertNotNull(rows.artifacts.ownership.currentClaim(rows.original.saved.id))
            assertExclusiveBusy()

            coroutineScope {
                val removal = async(start = CoroutineStart.UNDISPATCHED) {
                    library.repository.removeFromLibrary(library.owner).also {
                        assertTrue(checkNotNull(producer.job).isCompleted, "Library removal returned before the real producer drained")
                    }
                }
                try {
                    val cancelled = withTimeout(GATE_TIMEOUT_MILLIS) {
                        rows.realDao.observeAllDownloads().first { downloads ->
                            downloads.singleOrNull()?.state == DownloadingState.FAILED
                        }.single()
                    }
                    assertEquals(
                        rows.original.download.copy(state = DownloadingState.FAILED, errorMsg = USER_CANCELLED),
                        cancelled,
                    )
                    assertFalse(rows.db.chapterArtifactDao().canPublish(claim))
                    assertFalse(removal.isCompleted)
                    assertEquals(rows.manga, rows.db.mangaDao().getMangaById(rows.manga.id))
                    assertEquals(rows.original.saved, rows.saved())
                    assertEquals(savedProgress, readProgress.findSnapshot(rows.manga.api, rows.manga.url, rows.original.saved.url))
                    assertEquals(0, library.snapshotReads, "The removal writer cannot enter before the original worker joins")
                    assertEquals(0, library.guardChecks)
                    assertFalse(library.exclusiveHeld)
                    assertExclusiveBusy()
                    storage.assertImages(paths)

                    // The real adapter requests WorkManager cancellation. Keep the independently
                    // started worker alive: a stop request is not evidence that its callbacks drained.
                    dao.releaseProgress.complete(Unit)
                    withTimeout(GATE_TIMEOUT_MILLIS) {
                        dao.lastProgressReturned.await()
                        sender.queuedResume.await()
                    }
                    assertEquals(FULL_BUFFER_PAGES, dao.progressCalls.get())
                    assertEquals(cancelled, rows.download())
                    assertFalse(removal.isCompleted, "Library removal must retain file custody until the real sender exits")
                    assertFalse(checkNotNull(worker.job).isCompleted)
                    assertFalse(checkNotNull(producer.job).isCompleted)
                    assertExclusiveBusy()
                    storage.assertImages(paths)

                    sender.release()
                    assertTrue(withTimeout(GATE_TIMEOUT_MILLIS) { removal.await() }.isSuccess)
                    assertFalse(storage.mangaDirectory.exists())
                    joinSuccessfulWorker()
                    assertExclusiveAvailable()
                } finally {
                    dao.releaseProgress.complete(Unit)
                    sender.release()
                }
            }

            assertEquals(1, dao.runningCalls.get())
            assertEquals(0, dao.completionCalls.get(), "Late COMPLETE must never write SUCCESS")
            assertEquals(0, dao.ownershipReadCalls.get(), "A swallowed worker failure must not stand in for the completion fence")
            assertEquals(0, dao.requeueCalls.get())
            assertEquals(FULL_BUFFER_PAGES, transport.requests.get())
            assertEquals(1, sender.retainedResumeCount.get())
            assertFalse(checkNotNull(producer.job).isCancelled)
            assertNull(rows.db.mangaDao().getMangaById(library.owner.id))
            assertNull(rows.db.mangaDao().getIdByApiAndTitle(rows.manga.api, rows.manga.title))
            assertNull(rows.db.chapterDao().getChapterByIdSuspend(rows.original.saved.id))
            assertNull(rows.realDao.getDownloadByChapter(rows.original.saved.id))
            assertNull(rows.db.chapterArtifactDao().get(rows.original.saved.id))
            assertNull(rows.db.notificationDao().getNotificationByChapterId(rows.original.saved.id))
            val cleared = assertNotNull(readProgress.findSnapshot(rows.manga.api, rows.manga.url, rows.original.saved.url))
            assertEquals(savedProgress.copy(workGeneration = savedProgress.workGeneration + 1L, pageIndex = null), cleared)
            assertFalse(readProgress.savePosition(savedProgress, rows.original.saved.lastReadPage), "The old epoch cannot resurrect removed progress")
            assertEquals(cleared, readProgress.findSnapshot(rows.manga.api, rows.manga.url, rows.original.saved.url))
            assertEquals(2, library.snapshotReads)
            assertEquals(2, library.guardChecks, "Preflight and final writer must both revalidate real drain and held exclusion")
            assertFalse(library.exclusiveHeld)
            assertTrue(paths.none { File(it).exists() })
            assertFalse(storage.mangaDirectory.exists(), "No chapter or manga directory may reappear after both actual Jobs join")
            receipt("lower-path-typed-Library-removal-retained-parent-until-original-drain; late-COMPLETE-fenced; progress-epoch-cleared; no-rows-or-root-recreated")
        }

    @Test
    fun deliveredCompleteSurvivesCancelledSender() =
        cancellationFixture(CancellationSeam.DELIVERED_SEND) {
            start()
            awaitSuspendedCompleteSender()
            awaitCommittedWithRetainedSend()
            assertExclusiveBusy()

            stopAndObserveCancellation()
            producer.awaitCancellation()
            assertFalse(checkNotNull(producer.job).isCompleted)
            assertExclusiveBusy()
            receipt("worker-and-producer-cancelled-before-send-resume-release")
            sender.release()
            joinJobs()
            assertExclusiveAvailable()
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
            assertExclusiveBusy()
            receipt("native-outer-commit-held; independent-physical-reader-sees-both-committed-rows")

            stopAndObserveCancellation()
            assertFalse(checkNotNull(worker.job).isCompleted)
            assertExclusiveBusy()
            receipt("actual-worker-Job-cancelled-before-native-step-return-release")
            commit.release()
            withTimeout(GATE_TIMEOUT_MILLIS) { dao.completionCancelled.await() }
            joinJobs()
            assertExclusiveAvailable()
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
            assertExclusiveBusy()
            stopAndObserveCancellation()
            joinJobs()
            assertExclusiveAvailable()
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
            assertExclusiveBusy()
            receipt("real-user-FAILED-read-back-before-stop")

            stopAndObserveCancellation()
            joinJobs()
            assertExclusiveAvailable()
            assertPartialStopped(DownloadingState.FAILED, USER_CANCELLED)
            assertEquals(failed, rows.download())
            assertEquals(0, dao.completionCalls.get())
            receipt("partial-file-deleted; existing-FAILED-preserved; no-queued-resurrection")
        }
}

private suspend fun DownloadWorkerCancellationFixture.assertExclusiveBusy() {
    assertFailsWith<DownloadOperationBusy> {
        rows.operations.withExclusive { error("Exclusive writer entered while the actual operation remained owned") }
    }
}

private suspend fun DownloadWorkerCancellationFixture.assertExclusiveAvailable() {
    rows.operations.withExclusive { /* Actual gate acquisition, not a queue/Job-state inference. */ }
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
