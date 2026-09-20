package me.manga.kira.data.backup

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import me.manga.kira.core.error.AppError
import me.manga.kira.data.backup.model.BackupChapter
import me.manga.kira.data.backup.model.BackupFile
import me.manga.kira.data.backup.model.BackupManga
import me.manga.kira.data.repository.progress.ProgressRuntimeFixture
import me.manga.kira.data.repository.recoveryTestPng
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.platform.download.DownloadOperationBusy
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.IOException
import okio.Path
import okio.Sink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real owner writers and archive cleanup; no queue/ledger snapshot substitutes for an operation. */
class BackupOperationExclusionTest {
    @Test
    fun exportWaitsBeforeOwnerCaptureAndRetainsOperationThroughFailedArchiveCleanup() = runTest {
        val entrants = this
        BackupOperationFixture("operation-export").use { f ->
            seedBackupManga(f.runtime, f.files, "Export", "operation-export", withDownload = true)
            f.writerAttempts.set(0)
            val closed = CompletableDeferred<Boolean>()
            val deleted = CompletableDeferred<Boolean>()
            val exportDir = f.files.cacheDir / "backup_export"
            f.observed.afterSinkClose = { path ->
                if (path.parent == exportDir) {
                    closed.complete(f.operations.refusesIndependentWriter())
                    throw IOException("controlled export close failure")
                }
            }
            f.observed.beforeDelete = { path ->
                if (path.parent == exportDir) deleted.complete(f.operations.refusesIndependentWriter())
            }
            val pending = f.operations.withExclusive {
                entrants.async(start = CoroutineStart.UNDISPATCHED) { f.repository.exportBackup(BackupScope.FullLibrary, true) }.also {
                    assertFalse(it.isCompleted)
                    assertEquals(0, f.writerAttempts.get())
                }
            }
            assertIs<AppError.Storage.Io>(pending.await().errorOrNull())
            assertTrue(closed.await() && deleted.await())
            assertTrue(f.writerAttempts.get() > 0)
            assertTrue(f.files.fileSystem().list(exportDir).isEmpty())
            f.operations.withExclusive {}
        }
    }

    @Test
    fun importWaitsBeforeInputAndOwnerCaptureAndRetainsOperationUntilPlanCloses() = runTest {
        val entrants = this
        BackupOperationFixture("operation-import").use { f ->
            val archive = f.pickedArchive()
            val planClosed = CompletableDeferred<Boolean>()
            f.observed.beforeDelete = { path ->
                if (path.parent == f.files.cacheDir / "backup_preflight") {
                    planClosed.complete(f.operations.refusesIndependentWriter())
                }
            }
            val captures = f.observed.captures.get()
            val pending = f.operations.withExclusive {
                entrants.async(start = CoroutineStart.UNDISPATCHED) { f.repository.importBackup(archive) }.also {
                    assertFalse(it.isCompleted)
                    assertEquals(captures, f.observed.captures.get())
                    assertEquals(0, f.writerAttempts.get())
                }
            }
            assertEquals(1, assertNotNull(pending.await().getOrNull()).mangasAdded)
            assertTrue(planClosed.await())
            assertTrue(f.observed.captures.get() > captures && f.writerAttempts.get() > 0)
            assertEquals("https://current.test/work/one", f.runtime.db.backupDao().getAllSavedManga().single().url)
            assertTrue(f.files.fileSystem().list(f.files.cacheDir / "backup_preflight").isEmpty())
            f.operations.withExclusive {}
        }
    }

    @Test
    fun cancelledRestoreRetainsOperationThroughGenerationSettlementAndPlanCleanup() = runTest {
        BackupOperationFixture("operation-cancel").use { f ->
            val archive = f.pickedArchive(withDownload = true)
            val partDeleted = CompletableDeferred<Pair<Path, Boolean>>()
            val planClosed = CompletableDeferred<Boolean>()
            f.observeRestoreCleanup(partDeleted, planClosed)
            val pending = async {
                val owner = currentCoroutineContext().job
                f.observed.afterSinkClose = { path ->
                    if (path.name == "chapter.cbz.part") {
                        owner.cancel()
                        throw CancellationException("controlled restore copy cancellation")
                    }
                }
                f.repository.importBackup(archive)
            }
            assertFailsWith<CancellationException> { pending.await() }
            pending.join()
            assertTrue(partDeleted.isCompleted && planClosed.isCompleted)
            val (part, held) = partDeleted.await()
            assertTrue(held && planClosed.await())
            assertFalse(f.files.fileSystem().exists(part))
            val parent = f.runtime.db.backupDao().getAllSavedManga().single()
            assertFalse(f.runtime.db.backupDao().getChaptersForManga(parent.id).single().isDownloaded)
            assertTrue(f.files.fileSystem().list(f.files.cacheDir / "backup_preflight").isEmpty())
            f.operations.withExclusive {}
        }
    }
}

/** Reuses the existing Room/progress and backup archive fixtures; only filesystem edges are observed. */
private class BackupOperationFixture(label: String) : Closeable {
    val runtime = ProgressRuntimeFixture()
    val files = BackupTestFileSystem(label)
    val operations = DownloadOperationExclusion()
    val observed = BackupOperationFiles(files)
    val writerAttempts = AtomicInteger()
    val repository = backupTestRepository(
        runtime.db, observed, BackupMergeWriter(runtime.owners, runtime.legacySettings), operations = operations,
    )

    init {
        runtime.transactions.onAttempt = { writerAttempts.incrementAndGet() }
    }

    fun pickedArchive(withDownload: Boolean = false): String {
        val chapters = if (withDownload) listOf(
            BackupChapter(url = "https://current.test/chapter/one", downloadEntry = "downloads/0.cbz"),
        ) else emptyList()
        val document = BackupFile(includesDownloads = withDownload, mangas = listOf(
            BackupManga(api = "source", url = "https://current.test/work/one", title = "One", chapters = chapters),
        ))
        val entries = mutableListOf(BACKUP_JSON_ENTRY to backupJson.encodeToString(document).encodeToByteArray())
        if (withDownload) entries += "downloads/0.cbz" to deflatedBackupArchive("1.png" to recoveryTestPng())
        val archive = files.cacheDir / "picked.zip"
        files.fileSystem().createDirectories(files.cacheDir)
        files.fileSystem().write(archive) { write(deflatedBackupArchive(*entries.toTypedArray())) }
        return archive.toString()
    }

    fun observeRestoreCleanup(
        partDeleted: CompletableDeferred<Pair<Path, Boolean>>,
        planClosed: CompletableDeferred<Boolean>,
    ) {
        observed.beforeDelete = { path ->
            if (path.name == "chapter.cbz.part") {
                val hasBytes = (files.fileSystem().metadataOrNull(path)?.size ?: 0) > 0
                partDeleted.complete(path to (hasBytes && operations.refusesIndependentWriter()))
            } else if (path.parent == files.cacheDir / "backup_preflight") {
                planClosed.complete(operations.refusesIndependentWriter())
            }
        }
    }

    override fun close() {
        try { runtime.close() } finally { files.cleanUp() }
    }
}

private class BackupOperationFiles(delegate: AppFileSystem) : AppFileSystem by delegate {
    val captures = AtomicInteger()
    var afterSinkClose: (Path) -> Unit = {}
    var beforeDelete: (Path) -> Unit = {}
    private val observed = object : ForwardingFileSystem(delegate.fileSystem()) {
        override fun sink(file: Path, mustCreate: Boolean): Sink {
            val sink = super.sink(file, mustCreate)
            return object : ForwardingSink(sink) {
                override fun close() {
                    super.close()
                    afterSinkClose(file)
                }
            }
        }

        override fun delete(path: Path, mustExist: Boolean) {
            beforeDelete(path)
            super.delete(path, mustExist)
        }
    }

    override fun fileSystem(): FileSystem {
        captures.incrementAndGet()
        return observed
    }
}

/** A fresh coroutine context is intentional: an upgrade error is not the expected busy result. */
private fun DownloadOperationExclusion.refusesIndependentWriter(): Boolean = runBlocking {
    try {
        withExclusive {}
        false
    } catch (_: DownloadOperationBusy) {
        true
    }
}
