package me.manga.kira.data.repository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.manga.kira.data.backup.RestoredChapterArchive
import me.manga.kira.data.backup.RestoredDownloadPublisher
import me.manga.kira.data.download.artifacts.ChapterArtifactReference
import me.manga.kira.data.local.dao.ChapterRestoreOutcome
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real shared runtime/Room/file joins. Writer bytes are synthetic; native codecs have separate gates. */
class ChapterArtifactConsumerTest {
    @Test
    fun deleteDownloadedChapterDrainsActualProducerAndLatePublicationCannotRecreateFiles() = downloadRecoveryTest {
        val original = seed(DownloadingState.FAILED)
        val runtime = artifactRuntime
        val claim = assertNotNull(runtime.downloads.enqueue(original.saved, original.download.copy(state = DownloadingState.QUEUED)))
        val directory = appFileSystem.chapterDir(original.saved.mangaId, original.saved.id)
        val entered = CompletableDeferred<Unit>()
        val revoked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = object : FakeDownloadRepository() {
            override suspend fun cancelARunningChapter(chapterId: Long, mangaId: Long) {
                runtime.downloads.cancel(chapterId, "__cancelled_by_user__")
                revoked.complete(Unit)
            }
        }
        coroutineScope {
            val producer = launch {
                runtime.ownership.producing(claim) {
                    entered.complete(Unit)
                    release.await()
                    assertNull(runtime.ownership.files(claim) {
                        fs.createDirectories(directory)
                        fs.write(directory / "image_99.png") { writeUtf8("must not publish") }
                    })
                }
            }
            entered.await()
            val deletion = async { actions(engine = engine).deleteDownloadedChapter(original.saved.id) }
            revoked.await()
            assertFalse(deletion.isCompleted, "A cancellation request is not a drained producer")
            assertRetainedFiles(original)
            release.complete(Unit)
            producer.join()
            assertTrue(deletion.await().isSuccess)
        }
        assertFalse(fs.exists(directory))
        assertFalse(saved(original).isDownloaded)
        assertTrue(saved(original).localImagePaths.isEmpty())
        assertNull(dao.getDownloadByChapter(original.saved.id))
        assertNull(runtime.dao.get(original.saved.id))
        assertFalse(runtime.dao.canPublish(claim))
    }

    @Test
    fun manualConversionPinsWriterAndHistoryOnlyDeletionDoesNotRecreateLedger() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val archive = appFileSystem.chapterDir(original.saved.mangaId, original.saved.id) / "chapter_${original.saved.id}.cbz"
        val converter = settingsConverter(CbzCallerWriter { _, _ ->
            entered.complete(Unit)
            release.await()
            fs.write(archive) { writeUtf8("converted-archive") }
            archive
        })
        coroutineScope {
            val converting = async { converter.compressExistingDownloads() }
            entered.await()
            assertNull(artifactRuntime.downloads.enqueue(original.saved, original.download.copy(state = DownloadingState.QUEUED)))
            // SUCCESS history eviction is permitted while the writer runs, but is never resurrected.
            assertTrue(actions().deleteDownload(original.saved.id).isSuccess)
            release.complete(Unit)
            assertTrue(converting.await().isSuccess)
        }
        assertEquals(1, converter.observeCbzConversion().first().convertedChapters)
        assertEquals(listOf(archive.toString()), saved(original).localImagePaths)
        assertNull(dao.getDownloadByChapter(original.saved.id))
        assertNull(artifactRuntime.dao.get(original.saved.id)?.token)
    }

    @Test
    fun retryCancellationKeepsRestoreAndNewSuccessRetiresOnlyItsReferencedGeneration() = downloadRecoveryTest {
        val original = seed(DownloadingState.FAILED)
        val runtime = artifactRuntime
        val source = appFileSystem.cacheDir / "validated.cbz"
        fs.createDirectories(appFileSystem.cacheDir)
        fs.write(source) { writeUtf8("validated-backup-archive") }
        val publisher = RestoredDownloadPublisher(runtime.ownership, runtime.dao, runtime.commits, appFileSystem, runtime.recovery)
        assertEquals(ChapterRestoreOutcome.COMMITTED, publisher.publish(
            RestoredChapterArchive(source, assertNotNull(fs.metadata(source).size)), original.saved, "test", "Manga",
        ))
        val restored = saved(original)
        val relative = assertNotNull(runtime.dao.get(restored.id)?.committedRelativePath)
        val generation = ChapterArtifactReference.resolve(appFileSystem, ChapterArtifactOwner.of(restored), relative)
        val directory = appFileSystem.chapterDir(restored.mangaId, restored.id)
        val first = assertNotNull(runtime.downloads.enqueue(restored, original.download.copy(state = DownloadingState.QUEUED)))
        runtime.ownership.files(first) { fs.write(directory / "image_0.png") { writeUtf8("partial") } }
        runtime.downloads.cancel(restored.id, "__cancelled_by_user__")
        assertTrue(runtime.downloads.settle(first))
        assertEquals(restored, saved(original))
        assertTrue(fs.exists(generation))
        assertFalse(fs.exists(directory / "image_0.png"))

        val unowned = directory / "_restored/22222222-2222-4222-8222-222222222222/chapter.cbz"
        fs.createDirectories(assertNotNull(unowned.parent))
        fs.write(unowned) { writeUtf8("unowned-generation") }
        val second = assertNotNull(runtime.downloads.enqueue(restored, original.download.copy(state = DownloadingState.QUEUED)))
        val row = assertNotNull(dao.getDownloadByChapter(restored.id))
        val canonical = directory / "chapter_${restored.id}.cbz"
        assertTrue(runtime.ownership.files(second) {
            fs.write(canonical) { writeUtf8("completed-new-download") }
            runtime.downloads.complete(second, row, listOf(canonical.toString()))
        } == true)
        assertTrue(runtime.downloads.settle(second))
        assertFalse(fs.exists(generation))
        assertTrue(fs.exists(unowned), "Retirement has authority over only the recorded prior generation")
        assertEquals(listOf(canonical.toString()), saved(original).localImagePaths)
        assertNull(runtime.dao.get(restored.id)?.committedRelativePath)
    }

    @Test
    fun restartFinishesDurableDeleteEvenAfterParentRowsWereRemoved() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val directory = appFileSystem.chapterDir(original.saved.mangaId, original.saved.id)
        val claim = assertNotNull(artifactRuntime.dao.reserveRemoval(ChapterArtifactOwner.of(original.saved), TOKEN))
        assertTrue(artifactRuntime.commits.clearForRemoval(claim))
        db.libraryDeo().removeMangaWithChapters(original.saved.mangaId)
        assertTrue(fs.exists(directory))
        reopen()
        artifactRuntime.ownership.read(original.saved.id) { assertNull(it) }
        assertFalse(fs.exists(directory))
        assertNull(artifactRuntime.dao.get(original.saved.id))
    }

    @Test
    fun ordinaryFailureRetainsPagesThroughSettlementOrRestartForRetryButCancelStillCleans() = downloadRecoveryTest {
        for (restart in listOf(false, true)) {
            val original = seed(DownloadingState.FAILED)
            val runtime = artifactRuntime
            val claim = assertNotNull(runtime.downloads.enqueue(
                original.saved, original.download.copy(state = DownloadingState.QUEUED),
            ))
            val directory = appFileSystem.chapterDir(original.saved.mangaId, original.saved.id)
            val page = directory / "image_0.png"
            val manifest = directory / "manifest.json"
            val bytes = recoveryTestPng()
            runtime.ownership.files(claim) {
                fs.write(page) { write(bytes) }
                fs.write(manifest) { writeUtf8("retained-attempt-${claim.token}") }
            }
            assertTrue(runtime.downloads.fail(claim, "temporary source failure"))
            if (restart) {
                reopen()
                artifactRuntime.ownership.read(original.saved.id) { assertNull(it?.token) }
            } else {
                assertTrue(runtime.downloads.settle(claim))
            }
            val recovered = artifactRuntime
            assertNull(recovered.dao.get(original.saved.id)?.token)
            assertContentEquals(bytes, fs.read(page) { readByteArray() })
            assertTrue(fs.exists(manifest))
            assertEquals("temporary source failure", download(original).errorMsg)

            val retry = assertNotNull(recovered.downloads.enqueue(
                saved(original), original.download.copy(state = DownloadingState.QUEUED),
            ))
            assertFalse(recovered.dao.canPublish(claim), "Retained bytes never revive the old attempt")
            assertContentEquals(bytes, recovered.ownership.files(retry) { fs.read(page) { readByteArray() } })
            assertNotNull(recovered.downloads.cancel(original.saved.id, "__cancelled_by_user__"))
            assertTrue(recovered.downloads.settle(retry))
            assertFalse(fs.exists(page))
            assertFalse(fs.exists(manifest))
        }
    }

    @Test
    fun closingParentSkipsBlockedWorkAndResumesOriginalQueuedAttemptWithoutExternalWake() = downloadRecoveryTest {
        val completed = seed(isDownloaded = true)
        val running = seed(DownloadingState.QUEUED, mangaId = completed.saved.mangaId)
        val waiting = seed(DownloadingState.QUEUED, mangaId = completed.saved.mangaId)
        val unrelated = seed(DownloadingState.QUEUED)
        val runtime = artifactRuntime
        val runningClaim = assertNotNull(runtime.downloads.claim(running.download))
        val waitingClaim = assertNotNull(runtime.downloads.claim(waiting.download))
        assertEquals(1, runtime.ownership.publish(runningClaim) { dao.claimQueuedAsRunning(running.saved.id) })
        assertEquals(waiting.download, dao.getNextQueuedChapter())
        assertEquals(waiting.download, dao.getQueuedChaptersForWorker().first())
        assertEquals(unrelated.download, dao.getQueuedChapters().first(), "iOS keeps its separate newest-first order")

        val reading = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val producing = CompletableDeferred<Unit>()
        val finishRunning = CompletableDeferred<Unit>()
        val engine = object : FakeDownloadRepository() {
            override suspend fun cancelARunningChapter(chapterId: Long, mangaId: Long) {
                assertEquals(completed.saved.id, chapterId)
                assertNull(runtime.downloads.cancel(chapterId, "__cancelled_by_user__"))
                closed.complete(Unit) // The real deletion already closed M; SUCCESS A has no download claim.
            }
        }
        coroutineScope {
            val pin = launch {
                runtime.ownership.read(completed.saved.id) {
                    reading.complete(Unit)
                    releaseRead.await()
                }
            }
            val work = async {
                val complete = runtime.ownership.producing(runningClaim) {
                    producing.complete(Unit)
                    finishRunning.await()
                    runtime.downloads.complete(runningClaim, download(running), running.saved.localImagePaths)
                }
                complete == true && runtime.downloads.settle(runningClaim)
            }
            try {
                reading.await()
                producing.await()
                val deletion = async { actions(engine = engine).deleteDownloadedChapter(completed.saved.id) }
                closed.await()
                assertFalse(deletion.isCompleted, "Removal must wait for the actual reader pin")
                finishRunning.complete(Unit)
                assertTrue(work.await(), "Already-admitted B can finish without reacquiring the parent gate")

                val next = assertNotNull(runtime.downloads.awaitNextQueued { dao.getQueuedChaptersForWorker() })
                assertEquals(unrelated.download, next.chapter, "Closed C must not park unrelated D")
                assertEquals(1, runtime.ownership.publish(next.claim) { dao.claimQueuedAsRunning(unrelated.saved.id) })
                assertTrue(runtime.downloads.complete(next.claim, download(unrelated), unrelated.saved.localImagePaths))
                assertTrue(runtime.downloads.settle(next.claim))

                val blocked = runtime.downloads.scanQueued(dao.getQueuedChaptersForWorker())
                assertNull(blocked.attempt)
                assertEquals(1, blocked.parentReopens.size)
                assertFalse(blocked.parentReopens.single().isCompleted)
                val queueRead = CompletableDeferred<Unit>()
                val resumed = async(start = CoroutineStart.UNDISPATCHED) {
                    runtime.downloads.awaitNextQueued {
                        dao.getQueuedChaptersForWorker().also { queueRead.complete(Unit) }
                    }
                }
                queueRead.await()
                assertFalse(resumed.isCompleted)
                releaseRead.complete(Unit)
                pin.join()
                assertTrue(deletion.await().isSuccess)
                val resumedAttempt = assertNotNull(resumed.await())
                assertEquals(waiting.download, resumedAttempt.chapter)
                assertEquals(waitingClaim, resumedAttempt.claim, "Reopen rechecks the original queued ledger/token")
                assertEquals(DownloadingState.QUEUED, download(waiting).state)
                assertNull(dao.getDownloadByChapter(completed.saved.id))
            } finally {
                finishRunning.complete(Unit)
                releaseRead.complete(Unit)
            }
        }
        assertFalse(fs.exists(appFileSystem.chapterDir(completed.saved.mangaId, completed.saved.id)))
    }

    private companion object { const val TOKEN = "11111111-1111-4111-8111-111111111111" }
}
