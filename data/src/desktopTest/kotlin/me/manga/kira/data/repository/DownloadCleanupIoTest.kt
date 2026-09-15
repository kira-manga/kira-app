package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Existing real-Room fixture, actual dedicated UI dispatcher and checked filesystem boundaries. */
class DownloadCleanupIoTest {
    @Test
    fun actualDeleteStatAndOpenLeaveTheCallingUiDispatcher() = downloadRecoveryTest {
        val completed = seed(sizeBytes = 0)
        val removed = seed(isDownloaded = true)
        val cancelled = seed(state = DownloadingState.QUEUED)
        val partial = appFileSystem.chapterDir(cancelled.saved.mangaId, cancelled.saved.id) / "image_0.png"
        fs.write(partial) { writeUtf8("retained partial") }
        val operations = ConcurrentHashMap.newKeySet<String>()
        Executors.newSingleThreadExecutor { task -> Thread(task, "download-maintenance-ui") }
            .asCoroutineDispatcher().use { ui ->
                withContext(ui) {
                    val uiThread = Thread.currentThread()
                    val guarded = object : ForwardingFileSystem(fs) {
                        private fun observe(operation: String) {
                            check(Thread.currentThread() !== uiThread) { "Blocking filesystem work on UI thread" }
                            operations += operation
                        }
                        override fun metadataOrNull(path: Path): FileMetadata? {
                            observe("stat")
                            return super.metadataOrNull(path)
                        }
                        override fun source(file: Path): Source {
                            observe("open")
                            return super.source(file)
                        }
                        override fun list(dir: Path): List<Path> {
                            observe("list")
                            return super.list(dir)
                        }
                        override fun delete(path: Path, mustExist: Boolean) {
                            observe("delete")
                            super.delete(path, mustExist)
                        }
                    }
                    val appFs = object : AppFileSystem by appFileSystem {
                        override fun fileSystem(): FileSystem = guarded
                    }
                    val runtime = ArtifactTestRuntime(db, appFs)
                    val actions = DownloadsActionRepositoryImpl(
                        FakeDownloadRepository(), dao, db.chapterDao(), appFs, runtime.ownership,
                    )
                    assertTrue(actions.reconcileInterrupted().isSuccess)
                    assertTrue(runtime.downloads.exactSize(completed.saved.localImagePaths) > 0)
                    assertTrue(actions.deleteDownloadedChapter(removed.saved.id).isSuccess)
                    val claim = assertNotNull(runtime.downloads.claim(cancelled.download))
                    assertNotNull(runtime.downloads.cancel(cancelled.saved.id, "__cancelled_by_user__"))
                    assertTrue(runtime.downloads.settle(claim))
                }
            }
        assertTrue(operations.containsAll(setOf("delete", "list", "stat", "open")))
        assertTrue(download(completed).sizeBytes > 0)
        assertFalse(fs.exists(partial))
    }

    @Test
    fun failedFullCleanupReportsFailureKeepsCustodyAndOtherChaptersStillFinish() = downloadRecoveryTest {
        val blocked = seed(isDownloaded = true)
        val other = seed(isDownloaded = true)
        val directory = appFileSystem.chapterDir(blocked.saved.mangaId, blocked.saved.id)
        val failing = object : ForwardingFileSystem(fs) {
            override fun delete(path: Path, mustExist: Boolean) {
                if (path == directory) throw IOException("private filesystem detail must not reach UI")
                super.delete(path, mustExist)
            }
        }
        val appFs = object : AppFileSystem by appFileSystem {
            override fun fileSystem(): FileSystem = failing
        }
        val runtime = ArtifactTestRuntime(db, appFs)
        val actions = DownloadsActionRepositoryImpl(
            FakeDownloadRepository(), dao, db.chapterDao(), appFs, runtime.ownership,
        )
        val failed = actions.deleteDownloadedChapter(blocked.saved.id)
        assertTrue(failed.isFailure)
        assertEquals("Chapter artifact removal could not be settled", failed.exceptionOrNull()?.message)
        assertEquals(ChapterArtifactOperation.DELETE, runtime.dao.get(blocked.saved.id)?.operation)
        assertNotNull(runtime.dao.get(blocked.saved.id)?.token)
        assertNotNull(db.chapterDao().getChapterByIdSuspend(blocked.saved.id))
        assertTrue(fs.exists(directory))
        assertTrue(actions.deleteDownloadedChapter(other.saved.id).isSuccess)
        assertFalse(fs.exists(appFileSystem.chapterDir(other.saved.mangaId, other.saved.id)))

        reopen()
        artifactRuntime.ownership.read(blocked.saved.id) { assertNull(it) }
        assertFalse(fs.exists(directory))
        assertNull(dao.getDownloadByChapter(blocked.saved.id))
        assertEquals(blocked.saved.copy(isDownloaded = false, localImagePaths = emptyList()), saved(blocked))
    }

    @Test
    fun sizeFailureIsReportedAfterOtherExactFileBackfillsAndCanBeRetried() = downloadRecoveryTest {
        val blocked = seed(sizeBytes = 0)
        val other = seed(sizeBytes = 0)
        val blockedPath = blocked.saved.localImagePaths.first().toPath()
        val failing = object : ForwardingFileSystem(fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                if (path == blockedPath) throw IOException("private failed stat detail")
                return super.metadataOrNull(path)
            }
        }
        val appFs = object : AppFileSystem by appFileSystem { override fun fileSystem() = failing }
        val result = actions(fileSystem = appFs).reconcileInterrupted()
        assertTrue(result.isFailure)
        assertEquals("Download maintenance could not be completed", result.exceptionOrNull()?.message)
        assertEquals(blocked.download, download(blocked))
        assertEquals(blocked.saved, saved(blocked))
        assertTrue(download(other).sizeBytes > 0)
        assertTrue(saved(other).isDownloaded)
        assertRetainedFiles(blocked)
        reopen()
        assertTrue(actions().reconcileInterrupted().isSuccess)
        assertTrue(download(blocked).sizeBytes > 0)
        assertTrue(saved(blocked).isDownloaded)
    }

    @Test
    fun cancelledFullCleanupPropagatesAndLeavesRecoverableCustody() = downloadRecoveryTest {
        val original = seed(isDownloaded = true)
        val directory = appFileSystem.chapterDir(original.saved.mangaId, original.saved.id)
        val failing = object : ForwardingFileSystem(fs) {
            override fun delete(path: Path, mustExist: Boolean) {
                if (path == directory) throw CancellationException("cancel cleanup")
                super.delete(path, mustExist)
            }
        }
        val appFs = object : AppFileSystem by appFileSystem { override fun fileSystem() = failing }
        val runtime = ArtifactTestRuntime(db, appFs)
        val actions = DownloadsActionRepositoryImpl(
            FakeDownloadRepository(), dao, db.chapterDao(), appFs, runtime.ownership,
        )
        assertFailsWith<CancellationException> { actions.deleteDownloadedChapter(original.saved.id) }
        assertEquals(ChapterArtifactOperation.DELETE, runtime.dao.get(original.saved.id)?.operation)
        assertNotNull(runtime.dao.get(original.saved.id)?.token)
        assertTrue(fs.exists(directory))
        reopen()
        artifactRuntime.ownership.read(original.saved.id) { assertNull(it) }
        assertFalse(fs.exists(directory))
    }
}
