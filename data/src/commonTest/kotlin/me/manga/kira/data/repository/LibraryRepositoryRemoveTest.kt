package me.manga.kira.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.mangaDir
import me.manga.kira.presentation.features.home.data.ApiTitle
import me.manga.kira.presentation.features.library.data.MangaChapterMetrics
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bug #2 (2026-06-07): removing a manga from the library must purge BOTH its `chapter_downloads`
 * rows AND its on-disk downloaded files — otherwise the "downloaded" badge/size (read from
 * `chapter_downloads`) survives the removal, and the orphaned `manga/$id/` directory leaks.
 *
 * Exercises the REAL [LibraryDeo.removeMangaWithChapters] `@Transaction` default body (so the new
 * `removeAllDownloadsForManga` step is covered) plus a REAL [FileService] over an in-memory okio
 * [FakeFileSystem] (so `deleteMangaFiles` actually removes the seeded manga directory).
 */
class LibraryRepositoryRemoveTest {

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    /** In-memory [LibraryDeo] tracking saved_chapters + chapter_downloads + saved_manga rows. */
    private class FakeLibraryDeo : LibraryDeo {
        val chapters = mutableListOf<SavedChapterEntity>()
        val downloadMangaIds = mutableListOf<Long>() // stand-in for chapter_downloads rows by mangaId
        val mangaIds = mutableListOf<Long>()
        val historyUrlsRemoved = mutableListOf<String>()
        val notificationUrlsRemoved = mutableListOf<String>()

        override suspend fun insertManga(manga: SavedMangaEntity): Long = 0L
        override suspend fun getMangaIdByUrl(url: String): Long? = null
        override suspend fun getSavedChapterUrls(mangaId: Long): List<String> =
            chapters.filter { it.mangaId == mangaId }.map { it.url }
        override suspend fun insertChapters(chapters: List<SavedChapterEntity>) { this.chapters += chapters }
        override fun getSavedMangaApiTitleFlow(): Flow<List<ApiTitle>> = flowOf(emptyList())
        override suspend fun getMangaIdByTitle(title: String): Long? = null
        override suspend fun getMangaIdByApiAndTitle(api: String, title: String): Long? = null
        override suspend fun deleteMangaById(id: Long): Int { mangaIds.removeAll { it == id }; return 0 }
        override suspend fun removeAllChaptersForManga(mangaId: Long) { chapters.removeAll { it.mangaId == mangaId } }
        override suspend fun removeAllDownloadsForManga(mangaId: Long) { downloadMangaIds.removeAll { it == mangaId } }
        override suspend fun removeAllNotification(mangaId: Long) {}
        override suspend fun removeHistory(mangaId: Long) {}
        override suspend fun removeHistoryByUrl(mangaUrl: String) { historyUrlsRemoved += mangaUrl }
        override suspend fun removeNotificationsByUrl(mangaUrl: String) { notificationUrlsRemoved += mangaUrl }
        override suspend fun markChapterAsReadInternal(chapterId: Long) {}
        override suspend fun markNotificationReadInternal(chapterId: Long) {}
    }

    /** [MangaDao] mapping (api,title) -> id, and id -> a saved_manga row carrying a url. */
    private class FakeMangaDao(private val ids: Map<Pair<String, String>, Long>) : MangaDao {
        override fun getAllChapterMetricsFlow(): Flow<List<MangaChapterMetrics>> = flowOf(emptyList())
        override suspend fun updateManga(manga: SavedMangaEntity): Int = 0
        override suspend fun update(manga: SavedMangaEntity) {}
        override fun getAllSavedMangaFlow(): Flow<List<SavedMangaEntity>> = flowOf(emptyList())
        override suspend fun getApiByMangaId(mangaId: Long): String? = null
        override suspend fun updateLastOpenTimestamp(mangaId: Long, timestamp: Long) {}
        override suspend fun getIdByApiAndTitle(api: String, title: String): Long? = ids[api to title]
        override suspend fun getMangaById(mangaId: Long): SavedMangaEntity? = SavedMangaEntity(
            id = mangaId, api = "src", language = "en", url = "m/$mangaId", imageUrl = "",
            title = "t$mangaId", description = "", status = "", rating = null, genres = emptyList(),
        )
        override suspend fun getMangaByApi(api: String): List<SavedMangaEntity> = emptyList()
        override suspend fun getMangaIdsByApi(api: String): List<Long> = emptyList()
    }

    /**
     * Real okio [FileSystem.SYSTEM] over a unique temp root, so `FileService.deleteMangaFiles`
     * exercises genuine recursive deletion. (Avoids okio-fakefilesystem, whose version pins clash
     * with the okio core on the test runtime classpath.)
     */
    private class TempDirFs : AppFileSystem {
        val fs: FileSystem = FileSystem.SYSTEM
        private val root: Path =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "yami-remove-test-${Random.nextLong().toString().trimStart('-')}"
        override val filesDir: Path = root
        override val cacheDir: Path = root / "cache"
        override fun fileSystem(): FileSystem = fs
        fun cleanUp() = fs.deleteRecursively(root, mustExist = false)
    }

    /** Seed a non-empty manga directory so we can prove it gets deleted. */
    private fun TempDirFs.seedManga(id: Long) {
        val dir = mangaDir(id) / "chapter_100"
        fs.createDirectories(dir)
        fs.write(dir / "1.jpg") { writeUtf8("img") }
    }

    @Test
    fun removeFromLibrary_purges_download_rows_and_deletes_files() = runTest {
        val deo = FakeLibraryDeo().apply {
            mangaIds += 7L
            chapters += SavedChapterEntity(mangaId = 7L, url = "c/1", number = "1", name = "1")
            downloadMangaIds += 7L
        }
        val appFs = TempDirFs().apply { seedManga(7L) }
        val readProgress = RecordingReadProgressRepository()
        // An in-flight download of chapter 55 for manga 7 must be cancelled before the purge so the
        // engine can't write an orphan CBZ into the just-deleted manga directory (r5-dl-3).
        val downloads = FakeDownloadRepository()
        val repo = LibraryRepositoryImpl(
            FakeMangaDao(mapOf(("src" to "Naruto") to 7L)),
            deo,
            FakeChapterDao(),
            RecordingNotificationDao(),
            RecordingHistoryDao(),
            FakeChapterDownloadDao(activeByManga = mapOf(7L to listOf(55L))),
            downloads,
            FileService(appFs),
            readProgress,
            testDispatchers,
        )

        try {
            val result = repo.removeFromLibrary(api = "src", language = "en", title = "Naruto")

            assertTrue(result.isSuccess)
            assertEquals(listOf(55L to 7L), downloads.cancelledRunning, "in-flight download cancelled before purge")
            assertTrue(deo.chapters.none { it.mangaId == 7L }, "saved_chapters purged")
            assertTrue(deo.downloadMangaIds.isEmpty(), "chapter_downloads rows purged")
            assertFalse(appFs.fs.exists(appFs.mangaDir(7L)), "manga/\$id directory deleted from disk")
            assertEquals(listOf("m/7"), deo.historyUrlsRemoved, "history cleared by manga url")
            assertEquals(listOf("m/7"), deo.notificationUrlsRemoved, "notifications cleared by manga url")
            assertEquals(listOf("c/1"), readProgress.cleared, "resume position cleared for the manga's chapters")
        } finally {
            appFs.cleanUp()
        }
    }

    @Test
    fun removeAllFromLibrary_purges_each_manga_rows_and_files() = runTest {
        val deo = FakeLibraryDeo().apply {
            mangaIds += listOf(7L, 8L)
            chapters += SavedChapterEntity(mangaId = 7L, url = "a/1", number = "1", name = "1")
            chapters += SavedChapterEntity(mangaId = 8L, url = "b/1", number = "1", name = "1")
            downloadMangaIds += listOf(7L, 8L)
        }
        val appFs = TempDirFs().apply { seedManga(7L); seedManga(8L) }
        val readProgress = RecordingReadProgressRepository()
        val repo = LibraryRepositoryImpl(
            FakeMangaDao(mapOf(("src" to "A") to 7L, ("src" to "B") to 8L)),
            deo,
            FakeChapterDao(),
            RecordingNotificationDao(),
            RecordingHistoryDao(),
            FakeChapterDownloadDao(),
            FakeDownloadRepository(),
            FileService(appFs),
            readProgress,
            testDispatchers,
        )

        try {
            val result = repo.removeAllFromLibrary(
                listOf(MangaKey("src", "en", "A"), MangaKey("src", "en", "B")),
            )

            assertTrue(result.isSuccess)
            assertTrue(deo.chapters.isEmpty(), "all saved_chapters purged")
            assertTrue(deo.downloadMangaIds.isEmpty(), "all chapter_downloads rows purged")
            assertFalse(appFs.fs.exists(appFs.mangaDir(7L)))
            assertFalse(appFs.fs.exists(appFs.mangaDir(8L)))
            assertEquals(setOf("m/7", "m/8"), deo.historyUrlsRemoved.toSet(), "history cleared by url for each manga")
            assertEquals(setOf("m/7", "m/8"), deo.notificationUrlsRemoved.toSet(), "notifications cleared by url for each manga")
            assertEquals(setOf("a/1", "b/1"), readProgress.cleared.toSet(), "resume positions cleared for all chapters")
        } finally {
            appFs.cleanUp()
        }
    }

    @Test
    fun removeAllFromLibrary_returns_actual_purged_count_skipping_not_found_keys() = runTest {
        // #21: a key with no saved_manga row (already removed / never saved) must be SKIPPED and must
        // NOT inflate the returned count — so the "Removed N items" toast shows what was really
        // purged (2), not the selected-key count (3).
        val deo = FakeLibraryDeo().apply {
            mangaIds += listOf(7L, 8L)
            chapters += SavedChapterEntity(mangaId = 7L, url = "a/1", number = "1", name = "1")
            chapters += SavedChapterEntity(mangaId = 8L, url = "b/1", number = "1", name = "1")
            downloadMangaIds += listOf(7L, 8L)
        }
        val appFs = TempDirFs().apply { seedManga(7L); seedManga(8L) }
        val repo = LibraryRepositoryImpl(
            // Only "A" and "B" resolve to ids; "Ghost" is absent → skipped, not counted.
            FakeMangaDao(mapOf(("src" to "A") to 7L, ("src" to "B") to 8L)),
            deo,
            FakeChapterDao(),
            RecordingNotificationDao(),
            RecordingHistoryDao(),
            FakeChapterDownloadDao(),
            FakeDownloadRepository(),
            FileService(appFs),
            RecordingReadProgressRepository(),
            testDispatchers,
        )

        try {
            val result = repo.removeAllFromLibrary(
                listOf(
                    MangaKey("src", "en", "A"),
                    MangaKey("src", "en", "B"),
                    MangaKey("src", "en", "Ghost"), // not in library → must be skipped
                ),
            )

            assertEquals(2, result.getOrNull(), "count reflects only rows actually purged (2), not keys.size (3)")
        } finally {
            appFs.cleanUp()
        }
    }
}
