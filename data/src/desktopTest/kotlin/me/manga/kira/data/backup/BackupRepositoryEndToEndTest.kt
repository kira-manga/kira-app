@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("FunctionNaming", "MagicNumber")

package me.manga.kira.data.backup

import kotlinx.coroutines.test.runTest
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.repository.BackupRepositoryImpl
import me.manga.kira.data.repository.libraryParent
import me.manga.kira.data.repository.librarySavedChapter
import me.manga.kira.data.repository.progress.ProgressRuntimeFixture
import me.manga.kira.data.repository.progress.seedLegacy
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.model.backup.BackupSelection
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.WorkLocator
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * High-level proof of Kira's two supported import artifacts: a complete library backup and an
 * individually exported manga package. The test crosses the real repository, JSON codec, ZIP
 * writer/reader, Room DAO transactions, resume-position store, and CBZ restore path using two
 * independent databases and filesystem roots (exporting device -> importing device).
 */
class BackupRepositoryEndToEndTest {
    private lateinit var source: ProgressRuntimeFixture
    private lateinit var target: ProgressRuntimeFixture
    private val targetDb get() = target.db
    private lateinit var sourceFs: BackupTestFileSystem
    private lateinit var targetFs: BackupTestFileSystem
    private lateinit var sourceRepository: BackupRepositoryImpl
    private lateinit var targetRepository: BackupRepositoryImpl

    @BeforeTest
    fun open() {
        source = ProgressRuntimeFixture()
        target = ProgressRuntimeFixture()
        sourceFs = BackupTestFileSystem("source")
        targetFs = BackupTestFileSystem("target")
        sourceRepository = backupTestRepository(source.db, sourceFs, BackupMergeWriter(source.owners, source.legacySettings))
        targetRepository = backupTestRepository(target.db, targetFs, BackupMergeWriter(target.owners, target.legacySettings))
    }

    @AfterTest
    fun close() {
        source.close()
        target.close()
        sourceFs.cleanUp()
        targetFs.cleanUp()
    }

    @Test
    fun fullBackup_restoresMetadataChaptersCoverDownloadHistoryAndProgress() =
        runTest {
            val occupied = target.parent(libraryParent(url = "https://current.test/occupied"))
            target.chapter(librarySavedChapter(occupied))
            val first = seedManga("First Manga", "first", withDownload = true)
            seedManga("Second Manga", "second", withDownload = false)

            val exported = sourceRepository.exportBackup(BackupScope.FullLibrary, includeDownloads = true).success()
            assertEquals(2, exported.mangaCount)
            assertEquals(2, exported.chapterCount)
            assertEquals(1, exported.downloadCount)
            assertTrue(exported.suggestedName.startsWith("kira-backup-"))

            val imported = targetRepository.importBackup(exported.archivePath).success()
            assertEquals(2, imported.mangasAdded)
            assertEquals(2, imported.chaptersAdded)
            assertEquals(1, imported.downloadsRestored)
            assertEquals(2, imported.historyMerged)

            val restored = assertNotNull(targetDb.backupDao().getMangaByUrl(first.manga.url))
            assertTrue(restored.id != first.manga.id, "portable IDs never select the receiver's parent")
            assertEquals(first.manga.imageUrl, restored.imageUrl, "cover URL metadata survives")
            assertEquals(first.manga.description, restored.description)
            assertEquals(first.manga.genres, restored.genres)
            assertTrue(restored.isLiked)
            assertTrue(restored.isWatchingNow)

            val chapter = assertNotNull(targetDb.backupDao().getChapterByMangaAndUrl(restored.id, first.chapter.url))
            assertTrue(chapter.id != first.chapter.id, "chapter IDs are receiver-local too")
            assertTrue(chapter.isRead)
            assertTrue(chapter.isBookmarked)
            assertTrue(chapter.isDownloaded)
            val locator = ChapterLocator(WorkLocator(restored.api, restored.url), chapter.url)
            assertEquals(7, target.native.readPosition(locator).success())

            val targetCbz = backupTestCbzReader(targetFs)
            val restoredPath = chapter.localImagePaths.single().toPath()
            assertTrue(targetFs.fileSystem().exists(restoredPath))
            assertFalse(targetCbz.cbzExists(restored.id, chapter.id), "restore never overwrites canonical bytes")
            assertEquals(1, targetCbz.pageCount(restoredPath))
            val downloadRow = assertNotNull(targetDb.backupDao().getDownloadRowByChapter(chapter.id))
            assertEquals(100, downloadRow.progress)
            assertTrue(downloadRow.sizeBytes > 0)

            val history = targetDb.backupDao().getAllHistoryOnce().associateBy { it.mangaUrl }
            assertEquals(first.chapter.url, history.getValue(first.manga.url).chapterUrl)
            assertEquals(7, history.getValue(first.manga.url).lastReadPage)
        }

    @Test
    fun individualMangaPackage_restoresOnlyTheSelectedManga() =
        runTest {
            val selected = seedManga("Selected Manga", "selected", withDownload = true)
            val excluded = seedManga("Excluded Manga", "excluded", withDownload = false)

            val scope =
                BackupScope.Mangas(
                    listOf(BackupSelection(WorkLocator(selected.manga.api, selected.manga.url), selected.manga.title)),
                )
            val exported = sourceRepository.exportBackup(scope, includeDownloads = true).success()
            assertEquals(1, exported.mangaCount)
            assertEquals(1, exported.chapterCount)
            assertEquals(1, exported.downloadCount)
            assertTrue(exported.suggestedName.lowercase().startsWith("kira-manga-selected-manga-"))

            val imported = targetRepository.importBackup(exported.archivePath).success()
            assertEquals(1, imported.mangasAdded)
            assertEquals(1, imported.chaptersAdded)
            assertEquals(1, imported.downloadsRestored)
            assertEquals(1, imported.historyMerged)

            val restored = assertNotNull(targetDb.backupDao().getMangaByUrl(selected.manga.url))
            assertEquals(selected.manga.imageUrl, restored.imageUrl)
            assertEquals(null, targetDb.backupDao().getMangaByUrl(excluded.manga.url))
            assertEquals(listOf(selected.manga.url), targetDb.backupDao().getAllHistoryOnce().map { it.mangaUrl })
        }

    @Test
    fun repeatedImportKeepsTheCommittedGenerationAndReexportsIt() = runTest {
        val seeded = seedManga("Round trip", "round-trip", withDownload = true)
        val exported = sourceRepository.exportBackup(BackupScope.FullLibrary, includeDownloads = true).success()
        assertEquals(1, targetRepository.importBackup(exported.archivePath).success().downloadsRestored)
        val manga = assertNotNull(targetDb.backupDao().getMangaByUrl(seeded.manga.url))
        val chapter = assertNotNull(targetDb.backupDao().getChapterByMangaAndUrl(manga.id, seeded.chapter.url))
        val repeated = targetRepository.importBackup(exported.archivePath).success()
        assertEquals(0, repeated.mangasAdded)
        assertEquals(0, repeated.chaptersAdded)
        assertEquals(0, repeated.downloadsRestored)
        assertEquals(chapter, targetDb.backupDao().getChapterByMangaAndUrl(manga.id, seeded.chapter.url))
        val reexported = targetRepository.exportBackup(BackupScope.FullLibrary, includeDownloads = true).success()
        assertEquals(1, reexported.downloadCount)
        assertEquals(0, targetRepository.importBackup(reexported.archivePath).success().downloadsRestored)
    }

    @Test
    fun missingExplicitGenerationNeverExportsAnUnrelatedCanonicalFile() = runTest {
        val seeded = seedManga("Missing", "missing", withDownload = true)
        val exported = sourceRepository.exportBackup(BackupScope.FullLibrary, includeDownloads = true).success()
        targetRepository.importBackup(exported.archivePath).success()
        val manga = assertNotNull(targetDb.backupDao().getMangaByUrl(seeded.manga.url))
        val chapter = assertNotNull(targetDb.backupDao().getChapterByMangaAndUrl(manga.id, seeded.chapter.url))
        val restored = chapter.localImagePaths.single().toPath()
        val fs = targetFs.fileSystem()
        val canonical = backupTestCbzReader(targetFs).cbzPath(manga.id, chapter.id)
        fs.write(canonical) { write(fs.read(restored) { readByteArray() }) }
        fs.delete(restored)
        val reexported = targetRepository.exportBackup(BackupScope.FullLibrary, includeDownloads = true).success()
        assertEquals(0, reexported.downloadCount)
        assertEquals(1, reexported.skippedLooseDownloads)
        assertTrue(fs.exists(canonical))
    }

    @Test
    fun quarantined_import_returns_constraint_without_settings_or_cbz_mutation() = runTest {
        val incoming = seedManga("Incoming", "conflict", withDownload = true)
        val exported = sourceRepository.exportBackup(BackupScope.FullLibrary, includeDownloads = true).success()
        val foreign = target.parent(incoming.manga.copy(id = 0, api = "foreign"))
        val existing = target.chapter(incoming.chapter.copy(id = 0, mangaId = foreign.id, isDownloaded = false))
        val captured = target.seedLegacy(ChapterLocator(WorkLocator(foreign.api, foreign.url), existing.url))

        val result = assertIs<AppResult.Failure>(targetRepository.importBackup(exported.archivePath))

        assertIs<AppError.Storage.Constraint>(result.error)
        assertEquals(listOf(foreign), targetDb.backupDao().getAllSavedManga())
        assertEquals(listOf(existing), targetDb.backupDao().getChaptersForManga(foreign.id))
        assertTrue(targetDb.backupDao().getAllHistoryOnce().isEmpty())
        assertTrue(targetDb.readerProgressDao().worksForApi(incoming.manga.api).isEmpty())
        assertTrue(targetDb.readerProgressDao().worksForApi(foreign.api).isEmpty())
        assertTrue(targetDb.readerLegacyCleanupDao().allReceipts().isEmpty())
        assertEquals(captured.payload, target.settings.getStringOrNull(captured.key))
        assertEquals(0, target.settings.removeAttempts)
        assertFalse(targetFs.fileSystem().exists(targetFs.filesDir))
    }

    private suspend fun seedManga(title: String, slug: String, withDownload: Boolean) =
        seedBackupManga(source, sourceFs, title, slug, withDownload)

    private fun <T> AppResult<T>.success(): T =
        when (this) {
            is AppResult.Success -> value
            is AppResult.Failure -> error("Expected success, got $error")
        }

}
