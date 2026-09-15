package me.manga.kira.data.repository

import androidx.room.useWriterConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.manga.kira.data.backup.RestoredChapterArchive
import me.manga.kira.data.backup.RestoredDownloadPublisher
import me.manga.kira.data.download.artifacts.ChapterArtifactReference
import me.manga.kira.data.local.dao.ChapterRestoreOutcome
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
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

/** Real Room transactions and owned files; no new queue/transport simulation or lifecycle harness. */
class FailedAttemptCleanupTest {
    @Test
    fun legacyFailedAttemptCleansOnlyItsPartialsAndPreservesReadableCbz() = downloadRecoveryTest {
        val original = seed(DownloadingState.FAILED)
        val partials = partials(original)
        val archive = appFileSystem.chapterDir(original.saved.mangaId, original.saved.id) / "chapter_${original.saved.id}.cbz"
        fs.write(archive) { writeUtf8("prior readable archive") }
        val readable = original.saved.copy(isDownloaded = true, localImagePaths = listOf(archive.toString()))
        db.backupDao().updateChapterRow(readable)
        assertNull(artifactRuntime.dao.get(original.saved.id))
        var stops = 0
        assertTrue(artifactRuntime.downloads.deleteAttempt(original.download) { claim ->
            stops++
            assertEquals(ChapterArtifactOperation.FAILED_CLEANUP, claim.operation)
            assertTrue(assertNotNull(artifactRuntime.dao.get(original.saved.id)).retiring)
            assertFalse(artifactRuntime.dao.canPublish(claim), "Cleanup cannot become a producer")
        })
        assertEquals(1, stops)
        partials.forEach { assertFalse(fs.exists(it)) }
        assertEquals("prior readable archive", fs.read(archive) { readUtf8() })
        assertEquals(readable, saved(original))
        assertNull(dao.getDownloadByChapter(original.saved.id))
        assertNull(artifactRuntime.dao.get(original.saved.id)?.token)
    }

    @Test
    fun newlySettledTokenlessFailureRetainsRetryPagesUntilDeleteAndKeepsRestore() = downloadRecoveryTest {
        val original = seed(DownloadingState.FAILED)
        val runtime = artifactRuntime
        val source = appFileSystem.cacheDir / "validated.cbz"
        fs.createDirectories(appFileSystem.cacheDir)
        fs.write(source) { writeUtf8("validated-backup-archive") }
        val publisher = RestoredDownloadPublisher(runtime.ownership, runtime.dao, runtime.commits, appFileSystem, runtime.recovery)
        assertEquals(ChapterRestoreOutcome.COMMITTED, publisher.publish(
            RestoredChapterArchive(source, assertNotNull(fs.metadata(source).size)), original.saved, "test", "Manga",
        ))
        val readable = saved(original)
        val relative = assertNotNull(runtime.dao.get(original.saved.id)?.committedRelativePath)
        val restored = ChapterArtifactReference.resolve(appFileSystem, ChapterArtifactOwner.of(readable), relative)
        val claim = assertNotNull(runtime.downloads.enqueue(readable, original.download.copy(state = DownloadingState.QUEUED)))
        val partials = partials(original)
        assertTrue(runtime.downloads.fail(claim, "retryable failure"))
        assertTrue(runtime.downloads.settle(claim))
        assertNull(runtime.dao.get(original.saved.id)?.token)
        partials.forEach { assertTrue(fs.exists(it), "Failure itself must retain resumable input") }
        val failed = download(original)
        reopen()
        assertTrue(artifactRuntime.downloads.deleteAttempt(failed))
        partials.forEach { assertFalse(fs.exists(it)) }
        assertEquals(readable, saved(original))
        assertEquals("validated-backup-archive", fs.read(restored) { readUtf8() })
        assertEquals(relative, artifactRuntime.dao.get(original.saved.id)?.committedRelativePath)
        assertNull(dao.getDownloadByChapter(original.saved.id))
    }

    @Test
    fun cleanupFailureKeepsRowAndCustodyWhileOtherChaptersFinishAndRestartRetries() = downloadRecoveryTest {
        val blocked = seed(DownloadingState.FAILED)
        val other = seed(DownloadingState.FAILED)
        val blockedFiles = partials(blocked)
        val otherFiles = partials(other)
        val failing = object : ForwardingFileSystem(fs) {
            override fun delete(path: Path, mustExist: Boolean) {
                if (path == blockedFiles.first()) throw IOException("injected cleanup failure")
                super.delete(path, mustExist)
            }
        }
        val appFs = object : AppFileSystem by appFileSystem { override fun fileSystem() = failing }
        val runtime = ArtifactTestRuntime(db, appFs)
        assertFalse(runtime.downloads.deleteAttempt(blocked.download))
        val retained = assertNotNull(runtime.dao.get(blocked.saved.id))
        assertEquals(ChapterArtifactOperation.FAILED_CLEANUP, retained.operation)
        assertTrue(retained.retiring)
        assertNotNull(retained.token)
        assertEquals(blocked.download, download(blocked))
        assertNull(runtime.downloads.enqueue(blocked.saved, blocked.download.copy(state = DownloadingState.QUEUED)))
        assertTrue(runtime.downloads.deleteAttempt(other.download))
        otherFiles.forEach { assertFalse(fs.exists(it)) }
        reopen()
        artifactRuntime.ownership.read(blocked.saved.id) { assertNull(it?.token) }
        blockedFiles.forEach { assertFalse(fs.exists(it)) }
        assertNull(dao.getDownloadByChapter(blocked.saved.id))
    }

    @Test
    fun terminalRowRemovalRollsBackIfTokenReleaseFails() = downloadRecoveryTest {
        val original = seed(DownloadingState.FAILED)
        val files = partials(original)
        db.useWriterConnection { connection ->
            connection.usePrepared(
                "CREATE TRIGGER reject_cleanup_release BEFORE UPDATE OF token ON chapter_artifacts " +
                    "WHEN OLD.operation = 'failed_cleanup' AND NEW.token IS NULL " +
                    "BEGIN SELECT RAISE(ABORT, 'reject cleanup release'); END",
            ) { it.step() }
        }
        assertFalse(artifactRuntime.downloads.deleteAttempt(original.download))
        files.forEach { assertFalse(fs.exists(it), "File deletion finished before the failing release") }
        assertEquals(original.download, download(original), "Row removal and release must roll back together")
        assertNotNull(artifactRuntime.dao.get(original.saved.id)?.token)
        reopen()
        artifactRuntime.ownership.read(original.saved.id) { assertNotNull(it?.token) }
        assertEquals(original.download, download(original))
        db.useWriterConnection { connection ->
            connection.usePrepared("DROP TRIGGER reject_cleanup_release") { it.step() }
        }
        assertTrue(artifactRuntime.downloads.deleteAttempt(original.download))
        assertNull(dao.getDownloadByChapter(original.saved.id))
        assertNull(artifactRuntime.dao.get(original.saved.id)?.token)
    }

    @Test
    fun failedCleanupDrainsOriginalProducerAndDoesNotBlockAnUnrelatedChapter() = downloadRecoveryTest {
        val original = seed(DownloadingState.QUEUED)
        val other = seed(DownloadingState.FAILED)
        val files = partials(original)
        partials(other)
        val runtime = artifactRuntime
        val claim = assertNotNull(runtime.downloads.claim(original.download))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        coroutineScope {
            val producer = async {
                runtime.ownership.producing(claim) {
                    entered.complete(Unit)
                    release.await()
                    assertNull(runtime.ownership.files(claim) { error("Old producer must not publish") })
                }
            }
            try {
                entered.await()
                assertTrue(runtime.downloads.fail(claim, "source failure"))
                val failed = download(original)
                val deletion = async {
                    runtime.downloads.deleteAttempt(failed) { cleanup ->
                        assertEquals(claim.token, cleanup.token, "Original use permits must be drained")
                        stopped.complete(Unit)
                    }
                }
                stopped.await()
                assertFalse(deletion.isCompleted)
                assertEquals(failed, download(original))
                files.forEach { assertTrue(fs.exists(it)) }
                assertFalse(runtime.dao.canPublish(claim))
                assertTrue(runtime.downloads.deleteAttempt(other.download))
                release.complete(Unit)
                producer.await()
                assertTrue(deletion.await())
                assertNull(dao.getDownloadByChapter(original.saved.id))
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun capturedDeleteRejectsReplacementBeforeStopButANewDeleteRemovesTheActiveAttempt() = downloadRecoveryTest {
        val original = seed(DownloadingState.FAILED)
        partials(original)
        val runtime = artifactRuntime
        val replacement = assertNotNull(runtime.downloads.enqueue(original.saved, original.download.copy(state = DownloadingState.QUEUED)))
        val current = download(original)
        var stops = 0
        assertFalse(runtime.downloads.deleteAttempt(original.download) { stops++ })
        assertEquals(0, stops)
        assertEquals(current, download(original))
        assertTrue(runtime.dao.canPublish(replacement))
        assertTrue(runtime.downloads.deleteAttempt(current) {
            assertEquals(replacement.token, it.token)
            stops++
        })
        assertEquals(1, stops)
        assertNull(dao.getDownloadByChapter(original.saved.id))
        assertFalse(runtime.downloads.deleteAttempt(current) { stops++ })
        assertEquals(1, stops, "An absent row conveys no cancellation authority")
    }

    @Test
    fun successHistoryAndForeignConversionCustodyNeverBecomeFailedCleanup() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val runtime = artifactRuntime
        runtime.ownership.read(original.saved.id) { assertNull(it) }
        // An already-live foreign operation, not a request to run/validate a synthetic conversion.
        val conversion = ChapterArtifactEntity(
            original.saved.id, original.saved.mangaId, original.saved.url,
            token = "11111111-1111-4111-8111-111111111111", operation = ChapterArtifactOperation.CONVERT,
            downloadId = original.download.id,
        )
        runtime.dao.insert(conversion)
        dao.updateStateChId(original.saved.id, DownloadingState.FAILED)
        assertFalse(runtime.downloads.deleteAttempt(download(original)) { error("Cannot stop a conversion") })
        assertEquals(conversion.token, runtime.dao.get(original.saved.id)?.token)
        dao.updateStateChId(original.saved.id, DownloadingState.SUCCESS)
        assertTrue(runtime.downloads.deleteAttempt(download(original)) { error("SUCCESS is history-only") })
        assertEquals(original.saved, saved(original))
        assertRetainedFiles(original)
        assertEquals(conversion.token, runtime.dao.get(original.saved.id)?.token)
        assertNull(dao.getDownloadByChapter(original.saved.id))
    }

    @Test
    fun cancelledDeleteRetainsItsExactCleanupIntentForRestart() = downloadRecoveryTest {
        val original = seed(DownloadingState.FAILED)
        val files = partials(original)
        assertFailsWith<CancellationException> {
            artifactRuntime.downloads.deleteAttempt(original.download) { throw CancellationException("cancel Delete caller") }
        }
        assertEquals(original.download, download(original))
        assertEquals(ChapterArtifactOperation.FAILED_CLEANUP, artifactRuntime.dao.get(original.saved.id)?.operation)
        files.forEach { assertTrue(fs.exists(it)) }
        reopen()
        artifactRuntime.ownership.read(original.saved.id) { assertNull(it?.token) }
        files.forEach { assertFalse(fs.exists(it)) }
        assertNull(dao.getDownloadByChapter(original.saved.id))
    }

    private fun DownloadRecoveryFixture.partials(chapter: RetainedDownload): List<Path> {
        val directory = appFileSystem.chapterDir(chapter.saved.mangaId, chapter.saved.id)
        return listOf("image_0.png", ".image_1.part", "manifest.json", ".manifest-test", "chapter.cbz.part")
            .map { directory / it }.onEach { path -> fs.write(path) { writeUtf8("attempt data") } }
    }
}
