package me.manga.kira.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifest
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifestStore
import me.manga.kira.presentation.features.download.domain.clean.ManifestPage
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual iOS Delete entry point over the existing Room/files/transport fixture; no OS claim. */
class IosBackgroundFailedCleanupTest {
    @Test
    fun legacyFailedDeleteRemovesPagesAndManifestButPreservesPriorReadableCbz() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        try {
            val chapter = fixture.seed()
            fixture.dao.updateStateChId(chapter.saved.id, DownloadingState.FAILED)
            val page = fixture.partial(chapter)
            val archive = fixture.archive(chapter)
            fixture.system.write(archive) { writeUtf8("prior CBZ") }
            val readable = chapter.saved.copy(isDownloaded = true, localImagePaths = listOf(archive.toString()))
            fixture.db.backupDao().updateChapterRow(readable)
            assertNull(fixture.artifacts.ownership.currentClaim(chapter.saved.id))
            val transport = ArtifactTestTransport(fixture.operations)
            val engine = fixture.engine(CoroutineScope(coroutineContext + hostJob), transport)

            engine.deleteDownload(chapter.saved.id)

            assertNull(fixture.dao.getDownloadByChapter(chapter.saved.id))
            assertFalse(fixture.system.exists(page))
            assertNull(DownloadManifestStore(fixture.appFileSystem).read(chapter.saved.mangaId, chapter.saved.id))
            assertEquals("prior CBZ", fixture.system.read(archive) { readUtf8() })
            assertEquals(readable, fixture.saved(chapter))
            assertEquals(listOf(chapter.saved.id), transport.cancelled.map { it.first })
            assertTrue(transport.enqueued.isEmpty())
        } finally {
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun settledFailureKeepsRetryPagesUntilDeleteWhileSuccessHistoryRemainsReadable() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        try {
            val failed = fixture.seed()
            val success = fixture.seed()
            fixture.dao.updateStateChId(success.saved.id, DownloadingState.SUCCESS)
            val readable = success.saved.copy(isDownloaded = true)
            fixture.db.backupDao().updateChapterRow(readable)
            val page = fixture.partial(failed)
            val claim = fixture.prepareAttempt(failed, DownloadingState.RUNNING)
            assertTrue(fixture.artifacts.fail(claim, "bounded page failure"))
            assertTrue(fixture.artifacts.settle(claim))
            assertNull(fixture.artifacts.ownership.currentClaim(failed.saved.id))
            assertTrue(fixture.system.exists(page), "Ordinary failure must leave the page resumable")
            assertNotNull(DownloadManifestStore(fixture.appFileSystem).read(failed.saved.mangaId, failed.saved.id))
            val transport = ArtifactTestTransport(fixture.operations)
            val engine = fixture.engine(CoroutineScope(coroutineContext + hostJob), transport)

            engine.deleteDownload(failed.saved.id)
            engine.deleteDownload(success.saved.id)

            assertFalse(fixture.system.exists(page))
            assertNull(fixture.dao.getDownloadByChapter(failed.saved.id))
            assertNull(fixture.dao.getDownloadByChapter(success.saved.id))
            assertEquals(readable, fixture.saved(success))
            success.pages.forEach { (path, bytes) ->
                assertEquals(bytes.toList(), fixture.system.read(path) { readByteArray() }.toList())
            }
            assertEquals(listOf(failed.saved.id), transport.cancelled.map { it.first }, "SUCCESS must not stop transport")
            assertTrue(transport.enqueued.isEmpty())
        } finally {
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }

    @Test
    fun failedIosCleanupThrowsAndRetainsItsRowAndIntentForReopen() = runTest {
        val fixture = IosCbzFinalizationFixture()
        val hostJob = SupervisorJob(coroutineContext[Job])
        try {
            val chapter = fixture.seed()
            fixture.dao.updateStateChId(chapter.saved.id, DownloadingState.FAILED)
            val page = fixture.partial(chapter)
            val captured = fixture.download(chapter)
            val failing = object : ForwardingFileSystem(fixture.system) {
                override fun delete(path: Path, mustExist: Boolean) {
                    if (path == page) throw IOException("injected private filesystem detail")
                    super.delete(path, mustExist)
                }
            }
            val files = object : AppFileSystem by fixture.appFileSystem { override fun fileSystem() = failing }
            val runtime = ArtifactTestRuntime(fixture.db, files)
            val transport = ArtifactTestTransport(fixture.operations)
            val engine = fixture.engine(
                CoroutineScope(coroutineContext + hostJob), transport, files = files, downloadArtifacts = runtime.downloads,
            )

            val failure = assertFailsWith<IllegalStateException> { engine.deleteDownload(chapter.saved.id) }

            assertEquals("Download cleanup could not be settled", failure.message)
            assertEquals(captured, fixture.download(chapter))
            val retained = assertNotNull(runtime.dao.get(chapter.saved.id))
            assertEquals(ChapterArtifactOperation.FAILED_CLEANUP, retained.operation)
            assertTrue(retained.retiring)
            assertNotNull(retained.token)
            assertTrue(fixture.system.exists(page))
            hostJob.cancelAndJoin()
            fixture.reopen()
            fixture.artifacts.ownership.read(chapter.saved.id) { assertNull(it?.token) }
            assertFalse(fixture.system.exists(page))
            assertNull(fixture.dao.getDownloadByChapter(chapter.saved.id))
        } finally {
            hostJob.cancelAndJoin()
            fixture.close()
        }
    }

    private fun IosCbzFinalizationFixture.partial(chapter: IosCbzChapter): Path {
        val page = appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id) / "image_0.png"
        system.write(page) { write(chapter.pages.values.last()) }
        DownloadManifestStore(appFileSystem).write(DownloadManifest(
            chapter.saved.mangaId, chapter.saved.id, "test",
            listOf(ManifestPage(0, "https://example.test/page.png", emptyMap(), attempts = 3)),
        ))
        return page
    }
}
