@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("FunctionNaming", "MagicNumber")

package me.manga.kira.data.backup

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.repository.BackupRepositoryImpl
import me.manga.kira.data.repository.libraryParent
import me.manga.kira.data.repository.librarySavedChapter
import me.manga.kira.data.repository.progress.ProgressRuntimeFixture
import me.manga.kira.data.repository.progress.seedLegacy
import me.manga.kira.data.repository.selection.StrictSourceSelectionMigration
import me.manga.kira.data.repository.selection.strictRule
import me.manga.kira.data.repository.selection.strictToken
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
    fun oldHostPackage_mergesIntoCurrentHostWithoutDuplicatingOwnersOrPackedDownloads() = runTest {
        val incoming = seedBackupManga(source, sourceFs, "Old title", "alias", true, host = "old.test")
        val exported = sourceRepository.exportBackup(BackupScope.FullLibrary, includeDownloads = true).success()
        val receiver = seedCurrentAliasReceiver(incoming)
        val parents = targetDb.backupDao().getAllSavedManga()
        val historyId = targetDb.backupDao().getAllHistoryOnce().single().id
        val imported = targetRepository.importBackup(exported.archivePath).success()
        assertEquals(0, imported.mangasAdded)
        assertEquals(0, imported.chaptersAdded)
        assertEquals(1, imported.downloadsRestored)
        assertEquals(parents, targetDb.backupDao().getAllSavedManga())
        val restored = assertImportedAliasState(receiver, historyId)
        val mergedHistory = targetDb.backupDao().getAllHistoryOnce().single()
        // Real nonempty SQL projection, not shipping worker/native exclusion evidence.
        target.transactions.write {
            StrictSourceSelectionMigration(targetDb.sourceSelectionMigrationDao(), targetDb.readerProgressDao())
                .migrateInTransaction(strictToken(), listOf(strictRule(receiver.manga.api, "https://current.test")))
        }
        val repeated = targetRepository.importBackup(exported.archivePath).success()
        assertEquals(0, repeated.mangasAdded)
        assertEquals(0, repeated.chaptersAdded)
        assertEquals(0, repeated.downloadsRestored)
        assertEquals(restored, assertImportedAliasState(receiver, historyId))
        assertEquals(mergedHistory, targetDb.backupDao().getAllHistoryOnce().single())
        val work = WorkLocator(receiver.manga.api, receiver.manga.url)
        val scope = BackupScope.Mangas(listOf(BackupSelection(work, receiver.manga.title)))
        assertEquals(1, targetRepository.exportBackup(scope, includeDownloads = true).success().downloadCount)
    }

    private suspend fun seedCurrentAliasReceiver(incoming: SeededBackupManga): SeededBackupManga {
        val occupied = target.parent(libraryParent(url = "https://current.test/occupied"))
        target.chapter(librarySavedChapter(occupied))
        val current = target.parent(incoming.manga.copy(
            id = 0, url = "https://current.test/manga/alias", title = "Current title",
        ))
        val child = target.chapter(incoming.chapter.copy(
            id = 0, mangaId = current.id, url = "https://current.test/chapter/alias-1",
            isDownloaded = false, localImagePaths = emptyList(), lastReadDate = 0,
        ))
        val locator = ChapterLocator(WorkLocator(current.api, current.url), child.url)
        target.native.save(target.native.beginSession(locator).success().handle, 2).success()
        val history = source.db.backupDao().getAllHistoryOnce().single().copy(
            id = 0, mangaId = current.id, mangaUrl = current.url, chapterUrl = child.url,
            lastReadDate = LocalDateTime(2026, 7, 17, 12, 0), lastReadPage = 2,
        )
        check(targetDb.backupDao().insertHistoryRow(history) > 0)
        return SeededBackupManga(current, child)
    }

    private suspend fun assertImportedAliasState(receiver: SeededBackupManga, historyId: Long): SavedChapterEntity {
        val (current, child) = receiver
        val restored = targetDb.backupDao().getChaptersForManga(current.id).single()
        assertEquals(child.id, restored.id)
        assertEquals(child.url, restored.url)
        assertTrue(restored.isDownloaded)
        val locator = ChapterLocator(WorkLocator(current.api, current.url), child.url)
        assertEquals(7, target.native.readPosition(locator).success())
        val history = targetDb.backupDao().getAllHistoryOnce().single()
        assertEquals(historyId, history.id)
        assertEquals(current.id, history.mangaId)
        assertEquals(current.url, history.mangaUrl)
        assertEquals(child.url, history.chapterUrl)
        assertEquals(7, history.lastReadPage)
        assertEquals(1, backupTestCbzReader(targetFs).pageCount(restored.localImagePaths.single().toPath()))
        assertEquals(child.id, targetDb.backupDao().getDownloadRowByChapter(child.id)?.chapterId)
        return restored
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
