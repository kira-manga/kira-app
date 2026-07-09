package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.storage.DataStoreHelper
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.manga.kira.presentation.features.settings.domain.SettingsRepository as LegacySettingsRepository

/**
 * Regression test for the converter half of the ledger-size invariant ([ChapterDownloadEntity]
 * KDoc): `compressExistingDownloads` rewrites a chapter dir (loose pages -> one much smaller WebP
 * CBZ) and must refresh the `chapter_downloads` row's `sizeBytes` to the new archive size — before
 * this fix it only rewrote `localImagePaths`, so Details kept displaying the stale pre-conversion
 * loose-pages size forever (the startup reconcile only back-fills rows whose size is 0).
 *
 * Disk-backed on purpose (desktopTest): real temp-dir pages, a [CbzWriter] fake that writes a real
 * archive of a known byte size and deletes the sources (the interface contract), and the real
 * `folderSize` walk. The best-effort guard (walk failure -> no size write, convert still succeeds)
 * is exercised by `CompressExistingDownloadsTest`, whose AppFileSystem fake throws.
 */
class CompressExistingDownloadsSizeRefreshTest {
    private val fs = FileSystem.SYSTEM
    private lateinit var root: Path
    private lateinit var appFs: AppFileSystem

    @BeforeTest
    fun setUp() {
        root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "kira-size-refresh-${Random.nextLong().toULong()}"
        fs.createDirectories(root / "files")
        appFs =
            object : AppFileSystem {
                override val filesDir: Path = root / "files"
                override val cacheDir: Path = root / "cache"

                override fun fileSystem(): FileSystem = fs
            }
    }

    @AfterTest
    fun tearDown() {
        if (fs.exists(root)) fs.deleteRecursively(root)
    }

    private val testDispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
        }

    /** Writes [pageBytes] loose page files into the chapter dir and returns the seeded row. */
    private fun seedLooseChapter(
        chapterId: Long,
        mangaId: Long,
        pageBytes: List<Int>,
    ): SavedChapterEntity {
        val dir = appFs.chapterDir(mangaId, chapterId)
        fs.createDirectories(dir)
        val paths =
            pageBytes.mapIndexed { i, bytes ->
                val page = dir / "page_${i + 1}.webp"
                fs.write(page) { write(ByteArray(bytes)) }
                page.toString()
            }
        return SavedChapterEntity(
            id = chapterId,
            mangaId = mangaId,
            name = "ch$chapterId",
            number = "$chapterId",
            url = "https://example/$chapterId",
            isDownloaded = true,
            localImagePaths = paths,
        )
    }

    /**
     * Contract-faithful [CbzWriter] fake: writes a REAL archive of `cbzBytes[chapterId]` bytes at
     * the conventional `chapter_<id>.cbz` location and deletes the source pages, so the post-convert
     * `folderSize` walk sees exactly what production sees — the new archive alone.
     */
    private inner class SizedCbzWriter(
        private val cbzBytes: Map<Long, Int>,
    ) : CbzWriter {
        override suspend fun createCbz(
            imagePaths: List<Path>,
            mangaId: Long,
            chapterId: Long,
            quality: Int,
        ): Path = error("not used by compressExistingDownloads")

        override suspend fun createCbzWithSplitting(
            imagePaths: List<Path>,
            mangaId: Long,
            chapterId: Long,
            quality: Int,
            maxHeight: Int,
            maxMemoryBytes: Long,
        ): Path {
            val cbz = appFs.chapterDir(mangaId, chapterId) / "chapter_$chapterId.cbz"
            fs.write(cbz) { write(ByteArray(cbzBytes.getValue(chapterId))) }
            imagePaths.forEach { fs.delete(it, mustExist = false) }
            return cbz
        }
    }

    private class DownloadedServingChapterDao(
        private val rows: List<SavedChapterEntity>,
    ) : FakeChapterDao() {
        val pathRewrites = mutableListOf<Pair<Long, List<String>>>()

        override suspend fun getAllDownloadedChapters(): List<SavedChapterEntity> = rows

        override suspend fun updateChapterLocalPaths(
            chapterId: Long,
            paths: List<String>,
        ) {
            pathRewrites += chapterId to paths
        }
    }

    private class SizeRecordingDownloadDao : FakeChapterDownloadDao() {
        val sizeWrites = mutableListOf<Pair<Long, Long>>()

        override suspend fun updateSize(
            id: Long,
            sizeBytes: Long,
        ) {
            sizeWrites += id to sizeBytes
        }
    }

    private object InertMangaDao : MangaDao {
        override suspend fun getMangaById(mangaId: Long) = null

        override fun getAllChapterMetricsFlow() = error("unused")

        override suspend fun updateManga(manga: SavedMangaEntity): Int = error("unused")

        override suspend fun update(manga: SavedMangaEntity) = error("unused")

        override fun getAllSavedMangaFlow() = error("unused")

        override suspend fun getApiByMangaId(mangaId: Long): String? = error("unused")

        override suspend fun updateLastOpenTimestamp(
            mangaId: Long,
            timestamp: Long,
        ) = error("unused")

        override suspend fun getIdByApiAndTitle(
            api: String,
            title: String,
        ): Long? = error("unused")

        override suspend fun getMangaByApi(api: String) = error("unused")

        override suspend fun getMangaIdsByApi(api: String): List<Long> = error("unused")
    }

    private fun repo(
        chapterDao: DownloadedServingChapterDao,
        writer: CbzWriter,
        downloadDao: SizeRecordingDownloadDao,
    ): SettingsRepositoryImpl =
        SettingsRepositoryImpl(
            legacy =
                LegacySettingsRepository(
                    prefsHelper = SharedPrefsHelper(MapSettings()),
                    ds = DataStoreHelper(MapSettings()),
                    fs = appFs,
                ),
            dispatchers = testDispatchers,
            dataStore = DataStoreHelper(MapSettings()),
            chapterDao = chapterDao,
            cbzWriter = writer,
            mangaDao = InertMangaDao,
            chapterDownloadDao = downloadDao,
            appFileSystem = appFs,
        )

    @Test
    fun conversion_refreshes_the_stale_ledger_size_to_the_new_archive_size() =
        runTest {
            // 70 000 bytes of loose pages; the "downloaded" ledger row would carry ~70 000 stale.
            val chapterDao =
                DownloadedServingChapterDao(listOf(seedLooseChapter(chapterId = 1, mangaId = 7, pageBytes = listOf(40_000, 30_000))))
            val downloadDao = SizeRecordingDownloadDao()
            val cbzSize = 12_345

            val result = repo(chapterDao, SizedCbzWriter(mapOf(1L to cbzSize)), downloadDao).compressExistingDownloads()

            assertTrue(result.isSuccess)
            assertEquals(
                listOf(1L to cbzSize.toLong()),
                downloadDao.sizeWrites,
                "the ledger row is refreshed to the new archive size, not left at the loose-pages size",
            )
            // The readable/downloaded semantics are untouched: only the paths column was rewritten
            // (FakeChapterDao's every other mutator is a recorded no-op — nothing else was reached).
            val cbzPath = appFs.chapterDir(7, 1) / "chapter_1.cbz"
            assertEquals(listOf(1L to listOf(cbzPath.toString())), chapterDao.pathRewrites)
            assertEquals(cbzSize.toLong(), fs.metadata(cbzPath).size)
        }

    @Test
    fun each_chapter_size_write_is_keyed_by_its_own_chapter_id() =
        runTest {
            val chapterDao =
                DownloadedServingChapterDao(
                    listOf(
                        seedLooseChapter(chapterId = 1, mangaId = 7, pageBytes = listOf(50_000)),
                        seedLooseChapter(chapterId = 2, mangaId = 9, pageBytes = listOf(60_000)),
                    ),
                )
            val downloadDao = SizeRecordingDownloadDao()

            val result =
                repo(
                    chapterDao,
                    SizedCbzWriter(mapOf(1L to 11_111, 2L to 22_222)),
                    downloadDao,
                ).compressExistingDownloads()

            assertTrue(result.isSuccess)
            assertEquals(
                listOf(1L to 11_111L, 2L to 22_222L),
                downloadDao.sizeWrites,
                "each write is scoped to its own (manga, chapter) via the unique chapterId — never by url",
            )
        }
}
