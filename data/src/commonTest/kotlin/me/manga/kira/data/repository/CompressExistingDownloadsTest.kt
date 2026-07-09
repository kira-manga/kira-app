package me.manga.kira.data.repository

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.ChapterIdUrl
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import me.manga.kira.presentation.features.settings.domain.SettingsRepository as LegacySettingsRepository

/**
 * Unit tests for [SettingsRepositoryImpl.compressExistingDownloads] (CBZ bulk-convert Slice A).
 *
 * Matches the existing `:data` fake-based commonTest style (kotlin-test + an inline test
 * [DispatcherProvider] pinned to `Dispatchers.Unconfined`). Drives the real impl over a
 * [FakeChapterDao] (in-memory rows + recorded `updateChapterLocalPaths` calls) and a
 * [FakeCbzWriter] (records the chapters it was asked to pack, returns a stub archive path, and
 * can be told to throw for a specific chapter to exercise the per-chapter isolation).
 *
 * Covers:
 *  - already-`.cbz` chapters are skipped (no writer call, no path rewrite),
 *  - loose-image chapters are packed and get `updateChapterLocalPaths` with the cbz path,
 *  - a throwing chapter is absorbed (the batch still returns success and the OTHER chapters
 *    convert normally).
 */
class CompressExistingDownloadsTest {
    private val testDispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
            override val unconfined: CoroutineDispatcher get() = Dispatchers.Unconfined
        }

    /** Records each [createCbzWithSplitting] call; returns a deterministic archive path per chapter. */
    private class FakeCbzWriter(
        /** chapterIds the writer should throw for (simulates iOS NotImplementedError / a bad page). */
        private val throwFor: Set<Long> = emptySet(),
        /** Invoked after each successful pack — lets a test request a mid-run stop deterministically. */
        private val afterPack: (Long) -> Unit = {},
    ) : CbzWriter {
        val packed = mutableListOf<Long>()

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
            if (chapterId in throwFor) throw NotImplementedError("CbzWriter unsupported on this platform")
            packed += chapterId
            afterPack(chapterId)
            return "manga/$mangaId/chapter_$chapterId/chapter_$chapterId.cbz".toPath()
        }
    }

    /**
     * In-memory [ChapterDao] fake. Only [getAllDownloadedChapters] + [updateChapterLocalPaths] are
     * exercised by the engine; the remaining DAO surface throws so an accidental new dependency is
     * caught loudly. Records the `(chapterId, paths)` pairs passed to [updateChapterLocalPaths].
     */
    private class FakeChapterDao(
        private val downloaded: List<SavedChapterEntity>,
    ) : ChapterDao {
        val pathRewrites = mutableListOf<Pair<Long, List<String>>>()

        override suspend fun getAllDownloadedChapters(): List<SavedChapterEntity> = downloaded

        override suspend fun updateChapterLocalPaths(
            chapterId: Long,
            paths: List<String>,
        ) {
            pathRewrites += chapterId to paths
        }

        // --- unused surface --------------------------------------------------------------------
        override fun getChaptersByMangaId(mangaId: Long): Flow<List<SavedChapterEntity>> = flowOf(emptyList())

        override suspend fun insertChaptersSafely(chapters: List<SavedChapterEntity>): List<Long> = error("unused")

        override suspend fun insertChapters(chapters: List<SavedChapterEntity>): List<Long> = error("unused")

        override suspend fun insertAll(chapters: List<SavedChapterEntity>) = error("unused")

        override suspend fun getChapterIdByUrl(url: String): Long? = error("unused")

        override suspend fun getChapterIdsByUrlsBatch(urls: List<String>): List<Long> = error("unused")

        override suspend fun getChapterIdUrlPairsBatch(urls: List<String>): List<ChapterIdUrl> = error("unused")

        override suspend fun getChapterIdUrlPairsForMangaBatch(
            mangaId: Long,
            urls: List<String>,
        ): List<ChapterIdUrl> = error("unused")

        override suspend fun markChapterDownloaded(chapterId: Long) = error("unused")

        override suspend fun toggleChapterBookmark(chapterId: Long) = error("unused")

        override suspend fun markChapterAsRead(
            chapterId: Long,
            currentTime: Long,
        ) = error("unused")

        override suspend fun markChapterIsNew(chapterId: Long) = error("unused")

        override fun getChapterById(chapterId: Long): Flow<SavedChapterEntity?> = flowOf(null)

        override fun getChapterByUrl(url: String): Flow<SavedChapterEntity?> = flowOf(null)

        override suspend fun getChapterByIdSuspend(chapterId: Long): SavedChapterEntity? = error("unused")

        override suspend fun markChaptersNotDownloaded(
            ids: List<Long>,
            emptyList: List<String>,
        ) = error("unused")

        override suspend fun deleteChapterById(chapterId: Long) = error("unused")

        override suspend fun markChaptersReadBatch(chapterIds: List<Long>) = error("unused")

        override suspend fun toggleChaptersReadBatch(chapterIds: List<Long>) = error("unused")

        override suspend fun toggleChaptersBookmarkBatch(chapterIds: List<Long>) = error("unused")

        override suspend fun getChaptersByMangaIdR(mangaId: Long): List<SavedChapterEntity> = error("unused")

        override suspend fun updateChapter(chapter: SavedChapterEntity) = error("unused")
    }

    /**
     * In-memory [MangaDao] fake. The convert engine only reaches [getMangaById] (for the
     * CbzConversionProgress "Current:" manga-title block); it returns a stub manga whose title
     * encodes the id so a missing lookup is obvious. The rest of the DAO surface throws so an
     * accidental new dependency is caught loudly.
     */
    private object FakeMangaDao : MangaDao {
        override suspend fun getMangaById(mangaId: Long): SavedMangaEntity? =
            SavedMangaEntity(
                id = mangaId,
                api = "MangaDex",
                language = "en",
                url = "https://example/manga/$mangaId",
                imageUrl = "https://example/cover/$mangaId.jpg",
                title = "Manga $mangaId",
                description = "",
                status = "",
                rating = null,
                genres = emptyList(),
            )

        // --- unused surface --------------------------------------------------------------------
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

        override suspend fun getMangaByApi(api: String): List<SavedMangaEntity> = error("unused")

        override suspend fun getMangaIdsByApi(api: String): List<Long> = error("unused")
    }

    private fun chapter(
        id: Long,
        paths: List<String>,
    ): SavedChapterEntity =
        SavedChapterEntity(
            id = id,
            mangaId = id * 10,
            name = "ch$id",
            number = "$id",
            url = "https://example/$id",
            isDownloaded = true,
            localImagePaths = paths,
        )

    /**
     * Minimal [AppFileSystem] fake. The cache-walk plumbing is never invoked here, so
     * [fileSystem] throws — which also exercises the ledger size-refresh guard for real: the
     * post-convert `folderSize` walk fails, the write is skipped best-effort, and the conversion
     * still counts as success (the disk-backed refresh itself is covered by
     * `CompressExistingDownloadsSizeRefreshTest` in desktopTest).
     */
    private object FakeAppFileSystem : AppFileSystem {
        override val filesDir: Path = "files".toPath()
        override val cacheDir: Path = "cache".toPath()

        override fun fileSystem(): FileSystem = error("filesystem not used by compressExistingDownloads")
    }

    /**
     * A real legacy [LegacySettingsRepository] over in-memory settings — the impl's ctor requires
     * it (for the 5 toggle flows + cache-size derivation), but `compressExistingDownloads` never
     * reaches it, so an in-memory [MapSettings]-backed instance is a sufficient stand-in.
     */
    private fun legacySettings(): LegacySettingsRepository =
        LegacySettingsRepository(
            prefsHelper = SharedPrefsHelper(MapSettings()),
            ds = DataStoreHelper(MapSettings()),
            fs = FakeAppFileSystem,
        )

    private fun repo(
        dao: ChapterDao,
        writer: CbzWriter,
        // B4: default fake returns null for getDownloadByChapter → no chapter has an active download
        // row, so every loose chapter still converts (existing assertions unchanged).
        downloadDao: ChapterDownloadDao = FakeChapterDownloadDao(),
    ): SettingsRepositoryImpl =
        SettingsRepositoryImpl(
            legacy = legacySettings(),
            dispatchers = testDispatchers,
            dataStore = DataStoreHelper(MapSettings()),
            chapterDao = dao,
            cbzWriter = writer,
            mangaDao = FakeMangaDao,
            chapterDownloadDao = downloadDao,
            appFileSystem = FakeAppFileSystem,
        )

    /** [FakeChapterDownloadDao] whose [getDownloadByChapter] serves a per-chapter download state (B4). */
    private class StateServingDownloadDao(
        private val stateByChapter: Map<Long, DownloadingState>,
    ) : FakeChapterDownloadDao() {
        override suspend fun getDownloadByChapter(chapterId: Long): ChapterDownloadEntity? =
            stateByChapter[chapterId]?.let { state ->
                ChapterDownloadEntity(
                    id = chapterId,
                    number = "1",
                    chapterId = chapterId,
                    mangaId = chapterId * 10,
                    api = "src",
                    url = "https://example/$chapterId",
                    state = state,
                    progress = 0,
                )
            }
    }

    @Test
    fun looseImageChapters_arePackedAndRewritten() =
        runTest {
            val dao =
                FakeChapterDao(
                    listOf(
                        chapter(1, listOf("manga/10/chapter_1/page_0001.webp", "manga/10/chapter_1/page_0002.webp")),
                        chapter(2, listOf("manga/20/chapter_2/page_0001.webp")),
                    ),
                )
            val writer = FakeCbzWriter()

            val result = repo(dao, writer).compressExistingDownloads()

            assertTrue(result.isSuccess)
            assertEquals(listOf(1L, 2L), writer.packed)
            assertEquals(2, dao.pathRewrites.size)
            assertEquals(1L, dao.pathRewrites[0].first)
            assertEquals(listOf("manga/10/chapter_1/chapter_1.cbz"), dao.pathRewrites[0].second)
            assertEquals(2L, dao.pathRewrites[1].first)
            assertEquals(listOf("manga/20/chapter_2/chapter_2.cbz"), dao.pathRewrites[1].second)
        }

    @Test
    fun alreadyCbzAndEmptyChapters_areSkipped() =
        runTest {
            val dao =
                FakeChapterDao(
                    listOf(
                        chapter(1, listOf("manga/10/chapter_1/chapter_1.cbz")), // already archived → skip
                        chapter(2, emptyList()), // no pages → skip
                        chapter(3, listOf("manga/30/chapter_3/page_0001.webp")), // loose → convert
                    ),
                )
            val writer = FakeCbzWriter()

            val result = repo(dao, writer).compressExistingDownloads()

            assertTrue(result.isSuccess)
            assertEquals(listOf(3L), writer.packed)
            assertEquals(1, dao.pathRewrites.size)
            assertEquals(3L, dao.pathRewrites.single().first)
            // The already-cbz chapter is never rewritten.
            assertFalse(dao.pathRewrites.any { it.first == 1L })
        }

    @Test
    fun throwingChapter_isAbsorbed_batchStillSucceeds() =
        runTest {
            val dao =
                FakeChapterDao(
                    listOf(
                        chapter(1, listOf("manga/10/chapter_1/page_0001.webp")),
                        chapter(2, listOf("manga/20/chapter_2/page_0001.webp")), // writer throws here
                        chapter(3, listOf("manga/30/chapter_3/page_0001.webp")),
                    ),
                )
            val writer = FakeCbzWriter(throwFor = setOf(2L))

            val result = repo(dao, writer).compressExistingDownloads()

            // Batch still succeeds despite chapter 2 throwing (e.g. iOS NotImplementedError).
            assertTrue(result.isSuccess)
            // Chapters 1 and 3 still converted; chapter 2 skipped (no path rewrite).
            assertEquals(listOf(1L, 3L), writer.packed)
            assertEquals(listOf(1L, 3L), dao.pathRewrites.map { it.first })
            assertFalse(dao.pathRewrites.any { it.first == 2L })
        }

    // GAP-SET-16 — after the run completes, the progress StateFlow holds a terminal Completed
    // snapshot: not converting, total + converted counts populated, not stopped, no error.
    @Test
    fun progress_terminalCompletedSnapshot_afterRun() =
        runTest {
            val dao =
                FakeChapterDao(
                    listOf(
                        chapter(1, listOf("manga/10/chapter_1/page_0001.webp")),
                        chapter(2, listOf("manga/20/chapter_2/page_0001.webp")),
                    ),
                )
            val repo = repo(dao, FakeCbzWriter())

            repo.compressExistingDownloads()

            val progress = repo.observeCbzConversion().first()
            assertFalse(progress.isConverting)
            assertFalse(progress.wasStopped)
            assertEquals(2, progress.totalChapters)
            assertEquals(2, progress.convertedChapters)
            assertNotNull(progress.successMessage)
        }

    // GAP-SET-16 — a stop requested mid-run (after the first chapter packs) is honored on the next
    // loop iteration: chapter 1 converts, chapters 2 & 3 are skipped, and the progress StateFlow
    // holds a terminal Stopped snapshot carrying the converted count. (`compressExistingDownloads`
    // resets the flag at its start, so the stop must be requested DURING the run, mirroring native
    // where `startConversion` clears `shouldStopConversion` before the loop.)
    @Test
    fun stopConversion_midRun_isHonored_andEmitsStoppedTerminal() =
        runTest {
            val dao =
                FakeChapterDao(
                    listOf(
                        chapter(1, listOf("manga/10/chapter_1/page_0001.webp")),
                        chapter(2, listOf("manga/20/chapter_2/page_0001.webp")),
                        chapter(3, listOf("manga/30/chapter_3/page_0001.webp")),
                    ),
                )
            lateinit var repo: SettingsRepositoryImpl
            // Request the stop right after chapter 1 packs; the loop's pre-chapter check then trips.
            val writer = FakeCbzWriter(afterPack = { id -> if (id == 1L) repo.stopConversion() })
            repo = repo(dao, writer)

            val result = repo.compressExistingDownloads()

            assertTrue(result.isSuccess)
            // Only chapter 1 converted; 2 & 3 skipped after the stop.
            assertEquals(listOf(1L), writer.packed)
            assertEquals(listOf(1L), dao.pathRewrites.map { it.first })
            val progress = repo.observeCbzConversion().first()
            assertFalse(progress.isConverting)
            assertTrue(progress.wasStopped)
            assertEquals(3, progress.totalChapters)
            assertEquals(1, progress.convertedChapters)
        }

    // ---- B4: compressor ↔ background-finalize race guard ----

    @Test
    fun chaptersWithActiveDownloadRow_areSkipped() =
        runTest {
            // B4: the manual compressor and the background finalize could otherwise write the SAME
            // chapter dir's `.cbz.part` + loose pages concurrently (the engine's finalizeSemaphore is
            // private to the engine, invisible here). A chapter with an active download row — all four
            // of QUEUED / RUNNING / DOWNLOADED / COMPRESSING — is the ENGINE's to finalize; skip it.
            val dao =
                FakeChapterDao(
                    listOf(
                        chapter(1, listOf("manga/10/chapter_1/page_0001.webp")), // QUEUED → skip
                        chapter(2, listOf("manga/20/chapter_2/page_0001.webp")), // RUNNING → skip
                        chapter(3, listOf("manga/30/chapter_3/page_0001.webp")), // DOWNLOADED → skip
                        chapter(4, listOf("manga/40/chapter_4/page_0001.webp")), // COMPRESSING → skip
                        chapter(5, listOf("manga/50/chapter_5/page_0001.webp")), // no row → convert
                    ),
                )
            val writer = FakeCbzWriter()
            val downloadDao =
                StateServingDownloadDao(
                    mapOf(
                        1L to DownloadingState.QUEUED,
                        2L to DownloadingState.RUNNING,
                        3L to DownloadingState.DOWNLOADED,
                        4L to DownloadingState.COMPRESSING,
                    ),
                )

            val result = repo(dao, writer, downloadDao).compressExistingDownloads()

            assertTrue(result.isSuccess)
            assertEquals(listOf(5L), writer.packed)
            assertEquals(listOf(5L), dao.pathRewrites.map { it.first })
        }

    @Test
    fun chaptersWithTerminalDownloadRow_stillConvert() =
        runTest {
            // Terminal rows are NOT the engine's: SUCCESS is the normal "downloaded long ago, bulk-convert
            // now" case, and a FAILED row's leftover loose pages are safe to pack (no live writer).
            val dao =
                FakeChapterDao(
                    listOf(
                        chapter(1, listOf("manga/10/chapter_1/page_0001.webp")), // SUCCESS row → convert
                        chapter(2, listOf("manga/20/chapter_2/page_0001.webp")), // FAILED row → convert
                    ),
                )
            val writer = FakeCbzWriter()
            val downloadDao =
                StateServingDownloadDao(
                    mapOf(1L to DownloadingState.SUCCESS, 2L to DownloadingState.FAILED),
                )

            val result = repo(dao, writer, downloadDao).compressExistingDownloads()

            assertTrue(result.isSuccess)
            assertEquals(listOf(1L, 2L), writer.packed)
            assertEquals(listOf(1L, 2L), dao.pathRewrites.map { it.first })
        }
}
