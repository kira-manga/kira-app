package me.manga.kira.data.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.backup.model.BackupChapter
import me.manga.kira.data.backup.model.BackupFile
import me.manga.kira.data.backup.model.BackupManga
import me.manga.kira.data.download.artifacts.ChapterArtifactRecovery
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.dao.ChapterArtifactCommitDao
import me.manga.kira.data.local.dao.ChapterRestoreOutcome
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.repository.DownloadRecoveryFixture
import me.manga.kira.data.repository.downloadRecoveryTest
import me.manga.kira.data.repository.recoveryTestPng
import me.manga.kira.domain.model.backup.BackupProgress
import me.manga.kira.platform.backup.BackupImportPolicy
import me.manga.kira.platform.backup.BackupImportStaging
import me.manga.kira.platform.backup.BackupJsonLimits
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.media.DesktopPageMediaInspector
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageMediaInspector
import okio.Buffer
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Production repository entrypoint, not only the preflight helper: rejection precedes all writes. */
class BackupImporterAdmissionTest {
    private val native = DesktopPageMediaInspector()

    @Test
    fun invalidFinalCbzLeavesEarlierMangasResumeAndExistingArtifactsUntouched() = downloadRecoveryTest {
        val retained = seed(isDownloaded = true)
        val before = db.backupDao().getAllSavedManga()
        val progress = BackupMemoryReadProgress()
        progress.save(retained.saved.url, 4)
        val selected = writeBackup(twoMangas(), recoveryTestPng(), "not an image".encodeToByteArray())
        val repo = backupTestRepository(db, appFileSystem, progress)
        val error = assertIs<AppResult.Failure>(repo.importBackup(selected.toString())).error
        assertIs<AppError.Validation.Format>(error)
        assertEquals(before, db.backupDao().getAllSavedManga())
        assertEquals(retained.saved, saved(retained))
        assertEquals(retained.download, download(retained))
        assertNull(db.chapterArtifactDao().get(retained.saved.id))
        assertEquals(4, progress.load(retained.saved.url))
        assertNull(progress.load("imported-chapter-0"))
        assertTrue(db.backupDao().getAllHistoryOnce().isEmpty())
        assertRetainedFiles(retained)
        assertTrue(fs.exists(selected))
        assertNoImportSnapshots()
    }

    @Test
    fun cancellingLastPageCleansPrivateSnapshotsAndResetsTheRunGate() = downloadRecoveryTest {
        val retained = seed(isDownloaded = true)
        val before = db.backupDao().getAllSavedManga()
        val progress = BackupMemoryReadProgress()
        val cancelled = CancellationException("cancel final page")
        var inspected = 0
        val inspector = object : PageMediaInspector by native {
            override fun inspect(encoded: ByteArray): PageInspection {
                if (++inspected == 2) throw cancelled
                return native.inspect(encoded)
            }
        }
        val repo = backupTestRepository(db, appFileSystem, progress, inspector)
        val selected = writeBackup(twoMangas(), recoveryTestPng(), recoveryTestPng())
        assertSame(cancelled, assertFailsWith<CancellationException> { repo.importBackup(selected.toString()) })
        assertEquals(before, db.backupDao().getAllSavedManga())
        assertEquals(retained.saved, saved(retained))
        assertEquals(retained.download, download(retained))
        assertNull(progress.load("imported-chapter-0"))
        assertEquals(BackupProgress(), repo.observeProgress().first())
        assertRetainedFiles(retained)
        assertNoImportSnapshots()
        assertIs<AppResult.Success<*>>(repo.importBackup(writeBackup(BackupFile()).toString()))
    }

    @Test
    fun userStopDuringPreflightIsStoppedNotFailedAndDoesNotMergeTheFirstManga() = downloadRecoveryTest {
        val before = db.backupDao().getAllSavedManga()
        var stop: () -> Unit = {}
        var inspected = 0
        val inspector = object : PageMediaInspector by native {
            override fun inspect(encoded: ByteArray): PageInspection {
                if (++inspected == 2) stop()
                return native.inspect(encoded)
            }
        }
        val progress = BackupMemoryReadProgress()
        val repo = backupTestRepository(db, appFileSystem, progress, inspector)
        stop = repo::stop
        val selected = writeBackup(twoMangas(), recoveryTestPng(), recoveryTestPng())
        assertIs<AppError.Cancelled>(assertIs<AppResult.Failure>(repo.importBackup(selected.toString())).error)
        assertEquals(before, db.backupDao().getAllSavedManga())
        assertNull(progress.load("imported-chapter-0"))
        assertTrue(repo.observeProgress().first().wasStopped)
        assertFalse(repo.observeProgress().first().failed)
        assertNoImportSnapshots()
    }

    @Test
    fun futureFormatPreservesItsSpecificTypedError() = downloadRecoveryTest {
        val repo = backupTestRepository(db, appFileSystem, BackupMemoryReadProgress())
        val selected = writeBackup(BackupFile(formatVersion = BACKUP_FORMAT_VERSION + 1))
        val failure = assertIs<AppResult.Failure>(repo.importBackup(selected.toString()))
        assertEquals("formatVersion", assertIs<AppError.Validation.OutOfRange>(failure.error).field)
        assertTrue(db.backupDao().getAllSavedManga().isEmpty())
        assertNoImportSnapshots()
    }

    @Test
    fun actualManifestLimitIsMappedBeforeAnyMetadataMerge() = downloadRecoveryTest {
        val policy = BackupImportPolicy(json = BackupJsonLimits(maxBytes = 16))
        val repo = backupTestRepository(db, appFileSystem, BackupMemoryReadProgress(), policy = policy)
        val selected = writeBackup(twoMangas(), recoveryTestPng(), recoveryTestPng())
        val failure = assertIs<AppResult.Failure>(repo.importBackup(selected.toString()))
        assertEquals("backup_size", assertIs<AppError.Validation.OutOfRange>(failure.error).field)
        assertTrue(db.backupDao().getAllSavedManga().isEmpty())
        assertNoImportSnapshots()
    }

    @Test
    fun unknownSettlementStopsBeforeTheNextMangaWithoutDeletingPublishedFiles() = downloadRecoveryTest {
        val selected = writeBackup(twoMangas(), recoveryTestPng(), recoveryTestPng())
        val importer = unknownSettlementImporter()
        val run = BackupRun(MutableStateFlow(BackupProgress()), { false }, currentCoroutineContext())
        assertFailsWith<IOException> { importer.run(selected.toString(), run) }
        val manga = assertNotNull(db.backupDao().getMangaByUrl("imported-manga-0"))
        val chapter = assertNotNull(db.backupDao().getChapterByMangaAndUrl(manga.id, "imported-chapter-0"))
        val record = assertNotNull(db.chapterArtifactDao().get(chapter.id))
        assertTrue(chapter.isDownloaded)
        assertTrue(fs.exists(chapter.localImagePaths.single().toPath()))
        assertNotNull(record.token, "uncertain publication keeps recovery custody")
        assertNull(db.backupDao().getMangaByUrl("imported-manga-1"))
        assertNoImportSnapshots()
    }

    private fun DownloadRecoveryFixture.unknownSettlementImporter(): BackupImporter {
        val commits = object : ChapterArtifactCommitDao by db.chapterArtifactCommitDao() {
            override suspend fun readRestoreOutcome(
                claim: ChapterArtifactClaim,
                absolutePath: String,
                sizeBytes: Long,
            ): ChapterRestoreOutcome = ChapterRestoreOutcome.UNKNOWN
        }
        val dao = db.chapterArtifactDao()
        val recovery = ChapterArtifactRecovery(dao, commits, appFileSystem)
        val artifacts = ChapterArtifacts(dao, recovery)
        val publisher = RestoredDownloadPublisher(artifacts, dao, commits, appFileSystem, recovery)
        val preflight = BackupArchivePreflight(appFileSystem, BackupImportStaging(appFileSystem), native)
        return BackupImporter(db.backupDao(), BackupMemoryReadProgress(), preflight, publisher)
    }

    private fun twoMangas() = BackupFile(
        includesDownloads = true,
        mangas = (0..1).map { index ->
            BackupManga(
                api = "source", url = "imported-manga-$index", title = "Imported $index",
                chapters = listOf(BackupChapter(url = "imported-chapter-$index", resumePage = 8, downloadEntry = "downloads/$index.cbz")),
            )
        },
    )

    private fun DownloadRecoveryFixture.writeBackup(document: BackupFile, vararg pages: ByteArray): Path {
        fs.createDirectories(appFileSystem.cacheDir)
        val path = appFileSystem.cacheDir / "selected-backup.zip"
        val archive = Buffer()
        BackupZipWriter(archive).apply {
            writeEntryBytes(BACKUP_JSON_ENTRY, backupJson.encodeToString(document).encodeToByteArray())
            pages.forEachIndexed { index, page ->
                val cbz = Buffer()
                BackupZipWriter(cbz).apply { writeEntryBytes("1.png", page); finish() }
                writeEntryBytes("downloads/$index.cbz", cbz.readByteArray())
            }
            finish()
        }
        fs.write(path) { writeAll(archive) }
        return path
    }

    private fun DownloadRecoveryFixture.assertNoImportSnapshots() {
        for (name in listOf("backup_import", "backup_preflight")) {
            val path = appFileSystem.cacheDir / name
            if (fs.exists(path)) assertTrue(fs.list(path).isEmpty())
        }
    }
}
