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
import me.manga.kira.data.download.selection.DownloadCatalogAdmission
import me.manga.kira.platform.download.BackgroundTransport
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifestStore
import me.manga.kira.presentation.features.download.domain.clean.ManifestPage
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual iOS receiver/window over the existing file-backed Room fixture; no OS scheduling claim. */
class IosBackgroundArtifactTest {
    @Test
    fun retryRefusesUnreadableMismatchedOrUnwritableRetainedManifestBeforeQueueAdmission() = runTest {
        for (failure in listOf("unreadable", "source", "write")) {
            val fixture = IosCbzFinalizationFixture()
            val hostJob = SupervisorJob(coroutineContext[Job])
            try {
                val original = fixture.seed()
                val prior = fixture.prepareAttempt(original, DownloadingState.RUNNING)
                assertTrue(fixture.artifacts.fail(prior, "ordinary failure"))
                assertTrue(fixture.artifacts.settle(prior))
                val captured = fixture.download(original)
                val path = fixture.appFileSystem.chapterDir(original.saved.mangaId, original.saved.id) / "manifest.json"
                when (failure) {
                    "unreadable" -> fixture.system.write(path) { writeUtf8("{broken") }
                    "source" -> DownloadManifestStore(fixture.appFileSystem).write(fixture.manifest(original).copy(api = "other"))
                }
                val before = fixture.system.read(path) { readByteArray() }
                val system = object : ForwardingFileSystem(fixture.system) {
                    override fun atomicMove(source: Path, target: Path) {
                        if (failure == "write" && target == path) throw IOException("retry manifest publication failed")
                        super.atomicMove(source, target)
                    }
                }
                val files = object : AppFileSystem by fixture.appFileSystem {
                    override fun fileSystem() = system
                }
                val transport = ArtifactTestTransport(fixture.operations, ready = true)
                val engine = fixture.engine(CoroutineScope(coroutineContext + hostJob), transport, files = files)
                assertFailsWith<Exception> { engine.retryChapterDownload(captured) }
                assertEquals(captured, fixture.download(original))
                assertNull(fixture.artifacts.ownership.currentClaim(original.saved.id))
                assertTrue(transport.enqueued.isEmpty())
                assertContentEquals(before, fixture.system.read(path) { readByteArray() })
            } finally {
                hostJob.cancelAndJoin()
                fixture.close()
            }
        }
    }

    @Test
    fun retryRebindsRetainedRosterWithoutRedownloadingVerifiedPageOrAcceptingOldCapture() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        try {
            val original = fixture.seed()
            val prior = fixture.prepareAttempt(original, DownloadingState.RUNNING)
            val store = DownloadManifestStore(fixture.appFileSystem)
            val retained = ReceiverPage(fixture, original, "retry-retained", failPublication = false)
            val retainedPath = assertNotNull(fixture.artifacts.ownership.files(prior) {
                store.write(fixture.manifest(original).copy(pages = listOf(
                    ManifestPage(0, "https://example.test/page0.png", emptyMap()),
                    ManifestPage(1, "https://example.test/page1.png", emptyMap(), attempts = 3),
                )))
                // CBZ fixture inputs are not download filenames; use the actual page publisher.
                original.pages.keys.forEach { fixture.system.delete(it) }
                retained.page.publish(fixture.appFileSystem.chapterDir(original.saved.mangaId, original.saved.id), 0)
            })
            assertEquals("image_0.png", retainedPath.name)
            assertTrue(fixture.artifacts.fail(prior, "ordinary failure"))
            assertTrue(fixture.artifacts.settle(prior))
            val captured = fixture.download(original)
            val transport = ArtifactTestTransport(fixture.operations, ready = true)
            val engine = fixture.engine(CoroutineScope(coroutineContext + hostJob), transport)
            assertTrue(engine.retryChapterDownload(captured))
            val request = transport.requests.receive()
            assertEquals(1, request.pageIndex)
            assertNotEquals(prior.token, request.attemptToken)
            assertNotEquals(captured.id, fixture.download(original).id)
            assertEquals(listOf(1), transport.enqueued.map { it.pageIndex })
            assertEquals(request.attemptToken, fixture.manifest(original).attemptToken)
            assertEquals(0, fixture.manifest(original).pages[1].attempts)
            assertContentEquals(original.pages.values.last(), fixture.system.read(retainedPath) { readByteArray() })
            assertFalse(engine.retryChapterDownload(captured))
            assertEquals(listOf(1), transport.enqueued.map { it.pageIndex })
        } finally {
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun retryNeverResetsDurablePagePolicyRefusal() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        try {
            val original = fixture.seed()
            val prior = fixture.prepareAttempt(original, DownloadingState.RUNNING)
            assertNotNull(fixture.artifacts.ownership.files(prior) {
                DownloadManifestStore(fixture.appFileSystem).write(fixture.manifest(original).copy(
                    pages = listOf(ManifestPage(0, "https://example.test/page.png", emptyMap(), attempts = 3, policyRejected = true)),
                ))
            })
            assertTrue(fixture.artifacts.fail(prior, "__page_policy_rejected__:ENCODED_OR_NATIVE_POLICY"))
            assertTrue(fixture.artifacts.settle(prior))
            val captured = fixture.download(original)
            val transport = ArtifactTestTransport(fixture.operations, ready = true)
            val engine = fixture.engine(CoroutineScope(coroutineContext + hostJob), transport)
            assertTrue(engine.retryChapterDownload(captured))
            assertTrue(transport.enqueued.isEmpty())
            assertEquals(DownloadingState.FAILED, fixture.download(original).state)
            assertTrue(fixture.manifest(original).pages.single().policyRejected)
            assertEquals(3, fixture.manifest(original).pages.single().attempts)
        } finally {
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun receiverPublicationFailureExhaustsOriginalAttemptAndCannotChargeReplacementOrCancellation() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        try {
            val original = fixture.seed()
            val next = fixture.seed()
            val claim = fixture.prepareAttempt(original, DownloadingState.RUNNING, failures = 2)
            val nextClaim = fixture.prepareAttempt(next, DownloadingState.QUEUED)
            val transport = ArtifactTestTransport(fixture.operations)
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
            val parentClosed = CompletableDeferred<Unit>()
            val fillCompletedWhileClosed = CompletableDeferred<Boolean>()
            val catalog = TestDownloadCatalogAdmission(fixture.operations)
            val observedCatalog = object : DownloadCatalogAdmission by catalog {
                override suspend fun <T> withAdmittedOperation(
                    block: suspend (DownloadOperationExclusion.Operation) -> T,
                ): T = catalog.withAdmittedOperation(block).also {
                    fillCompletedWhileClosed.complete(parentClosed.isCompleted && !releaseRead.isCompleted)
                }
            }
            val startupEntered = CompletableDeferred<Unit>()
            val startupReturned = CompletableDeferred<Unit>()
            val transport = ArtifactTestTransport(fixture.operations)
            val observedTransport = object : BackgroundTransport by transport {
                override suspend fun ensureReady() {
                    startupEntered.complete(Unit)
                    transport.ensureReady()
                    startupReturned.complete(Unit)
                }
            }
            val engine = fixture.engine(
                CoroutineScope(coroutineContext + hostJob), observedTransport, catalog = observedCatalog,
            )
            startupEntered.await()
            val reading = CompletableDeferred<Unit>()
            val actions = DownloadsActionRepositoryImpl(
                operations = fixture.operations,
                catalog = TestDownloadCatalogAdmission(fixture.operations),
                legacy = object : DownloadRepository by engine {
                    override suspend fun cancelARunningChapter(chapterId: Long, mangaId: Long) {
                        parentClosed.complete(Unit) // The real removeChapter stop callback owns the parent close.
                        engine.cancelARunningChapter(chapterId, mangaId)
                    }
                },
                storage = DownloadsActionStorage(
                    fixture.dao, fixture.db.chapterDao(), fixture.appFileSystem,
                    fixture.artifacts.ownership, fixture.db.chapterArtifactRepairDao(),
                ),
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
                    parentClosed.await()
                    assertTrue(fillCompletedWhileClosed.await(), "The admitted fill must finish before reopening")
                    assertFalse(startupReturned.isCompleted, "Blocked startup must not satisfy the fill witness")
                    assertFalse(deletion.isCompleted)
                    assertEquals(DownloadingState.QUEUED, fixture.download(waiting).state)
                    assertTrue(transport.enqueued.isEmpty())
                    releaseRead.complete(Unit)
                    pin.join()
                    assertTrue(deletion.await().isSuccess)
                    val transfer = transport.requests.receive() // No enqueue/pump/lifecycle call wakes it.
                    assertFalse(startupReturned.isCompleted, "Parent reopening, not startup, must resume the transfer")
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
