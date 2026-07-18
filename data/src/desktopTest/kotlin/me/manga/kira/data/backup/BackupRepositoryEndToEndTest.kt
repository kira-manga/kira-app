@file:OptIn(kotlin.time.ExperimentalTime::class)
@file:Suppress("FunctionNaming", "MagicNumber")

package me.manga.kira.data.backup

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.dao.BackupDao
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.repository.BackupRepositoryImpl
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.domain.repository.ReadProgressRepository
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.cbz.DefaultCbzReader
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * High-level proof of Kira's two supported import artifacts: a complete library backup and an
 * individually exported manga package. The test crosses the real repository, JSON codec, ZIP
 * writer/reader, Room DAO transactions, resume-position store, and CBZ restore path using two
 * independent databases and filesystem roots (exporting device -> importing device).
 */
class BackupRepositoryEndToEndTest {
    private lateinit var sourceDb: MangaDatabase
    private lateinit var targetDb: MangaDatabase
    private lateinit var sourceFs: TempAppFileSystem
    private lateinit var targetFs: TempAppFileSystem
    private lateinit var sourceProgress: MemoryReadProgress
    private lateinit var targetProgress: MemoryReadProgress
    private lateinit var sourceRepository: BackupRepositoryImpl
    private lateinit var targetRepository: BackupRepositoryImpl

    @BeforeTest
    fun open() {
        sourceDb = inMemoryDatabase()
        targetDb = inMemoryDatabase()
        sourceFs = TempAppFileSystem("source")
        targetFs = TempAppFileSystem("target")
        sourceProgress = MemoryReadProgress()
        targetProgress = MemoryReadProgress()
        sourceRepository = repository(sourceDb, sourceFs, sourceProgress)
        targetRepository = repository(targetDb, targetFs, targetProgress)
    }

    @AfterTest
    fun close() {
        sourceDb.close()
        targetDb.close()
        sourceFs.cleanUp()
        targetFs.cleanUp()
    }

    @Test
    fun fullBackup_restoresMetadataChaptersCoverDownloadHistoryAndProgress() =
        runTest {
            val first =
                seedManga(
                    dao = sourceDb.backupDao(),
                    title = "First Manga",
                    slug = "first",
                    withDownload = true,
                )
            seedManga(
                dao = sourceDb.backupDao(),
                title = "Second Manga",
                slug = "second",
                withDownload = false,
            )

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
            assertEquals(first.manga.imageUrl, restored.imageUrl, "cover URL metadata survives")
            assertEquals(first.manga.description, restored.description)
            assertEquals(first.manga.genres, restored.genres)
            assertTrue(restored.isLiked)
            assertTrue(restored.isWatchingNow)

            val chapter = assertNotNull(targetDb.backupDao().getChapterByMangaAndUrl(restored.id, first.chapter.url))
            assertTrue(chapter.isRead)
            assertTrue(chapter.isBookmarked)
            assertTrue(chapter.isDownloaded)
            assertEquals(7, targetProgress.load(chapter.url))

            val targetCbz = DefaultCbzReader(targetFs, TestDispatchers)
            assertTrue(targetCbz.cbzExists(restored.id, chapter.id))
            assertEquals(1, targetCbz.pageCount(targetCbz.cbzPath(restored.id, chapter.id)))
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
            val selected =
                seedManga(
                    dao = sourceDb.backupDao(),
                    title = "Selected Manga",
                    slug = "selected",
                    withDownload = true,
                )
            val excluded =
                seedManga(
                    dao = sourceDb.backupDao(),
                    title = "Excluded Manga",
                    slug = "excluded",
                    withDownload = false,
                )

            val scope =
                BackupScope.Mangas(
                    listOf(MangaKey(selected.manga.api, selected.manga.language, selected.manga.title)),
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

    private suspend fun seedManga(
        dao: BackupDao,
        title: String,
        slug: String,
        withDownload: Boolean,
    ): SeededManga {
        val manga =
            SavedMangaEntity(
                api = "azora",
                language = "ar",
                url = "https://source.example/manga/$slug",
                imageUrl = "https://images.example/$slug-cover.webp",
                title = title,
                description = "$title description",
                status = "Ongoing",
                rating = "4.8",
                genres = listOf("action", "fantasy"),
                savedTimestamp = 100,
                lastOpenTimestamp = 200,
                isLiked = true,
                isWatchingNow = true,
            )
        val mangaId = dao.insertMangaRow(manga)
        val chapter =
            SavedChapterEntity(
                mangaId = mangaId,
                name = "Chapter 1",
                number = "1",
                url = "https://source.example/chapter/$slug-1",
                date = LocalDate(2026, 7, 18),
                isDownloaded = withDownload,
                isBookmarked = true,
                isRead = true,
                lastReadDate = 900,
                localImagePaths = emptyList(),
            )
        val chapterId = dao.insertChapterRow(chapter)
        sourceProgress.save(chapter.url, 7)
        dao.insertHistoryRow(
            HistoryItemD(
                api = manga.api,
                language = manga.language,
                mangaId = mangaId,
                mangaUrl = manga.url,
                mangaTitle = manga.title,
                mangaImageUrl = manga.imageUrl,
                chapterUrl = chapter.url,
                chapterTitle = chapter.name,
                isDownloaded = withDownload,
                lastReadDate = LocalDateTime(2026, 7, 18, 12, 0),
                lastReadPage = 7,
                totalPages = 20,
            ),
        )
        if (withDownload) writeOnePageCbz(mangaId, chapterId)
        return SeededManga(manga.copy(id = mangaId), chapter.copy(id = chapterId))
    }

    private fun writeOnePageCbz(
        mangaId: Long,
        chapterId: Long,
    ) {
        val reader = DefaultCbzReader(sourceFs, TestDispatchers)
        val path = reader.cbzPath(mangaId, chapterId)
        sourceFs.fileSystem().createDirectories(checkNotNull(path.parent))
        sourceFs.fileSystem().sink(path).buffer().use { sink ->
            BackupZipWriter(sink).apply {
                writeEntryBytes("001.jpg", "image-bytes".encodeToByteArray())
                finish()
            }
        }
    }

    private fun repository(
        db: MangaDatabase,
        fs: AppFileSystem,
        progress: ReadProgressRepository,
    ) = BackupRepositoryImpl(
        backupDao = db.backupDao(),
        readProgress = progress,
        appFileSystem = fs,
        dispatchers = TestDispatchers,
        cbzReader = DefaultCbzReader(fs, TestDispatchers),
        chapterDownloadDao = db.chapterDownloadingDao(),
        notificationDao = db.notificationDao(),
        appVersion = "1.0.0",
        platformName = "test",
    )

    private fun inMemoryDatabase(): MangaDatabase =
        Room
            .inMemoryDatabaseBuilder<MangaDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Unconfined)
            .build()

    private fun <T> AppResult<T>.success(): T =
        when (this) {
            is AppResult.Success -> value
            is AppResult.Failure -> error("Expected success, got $error")
        }

    private data class SeededManga(
        val manga: SavedMangaEntity,
        val chapter: SavedChapterEntity,
    )

    private class MemoryReadProgress : ReadProgressRepository {
        private val values = mutableMapOf<String, Int>()

        override suspend fun save(
            chapterUrl: String,
            pageIndex: Int,
        ) {
            values[chapterUrl] = pageIndex
        }

        override suspend fun load(chapterUrl: String): Int? = values[chapterUrl]

        override suspend fun clear(chapterUrl: String) {
            values.remove(chapterUrl)
        }
    }

    private class TempAppFileSystem(
        label: String,
    ) : AppFileSystem {
        private val root: Path =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "kira-backup-$label-${Random.nextLong().toString().trimStart('-')}"

        override val filesDir: Path = root / "files"
        override val cacheDir: Path = root / "cache"

        override fun fileSystem(): FileSystem = FileSystem.SYSTEM

        fun cleanUp() = fileSystem().deleteRecursively(root, mustExist = false)
    }

    private data object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }
}
