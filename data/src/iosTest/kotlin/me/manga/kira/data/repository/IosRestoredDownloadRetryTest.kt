package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.dao.ChapterArtifactDao
import me.manga.kira.platform.download.BackgroundTransport
import me.manga.kira.platform.download.TransferListener
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.IosPageMediaInspector
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Only actual public enqueue/Retry or an observed exact-token native page authorizes replacement. */
class IosRestoredDownloadRetryTest {
    @Test
    fun repairedAttemptSettlesForSameEngineRetryAndLateCallbacksCannotMutateEitherGeneration() = runTest {
        for (retainedManifest in listOf(false, true)) {
            val fixture = IosCbzFinalizationFixture()
            val host = SupervisorJob(coroutineContext[Job])
            try {
                val chapter = fixture.downloadNamed(fixture.seed())
                val prior = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 2, pageCount = 2)
                val directory = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
                fixture.system.delete(directory / "image_1.png")
                if (!retainedManifest) {
                    fixture.system.delete(directory / "image_0.png")
                    fixture.system.delete(directory / "manifest.json")
                }
                fixture.reopen()
                val released = CompletableDeferred<Unit>()
                val records = fixture.db.chapterArtifactDao()
                val observed = object : ChapterArtifactDao by records {
                    override suspend fun release(chapterId: Long, token: String): Int = records.release(chapterId, token).also {
                        if (chapterId == chapter.saved.id && token == prior.token && it == 1) released.complete(Unit)
                    }
                }
                val runtime = ArtifactTestRuntime(observed, fixture.db.chapterArtifactCommitDao(), fixture.appFileSystem,
                    IosPageMediaInspector(system = fixture.system))
                var resolutions = 0
                val provider = object : ChapterPageProvider {
                    override suspend fun pagesOrNull(api: String, mangaUrl: String, mangaLanguage: String, chapterUrl: String): List<DownloadPage> {
                        resolutions++
                        return List(2) { DownloadPage("https://example.test/retry-$it.png", emptyMap()) }
                    }
                }
                val transport = ArtifactTestTransport(fixture.operations, ready = true)
                val engine = fixture.engine(CoroutineScope(coroutineContext + host), transport,
                    downloadArtifacts = runtime.downloads, pageProvider = provider)
                engine.reconcileInterruptedDownloads()
                released.await() // Observe the engine's real settlement; never settle it for the test.
                val failed = fixture.download(chapter)
                assertEquals(prior.downloadId, failed.id)
                assertEquals(DownloadingState.FAILED, failed.state)
                assertEquals(0L, failed.sizeBytes)
                assertEquals(0, resolutions)
                assertTrue(transport.enqueued.isEmpty())
                assertNull(runtime.ownership.currentClaim(chapter.saved.id))
                assertStaleReceipts(fixture, chapter, prior.token, engine, "failed")
                assertEquals(failed, fixture.download(chapter))
                if (retainedManifest) assertEquals(listOf(2, 2), fixture.manifest(chapter).pages.map { it.attempts })
                else assertFalse(fixture.system.exists(directory / "manifest.json"))

                assertTrue(engine.retryChapterDownload(failed))
                val requests = List(if (retainedManifest) 1 else 2) { transport.requests.receive() }
                val replacement = fixture.download(chapter)
                assertNotEquals(prior.token, requests.first().attemptToken)
                assertNotEquals(failed.id, replacement.id)
                assertEquals(DownloadingState.RUNNING, replacement.state)
                assertEquals(if (retainedManifest) setOf(1) else setOf(0, 1), requests.map { it.pageIndex }.toSet())
                assertEquals(if (retainedManifest) 0 else 1, resolutions)
                val manifest = fixture.manifest(chapter)
                assertEquals(requests.first().attemptToken, manifest.attemptToken)
                assertEquals(listOf(0, 0), manifest.pages.map { it.attempts })
                val record = fixture.db.chapterArtifactDao().get(chapter.saved.id)
                assertStaleReceipts(fixture, chapter, prior.token, engine, "replacement")
                assertEquals(replacement, fixture.download(chapter))
                assertEquals(record, fixture.db.chapterArtifactDao().get(chapter.saved.id))
                assertEquals(manifest, fixture.manifest(chapter))
                assertEquals(requests, transport.enqueued)
                assertFalse(engine.retryChapterDownload(failed), "The old FAILED ledger is not a second explicit admission")
                if (retainedManifest) {
                    assertContentEquals(chapter.pages.values.first(), fixture.system.read(directory / "image_0.png") { readByteArray() })
                }
            } finally {
                host.cancelAndJoin()
                fixture.close()
            }
        }
    }

    @Test
    fun freshExplicitEnqueueCanResolveAndTransferIntoAnEmptyChapterRoot() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val host = SupervisorJob(coroutineContext[Job])
        try {
            val chapter = fixture.seed()
            fixture.dao.deleteByChapterId(chapter.saved.id)
            fixture.system.deleteRecursively(fixture.appFileSystem.filesDir / "manga")
            var resolutions = 0
            val provider = object : ChapterPageProvider {
                override suspend fun pagesOrNull(api: String, mangaUrl: String, mangaLanguage: String, chapterUrl: String): List<DownloadPage> {
                    resolutions++
                    return List(2) { DownloadPage("https://example.test/fresh-$it.png", emptyMap()) }
                }
            }
            val transport = ArtifactTestTransport(fixture.operations, ready = true)
            val engine = fixture.engine(CoroutineScope(coroutineContext + host), transport, pageProvider = provider)
            engine.enqueueChapterDownload(fixture.saved(chapter), "CBZ", "test")
            val requests = List(2) { transport.requests.receive() }
            assertEquals(1, resolutions)
            assertEquals(DownloadingState.RUNNING, fixture.download(chapter).state)
            assertEquals(setOf(0, 1), requests.map { it.pageIndex }.toSet())
            assertEquals(1, requests.map { it.attemptToken }.distinct().size)
            assertEquals(requests.first().attemptToken, fixture.manifest(chapter).attemptToken)
            engine.reconcileInterruptedDownloads()
            assertEquals(requests, transport.enqueued, "An ordinary pump must not lose the explicit token or duplicate its native requests")
        } finally {
            host.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun recoveredNativeOwnershipProtectsOnlyItsPageAndNeverStartsAnUnrelatedMissingPage() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val host = SupervisorJob(coroutineContext[Job])
        try {
            val chapter = fixture.downloadNamed(fixture.seed())
            val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 1, pageCount = 2)
            chapter.pages.keys.forEach { fixture.system.delete(it) }
            val expected = fixture.download(chapter)
            val manifest = fixture.manifest(chapter)
            fixture.reopen()
            val transport = ArtifactTestTransport(fixture.operations, ready = true)
            val nativePages = mutableSetOf(0)
            val recovered = object : BackgroundTransport by transport {
                override suspend fun inFlightPages(chapterId: Long, attemptToken: String): Set<Int> =
                    if (chapterId == chapter.saved.id && attemptToken == claim.token) nativePages.toSet() else emptySet()
            }
            val engine = fixture.engine(CoroutineScope(coroutineContext + host), recovered)
            engine.reconcileInterruptedDownloads()
            assertEquals(expected, fixture.download(chapter), "A positively live page still owns its result")
            assertEquals(manifest, fixture.manifest(chapter))
            assertTrue(transport.enqueued.isEmpty(), "Page 0 cannot authorize the missing page 1")

            val received = ReceiverPage(fixture, chapter, "recovered-zero", failPublication = false)
            val acknowledged = CompletableDeferred<Unit>()
            var receipts = 0
            fixture.operations.withOperation { operation ->
                engine.onPageComplete(chapter.saved.mangaId, chapter.saved.id, 0, claim.token, received.page, operation) {
                    nativePages.clear()
                    receipts++
                    acknowledged.complete(Unit)
                }
            }
            acknowledged.await()
            fixture.dao.observeAllDownloads().first { rows -> rows.any { it.chapterId == chapter.saved.id && it.state == DownloadingState.FAILED } }
            host.cancelAndJoin()
            assertEquals(1, receipts)
            assertEquals(1, received.publications)
            assertFalse(received.discarded.isCompleted, "Successful publication transfers the stage; receiver disposal must not delete it again")
            assertFalse(fixture.system.exists(received.path))
            assertTrue(transport.enqueued.isEmpty())
            assertEquals(manifest, fixture.manifest(chapter), "No new retry budget was manufactured for the gap")
            assertEquals(expected.copy(state = DownloadingState.FAILED, progress = 0, sizeBytes = 0, errorMsg = null), fixture.download(chapter))
            val directory = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
            assertContentEquals(chapter.pages.values.last(), fixture.system.read(directory / "image_0.png") { readByteArray() })
            assertFalse(fixture.system.exists(directory / "image_1.png"))
        } finally {
            host.cancelAndJoin()
            fixture.close()
        }
    }
}

private suspend fun assertStaleReceipts(
    fixture: IosCbzFinalizationFixture,
    chapter: IosCbzChapter,
    token: String,
    receiver: TransferListener,
    name: String,
) {
    var disposals = 0
    val page = ReceiverPage(fixture, chapter, "stale-$name", failPublication = false, beforeDiscard = { disposals++ })
    val completed = CompletableDeferred<Unit>()
    val failed = CompletableDeferred<Unit>()
    var completionReceipts = 0
    var failureReceipts = 0
    fixture.operations.withOperation { operation ->
        receiver.onPageComplete(chapter.saved.mangaId, chapter.saved.id, 0, token, page.page, operation) {
            completionReceipts++
            completed.complete(Unit)
        }
    }
    fixture.operations.withOperation { operation ->
        receiver.onPageFailed(chapter.saved.mangaId, chapter.saved.id, 1, token, "late native failure", operation) {
            failureReceipts++
            failed.complete(Unit)
        }
    }
    completed.await()
    failed.await()
    assertEquals(1, completionReceipts)
    assertEquals(1, failureReceipts)
    assertEquals(1, disposals)
    assertEquals(0, page.publications)
    assertFalse(fixture.system.exists(page.path))
}
