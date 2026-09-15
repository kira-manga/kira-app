package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual iOS receiver/window over the existing file-backed Room fixture; no OS scheduling claim. */
class IosBackgroundArtifactTest {
    @Test
    fun receiverPublicationFailureExhaustsOriginalAttemptAndCannotChargeReplacementOrCancellation() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        try {
            val original = fixture.seed()
            val next = fixture.seed()
            val claim = fixture.prepareAttempt(original, DownloadingState.RUNNING, failures = 2)
            val nextClaim = fixture.prepareAttempt(next, DownloadingState.QUEUED)
            val transport = ArtifactTestTransport()
            val engine = fixture.engine(CoroutineScope(coroutineContext + hostJob), transport)
            val failed = ReceiverPage(fixture, original, "failed")
            transport.deliverPage(original, claim.token, failed.page).await()
            assertTrue(failed.discarded.isCompleted, "Receipt includes receiver failure handling and staging disposal")

            assertEquals(1, failed.publications)
            assertFalse(fixture.system.exists(failed.path))
            assertEquals(DownloadingState.FAILED, fixture.download(original).state)
            assertEquals(3, fixture.manifest(original).pages.single().attempts)
            assertTrue(original.saved.id to claim.token in transport.cancelled)
            val transfer = transport.requests.receive()
            assertEquals(next.saved.id, transfer.chapterId, "The exhausted receiver releases the transfer slot")
            assertEquals(nextClaim.token, transfer.attemptToken)
            assertEquals(DownloadingState.RUNNING, fixture.download(next).state)
            assertEquals(listOf(next.saved.id), transport.enqueued.map { it.chapterId })
            fixture.artifacts.settle(claim)
            assertNull(fixture.artifacts.ownership.currentClaim(original.saved.id))
            assertEquals(3, fixture.manifest(original).pages.single().attempts, "Ordinary failure retains retry work")

            engine.onCancel(next.saved.id)
            val replacement = assertNotNull(fixture.artifacts.enqueue(
                fixture.saved(original), original.download.copy(state = DownloadingState.QUEUED),
            ))
            assertNotEquals(claim.token, replacement.token)
            assertEquals(1, fixture.artifacts.ownership.publish(replacement) {
                fixture.dao.claimQueuedAsRunning(original.saved.id)
            })
            fixture.writeManifest(original, replacement)
            val stale = ReceiverPage(fixture, original, "stale")
            transport.deliverPage(original, claim.token, stale.page).await()
            assertTrue(stale.discarded.isCompleted)
            assertEquals(0, stale.publications)
            assertEquals(0, fixture.manifest(original).pages.single().attempts)
            assertEquals(DownloadingState.RUNNING, fixture.download(original).state)

            assertNotNull(fixture.artifacts.cancel(original.saved.id, "__cancelled_by_user__"))
            val cancelled = ReceiverPage(fixture, original, "cancelled")
            transport.deliverPage(original, replacement.token, cancelled.page).await()
            assertTrue(cancelled.discarded.isCompleted)
            assertEquals(0, cancelled.publications)
            assertEquals(0, fixture.manifest(original).pages.single().attempts)
            assertEquals("__cancelled_by_user__", fixture.download(original).errorMsg)
            assertEquals(listOf(next.saved.id), transport.enqueued.map { it.chapterId })
            assertTrue(fixture.artifacts.settle(replacement))
        } finally {
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun parentReopenRefillsIosWindowWithoutExternalPump() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        val releaseRead = CompletableDeferred<Unit>()
        try {
            val completed = fixture.seed()
            fixture.dao.updateStateChId(completed.saved.id, DownloadingState.SUCCESS)
            fixture.db.backupDao().updateChapterRow(completed.saved.copy(isDownloaded = true))
            val waiting = fixture.seed(mangaId = completed.saved.mangaId)
            val claim = fixture.prepareAttempt(waiting, DownloadingState.QUEUED)
            val transport = ArtifactTestTransport()
            val engine = fixture.engine(CoroutineScope(coroutineContext + hostJob), transport)
            val reading = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val actions = DownloadsActionRepositoryImpl(
                legacy = object : DownloadRepository by engine {
                    override suspend fun cancelARunningChapter(chapterId: Long, mangaId: Long) {
                        engine.cancelARunningChapter(chapterId, mangaId)
                        closed.complete(Unit) // Actual onCancel filled the window while parent M was closed.
                    }
                },
                chapterDownloadDao = fixture.dao,
                chapterDao = fixture.db.chapterDao(),
                appFileSystem = fixture.appFileSystem,
                artifacts = fixture.artifacts.ownership,
            )
            coroutineScope {
                val pin = launch {
                    fixture.artifacts.ownership.read(completed.saved.id) {
                        reading.complete(Unit)
                        releaseRead.await()
                    }
                }
                try {
                    reading.await()
                    val deletion = async { actions.deleteDownloadedChapter(completed.saved.id) }
                    closed.await()
                    assertFalse(deletion.isCompleted)
                    assertEquals(DownloadingState.QUEUED, fixture.download(waiting).state)
                    assertTrue(transport.enqueued.isEmpty())
                    releaseRead.complete(Unit)
                    pin.join()
                    assertTrue(deletion.await().isSuccess)
                    val transfer = transport.requests.receive() // No enqueue/pump/lifecycle call wakes it.
                    assertEquals(waiting.saved.id, transfer.chapterId)
                    assertEquals(claim.token, transfer.attemptToken)
                    assertEquals(waiting.download.id, fixture.download(waiting).id)
                    assertEquals(DownloadingState.RUNNING, fixture.download(waiting).state)
                    assertNull(fixture.dao.getDownloadByChapter(completed.saved.id))
                    assertFalse(fixture.system.exists(fixture.appFileSystem.chapterDir(completed.saved.mangaId, completed.saved.id)))
                } finally {
                    releaseRead.complete(Unit)
                }
            }
        } finally {
            releaseRead.complete(Unit)
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }
}
