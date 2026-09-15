package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifestStore
import me.manga.kira.presentation.features.download.domain.clean.DownloadPage
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Existing real iOS repository/Room fixture. No modeled state machine or OS-termination claim. */
class IosManifestRecoverySafetyTest {
    @Test
    fun secondaryCallbackPersistenceFailureStillAcknowledgesWithoutEscaping() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val host = SupervisorJob(coroutineContext[Job])
        try {
            val chapter = fixture.seed()
            val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 2)
            val expected = fixture.download(chapter)
            val path = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id) / "manifest.json"
            val before = fixture.system.read(path) { readByteArray() }
            var failedReads = 0
            val downloads = object : ChapterDownloadDao by fixture.dao {
                override suspend fun getDownloadByChapter(chapterId: Long): ChapterDownloadEntity? {
                    failedReads++
                    throw IOException("callback ledger read unavailable")
                }
            }
            // The existing fixture keeps startup blocked, isolating the actual delegate callback.
            val transport = ArtifactTestTransport()
            val engine = fixture.engine(CoroutineScope(coroutineContext + host), transport, downloads = downloads)
            val acknowledged = CompletableDeferred<Unit>()
            var receipts = 0
            engine.onPageFailed(chapter.saved.mangaId, chapter.saved.id, 0, claim.token, "transfer failed") {
                receipts++
                acknowledged.complete(Unit)
            }
            acknowledged.await()
            host.cancelAndJoin() // runTest also rejects any unhandled exception from the callback job.
            assertEquals(2, failedReads, "One attempt and one bounded recovery; no recursive retry")
            assertEquals(1, receipts)
            assertEquals(expected, fixture.download(chapter))
            assertContentEquals(before, fixture.system.read(path) { readByteArray() })
            assertTrue(transport.enqueued.isEmpty())
        } finally {
            host.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun unreadableOrForeignManifestFailsClosedWithoutResolveAndReadableExplicitRetryStillWorks() = runTest {
        for (failure in listOf("corrupt", "read", "api", "token")) {
            val fixture = IosCbzFinalizationFixture()
            val host = SupervisorJob(coroutineContext[Job])
            val retryHost = SupervisorJob(coroutineContext[Job])
            try {
                val chapter = fixture.seed()
                val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 2)
                chapter.pages.keys.forEach { fixture.system.delete(it) }
                val path = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id) / "manifest.json"
                when (failure) {
                    "corrupt" -> fixture.system.write(path) { writeUtf8("{torn manifest") }
                    "api" -> DownloadManifestStore(fixture.appFileSystem).write(fixture.manifest(chapter).copy(api = "other"))
                    "token" -> DownloadManifestStore(fixture.appFileSystem).write(fixture.manifest(chapter).copy(attemptToken = "foreign"))
                }
                val before = fixture.system.read(path) { readByteArray() }
                val guarded = object : ForwardingFileSystem(fixture.system) {
                    override fun source(file: Path): Source {
                        if (failure == "read" && file == path) throw IOException("manifest read unavailable")
                        return super.source(file)
                    }
                }
                val files = object : AppFileSystem by fixture.appFileSystem {
                    override fun fileSystem() = guarded
                }
                var resolutions = 0
                val provider = object : ChapterPageProvider {
                    override suspend fun pagesOrNull(api: String, mangaUrl: String, mangaLanguage: String, chapterUrl: String): List<DownloadPage> {
                        resolutions++
                        error("Retained bytes cannot authorize a new scrape")
                    }
                }
                val transport = ArtifactTestTransport(ready = true)
                fixture.engine(CoroutineScope(coroutineContext + host), transport, files = files, pageProvider = provider)
                fixture.dao.observeAllDownloads().first { rows ->
                    rows.any { it.chapterId == chapter.saved.id && it.state == DownloadingState.FAILED }
                }
                host.cancelAndJoin()
                assertEquals(0, resolutions)
                assertTrue(transport.enqueued.isEmpty())
                assertEquals("Download manifest could not be read", fixture.download(chapter).errorMsg)
                assertContentEquals(before, fixture.system.read(path) { readByteArray() })
                fixture.reopen()
                assertEquals(DownloadingState.FAILED, fixture.download(chapter).state)
                assertContentEquals(before, fixture.system.read(path) { readByteArray() })

                if (failure == "read") {
                    // Storage is healthy now. Only explicit user Retry may grant a fresh ordinary budget.
                    assertEquals(2, fixture.manifest(chapter).pages.single().attempts)
                    val retryTransport = ArtifactTestTransport(ready = true)
                    val engine = fixture.engine(CoroutineScope(coroutineContext + retryHost), retryTransport, pageProvider = provider)
                    assertTrue(engine.retryChapterDownload(fixture.download(chapter)))
                    val request = retryTransport.requests.receive()
                    assertNotEquals(claim.token, request.attemptToken)
                    assertEquals(0, fixture.manifest(chapter).pages.single().attempts)
                    assertEquals(0, resolutions)
                }
            } finally {
                host.cancelAndJoin()
                retryHost.cancelAndJoin()
                fixture.close()
            }
        }
    }

    @Test
    fun startupReclaimsOnlyOwnedManifestStagesAndResumesTheUnchangedDurableBudget() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val host = SupervisorJob(coroutineContext[Job])
        try {
            val chapter = fixture.seed()
            val claim = fixture.prepareAttempt(chapter, DownloadingState.RUNNING, failures = 2)
            chapter.pages.keys.forEach { fixture.system.delete(it) }
            val directory = fixture.appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
            val manifest = directory / "manifest.json"
            val before = fixture.system.read(manifest) { readByteArray() }
            val orphan = directory / ".manifest-123abc.tmp"
            val unrelated = directory / ".manifest-nothex.tmp"
            val stageDirectory = directory / ".manifest-f.tmp"
            val otherChapter = directory.parent!! / "chapter_9999" / ".manifest-abc.tmp"
            fixture.system.createDirectories(stageDirectory)
            fixture.system.createDirectories(otherChapter.parent!!)
            listOf(orphan, unrelated, stageDirectory / "keep", otherChapter).forEach { file ->
                fixture.system.write(file) { writeUtf8("retained fixture bytes") }
            }
            val transport = ArtifactTestTransport(ready = true)
            fixture.engine(CoroutineScope(coroutineContext + host), transport)
            val request = transport.requests.receive()
            assertEquals(claim.token, request.attemptToken)
            assertEquals(2, fixture.manifest(chapter).pages.single().attempts)
            assertContentEquals(before, fixture.system.read(manifest) { readByteArray() })
            assertFalse(fixture.system.exists(orphan))
            listOf(unrelated, stageDirectory / "keep", otherChapter).forEach { assertTrue(fixture.system.exists(it)) }
        } finally {
            host.cancelAndJoin()
            fixture.close()
        }
    }
}
