package me.manga.kira.data.repository
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal fun downloadRecoveryTest(block: suspend DownloadRecoveryFixture.() -> Unit) =
    runTest {
        val fixture = DownloadRecoveryFixture()
        try {
            fixture.block()
        } finally {
            fixture.close()
        }
    }

/** Only this recovery suite owns these temporary files and the file-backed Room database. */
internal class DownloadRecoveryFixture {
    private val root = Files.createTempDirectory("kira-download-recovery-").toString().toPath()
    private var nextManga = 0
    val fs: FileSystem = FileSystem.SYSTEM
    val appFileSystem: AppFileSystem =
        object : AppFileSystem {
            override val filesDir: Path = root / "files"
            override val cacheDir: Path = root / "cache"

            override fun fileSystem(): FileSystem = fs
        }
    var db: MangaDatabase = openDatabase()
        private set
    val dao: ChapterDownloadDao get() = db.chapterDownloadingDao()
    var artifactRuntime = ArtifactTestRuntime(db, appFileSystem)
        private set
    var restartCalls = 0
        private set
    private val legacy =
        object : FakeDownloadRepository() {
            override suspend fun reconcileInterruptedDownloads() {
                restartCalls++
                // Exercise the real existing SQL reset, not WorkManager or its scheduling behavior.
                dao.reEnqueueInterrupted()
            }

            override suspend fun deleteDownload(chapterId: Long) {
                dao.deleteByChapterId(chapterId)
            }
        }

    fun close() {
        db.close()
        fs.deleteRecursively(root)
    }

    fun reopen() {
        db.close()
        db = openDatabase()
        artifactRuntime = ArtifactTestRuntime(db, appFileSystem)
    }

    fun actions(
        downloadDao: ChapterDownloadDao = dao,
        fileSystem: AppFileSystem = appFileSystem,
        engine: me.manga.kira.presentation.features.download.domain.clean.DownloadRepository = legacy,
    ) = DownloadsActionRepositoryImpl(
        legacy = engine,
        chapterDownloadDao = downloadDao,
        chapterDao = db.chapterDao(),
        appFileSystem = fileSystem,
        artifacts = artifactRuntime.ownership,
    )

    suspend fun seed(
        state: DownloadingState = DownloadingState.SUCCESS,
        sizeBytes: Long = 100L,
        isDownloaded: Boolean = false,
        mangaId: Long? = null,
    ): RetainedDownload {
        val number = ++nextManga
        val chapter =
            SavedChapterEntity(
                mangaId = mangaId ?: seedManga(number),
                name = "Chapter $number",
                number = number.toString(),
                url = "https://example.test/chapter/$number",
                date = null,
                isDownloaded = isDownloaded,
                isBookmarked = true,
                lastReadPage = 3,
            )
        val chapterId = db.backupDao().insertChapterRow(chapter)
        val contents = appFileSystem.createRetainedFiles(chapter.mangaId, chapterId)
        val saved = chapter.copy(id = chapterId, localImagePaths = contents.keys.toList())
        db.backupDao().updateChapterRow(saved)
        val download = downloadRow(saved, state, sizeBytes)
        return RetainedDownload(saved, download.copy(id = dao.insert(download)), contents)
    }

    suspend fun saved(original: RetainedDownload): SavedChapterEntity =
        assertNotNull(db.chapterDao().getChapterByIdSuspend(original.saved.id))

    suspend fun download(original: RetainedDownload) = assertNotNull(dao.getDownloadByChapter(original.saved.id))

    fun assertRetainedFiles(original: RetainedDownload) {
        original.contents.forEach { (path, content) ->
            assertEquals(content, fs.read(path.toPath()) { readUtf8() })
        }
    }

    suspend fun reconcileAfterFileReads(beforeRepair: suspend (ChapterDownloadEntity) -> Unit): Map<Long, Boolean> {
        val results = mutableMapOf<Long, Boolean>()
        val realDao = dao
        val racingDao =
            object : ChapterDownloadDao by realDao {
                override suspend fun repairCompletedDownloadFlag(
                    expected: ChapterDownloadEntity,
                    expectedPaths: List<String>,
                ): Boolean {
                    // Production reaches this seam after reading all files. Mutate real persisted
                    // rows now, then invoke the generated transaction; never emulate its outcome.
                    beforeRepair(expected)
                    return realDao.repairCompletedDownloadFlag(expected, expectedPaths).also {
                        results[expected.chapterId] = it
                    }
                }
            }
        assertTrue(actions(downloadDao = racingDao).reconcileInterrupted().isSuccess)
        return results
    }

    private fun openDatabase(): MangaDatabase =
        Room
            .databaseBuilder<MangaDatabase>(name = (root / "recovery.db").toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()

    private suspend fun seedManga(number: Int): Long =
        db.backupDao().insertMangaRow(
            SavedMangaEntity(
                api = "test",
                language = "en",
                url = "https://example.test/manga/$number",
                imageUrl = "https://example.test/cover.jpg",
                title = "Manga $number",
                description = "",
                status = "ongoing",
                rating = null,
                genres = emptyList(),
                savedTimestamp = 1L,
                lastOpenTimestamp = 2L,
            ),
        )
}

internal data class RetainedDownload(
    val saved: SavedChapterEntity,
    val download: ChapterDownloadEntity,
    val contents: Map<String, String>,
)

private fun AppFileSystem.createRetainedFiles(
    mangaId: Long,
    chapterId: Long,
): Map<String, String> {
    val fs = fileSystem()
    val chapterDir = chapterDir(mangaId, chapterId)
    fs.createDirectories(chapterDir)
    // Recovery checks readable bytes, not image/CBZ decoding; payloads test that boundary.
    return (1..2).associate { page ->
        val path = chapterDir / "$page.jpg"
        val content = "retained chapter $chapterId page $page"
        fs.write(path) { writeUtf8(content) }
        path.toString() to content
    }
}

private fun downloadRow(
    saved: SavedChapterEntity,
    state: DownloadingState,
    sizeBytes: Long,
) = ChapterDownloadEntity(
    number = saved.number,
    chapterId = saved.id,
    mangaId = saved.mangaId,
    api = "test",
    mangaTitle = "Manga ${saved.number}",
    url = saved.url,
    state = state,
    progress = 100,
    sizeBytes = sizeBytes,
)
