package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Sink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Real iOS repository + file-backed Room; restart reconstruction, not an OS-kill/fsync claim. */
class IosManifestPersistenceTest {
    @Test
    fun initialManifestOpenWriteCloseAndRenameFailuresNeverEnqueueTransfers() = runTest {
        for (cut in ManifestWriteCut.entries) {
            val fixture = IosCbzFinalizationFixture()
            val host = SupervisorJob(coroutineContext[Job])
            try {
                val chapter = fixture.seed()
                fixture.dao.deleteByChapterId(chapter.saved.id)
                chapter.pages.keys.forEach { fixture.system.delete(it) }
                val directory = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
                val fault = ManifestWriteFault(fixture.system, directory, cut)
                val transport = ArtifactTestTransport(fixture.operations, ready = true)
                var resolutions = 0
                val provider = object : ChapterPageProvider {
                    override suspend fun pagesOrNull(api: String, mangaUrl: String, mangaLanguage: String, chapterUrl: String): List<DownloadPage> {
                        resolutions++
                        return listOf(DownloadPage("https://example.test/initial.png", emptyMap()))
                    }
                }
                val engine = fixture.engine(CoroutineScope(coroutineContext + host), transport,
                    files = fixture.manifestFiles(fault), pageProvider = provider)
                engine.enqueueChapterDownload(fixture.saved(chapter), "CBZ", "test")
                fixture.dao.observeAllDownloads().first { rows ->
                    rows.any { it.chapterId == chapter.saved.id && it.state == DownloadingState.FAILED }
                }
                host.cancelAndJoin() // Inspect only after the real resolver/file scopes have drained.
                assertEquals(1, resolutions)
                assertEquals(1, fault.hits, "The intended $cut boundary must actually execute")
                assertEquals("Download manifest could not be saved", fixture.download(chapter).errorMsg)
                assertTrue(transport.enqueued.isEmpty(), "$cut must not reach transfer enqueue")
                assertFalse(fixture.system.exists(directory / "manifest.json"))
                assertTrue(fixture.system.list(directory).none { it.name.startsWith(".manifest-") })
                fixture.reopen()
                assertEquals(DownloadingState.FAILED, fixture.download(chapter).state)
                assertFalse(fixture.system.exists(directory / "manifest.json"))
            } finally {
                host.cancelAndJoin()
                fixture.close()
            }
        }
    }

    @Test
    fun retryIncrementFailurePreservesLastManifestAndNeverEnqueuesAcrossReopen() = runTest {
        for (cut in ManifestWriteCut.entries) {
            val fixture = IosCbzFinalizationFixture()
            val host = SupervisorJob(coroutineContext[Job])
            val reopenedHost = SupervisorJob(coroutineContext[Job])
            try {
                val chapter = fixture.seed()
                val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 1)
                chapter.pages.keys.forEach { fixture.system.delete(it) }
                val directory = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
                val path = directory / "manifest.json"
                val before = fixture.system.read(path) { readByteArray() }
                val fault = ManifestWriteFault(fixture.system, directory, cut)
                val transport = ArtifactTestTransport(fixture.operations)
                fixture.engine(CoroutineScope(coroutineContext + host), transport, files = fixture.manifestFiles(fault))
                transport.failPage(chapter, claim.token).await()
                host.cancelAndJoin()
                assertEquals(1, fault.hits)
                assertTrue(transport.enqueued.isEmpty())
                assertEquals(DownloadingState.FAILED, fixture.download(chapter).state)
                assertEquals(1, fixture.manifest(chapter).pages.single().attempts, "No uncommitted count")
                assertContentEquals(before, fixture.system.read(path) { readByteArray() })
                assertTrue(fixture.system.list(directory).none { it.name.startsWith(".manifest-") })

                fixture.reopen()
                val restarted = ArtifactTestTransport(fixture.operations, ready = true)
                val engine = fixture.engine(CoroutineScope(coroutineContext + reopenedHost), restarted)
                engine.reconcileInterruptedDownloads()
                assertEquals(DownloadingState.FAILED, fixture.download(chapter).state)
                assertEquals(1, fixture.manifest(chapter).pages.single().attempts)
                assertContentEquals(before, fixture.system.read(path) { readByteArray() })
                assertTrue(restarted.enqueued.isEmpty(), "Failed persistence must not become an automatic retry")
            } finally {
                host.cancelAndJoin()
                reopenedHost.cancelAndJoin()
                fixture.close()
            }
        }
    }

    @Test
    fun committedRetryCountSurvivesReopenWithoutAuthorizingAutomaticReplacement() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val host = SupervisorJob(coroutineContext[Job])
        val reopenedHost = SupervisorJob(coroutineContext[Job])
        val handoff = HeldSettlementWrite()
        try {
            val chapter = fixture.seed()
            val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 1)
            chapter.pages.keys.forEach { fixture.system.delete(it) }
            val transport = ArtifactTestTransport(fixture.operations)
            fixture.engine(CoroutineScope(coroutineContext + host), transport.holdNextEnqueue(handoff))
            transport.failPage(chapter, claim.token).await()
            handoff.entered.await()
            assertEquals(2, fixture.manifest(chapter).pages.single().attempts)
            assertTrue(transport.enqueued.isEmpty())
            val path = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id) / "manifest.json"
            val committed = fixture.system.read(path) { readByteArray() }
            val ledgerId = fixture.download(chapter).id
            host.cancelAndJoin() // Cancellable held handoff unwinds without reaching the transport.
            fixture.reopen()
            assertContentEquals(committed, fixture.system.read(path) { readByteArray() })

            val restarted = ArtifactTestTransport(fixture.operations, ready = true)
            val engine = fixture.engine(CoroutineScope(coroutineContext + reopenedHost), restarted)
            engine.reconcileInterruptedDownloads()
            assertEquals(DownloadingState.FAILED, fixture.download(chapter).state)
            assertEquals(ledgerId, fixture.download(chapter).id)
            assertEquals(2, fixture.manifest(chapter).pages.single().attempts)
            assertContentEquals(committed, fixture.system.read(path) { readByteArray() })
            assertTrue(restarted.enqueued.isEmpty(), "A persisted token/count is not a live native transfer")
            // Join the engine's actual retained settlement before testing explicit user admission.
            reopenedHost.cancelAndJoin()
            fixture.reopen()
            val retryHost = SupervisorJob(coroutineContext[Job])
            try {
                val retryEngine = fixture.engine(CoroutineScope(coroutineContext + retryHost), restarted)
                assertTrue(retryEngine.retryChapterDownload(fixture.download(chapter)))
                val request = restarted.requests.receive()
                assertNotEquals(claim.token, request.attemptToken)
                assertNotEquals(ledgerId, fixture.download(chapter).id)
                assertEquals(0, fixture.manifest(chapter).pages.single().attempts, "Only explicit Retry resets the durable budget")
            } finally {
                retryHost.cancelAndJoin()
            }
        } finally {
            host.cancelAndJoin()
            reopenedHost.cancelAndJoin()
            handoff.release.complete(Unit)
            fixture.close()
        }
    }
}

private enum class ManifestWriteCut { OPEN, WRITE, CLOSE, RENAME }

/** Intercepts only the real store's same-directory manifest stage, forwarding all other operations. */
private class ManifestWriteFault(delegate: FileSystem, private val directory: Path, private val cut: ManifestWriteCut) :
    ForwardingFileSystem(delegate) {
    var hits = 0
        private set

    private fun fail() {
        hits++
        throw IOException("Injected manifest $cut failure")
    }

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        if (file.parent != directory || !file.name.startsWith(".manifest-")) return super.sink(file, mustCreate)
        if (cut == ManifestWriteCut.OPEN) fail()
        val delegate = super.sink(file, mustCreate)
        return object : Sink by delegate {
            override fun write(source: Buffer, byteCount: Long) {
                if (cut == ManifestWriteCut.WRITE) {
                    if (byteCount > 0) delegate.write(source, minOf(byteCount, 1L))
                    fail()
                }
                delegate.write(source, byteCount)
            }
            override fun close() {
                delegate.close()
                if (cut == ManifestWriteCut.CLOSE) fail()
            }
        }
    }

    override fun atomicMove(source: Path, target: Path) {
        if (cut == ManifestWriteCut.RENAME && target == directory / "manifest.json") fail()
        super.atomicMove(source, target)
    }
}

private fun IosCbzFinalizationFixture.manifestFiles(fault: FileSystem): AppFileSystem =
    object : AppFileSystem by appFileSystem {
        override fun fileSystem(): FileSystem = fault
    }

private suspend fun ArtifactTestTransport.failPage(chapter: IosCbzChapter, token: String): CompletableDeferred<Unit> =
    CompletableDeferred<Unit>().also { acknowledgement ->
        operations.withOperation { operation ->
            receiver.onPageFailed(chapter.saved.mangaId, chapter.saved.id, 0, token, "HTTP 500", operation) {
                acknowledgement.complete(Unit)
            }
        }
    }
