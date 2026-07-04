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
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.presentation.features.home.data.ApiTitle
import me.manga.kira.presentation.features.library.data.MangaChapterMetrics
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #3: [LibraryRepositoryImpl.persistNewChapters] persists only refresh-discovered chapters not yet
 * saved, flagged `isNew = true` with a `fetchedAt` timestamp, oldest-first, and is idempotent.
 */
class LibraryRepositoryPersistNewTest {
    private val testDispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
            override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Unconfined
            override val io: CoroutineDispatcher = Dispatchers.Unconfined
            override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
        }

    private class FakeLibraryDeo(
        seededUrls: List<String>,
    ) : LibraryDeo {
        val inserted = mutableListOf<SavedChapterEntity>()
        private val savedUrls = seededUrls.toMutableList()

        override suspend fun getSavedChapterUrls(mangaId: Long): List<String> = savedUrls.toList()

        override suspend fun insertChapters(chapters: List<SavedChapterEntity>) {
            inserted += chapters
            savedUrls += chapters.map { it.url } // so a second call sees them as already-saved
        }

        override suspend fun insertManga(manga: SavedMangaEntity): Long = 0L

        override suspend fun getMangaIdByUrl(url: String): Long? = null

        override fun getSavedMangaApiTitleFlow(): Flow<List<ApiTitle>> = flowOf(emptyList())

        override suspend fun getMangaIdByTitle(title: String): Long? = null

        override suspend fun getMangaIdByApiAndTitle(
            api: String,
            title: String,
        ): Long? = null

        override suspend fun deleteMangaById(id: Long): Int = 0

        override suspend fun removeAllChaptersForManga(mangaId: Long) {}

        override suspend fun removeAllDownloadsForManga(mangaId: Long) {}

        override suspend fun removeAllNotification(mangaId: Long) {}

        override suspend fun removeHistory(mangaId: Long) {}

        override suspend fun removeHistoryByUrl(mangaUrl: String) {}

        override suspend fun removeNotificationsByUrl(mangaUrl: String) {}

        override suspend fun markChapterAsReadInternal(chapterId: Long) {}

        override suspend fun markNotificationReadInternal(chapterId: Long) {}
    }

    private class FakeMangaDao(
        private val id: Long?,
    ) : MangaDao {
        override suspend fun getIdByApiAndTitle(
            api: String,
            title: String,
        ): Long? = id

        override fun getAllChapterMetricsFlow(): Flow<List<MangaChapterMetrics>> = flowOf(emptyList())

        override suspend fun updateManga(manga: SavedMangaEntity): Int = 0

        override suspend fun update(manga: SavedMangaEntity) {}

        override fun getAllSavedMangaFlow(): Flow<List<SavedMangaEntity>> = flowOf(emptyList())

        override suspend fun getApiByMangaId(mangaId: Long): String? = null

        override suspend fun updateLastOpenTimestamp(
            mangaId: Long,
            timestamp: Long,
        ) {}

        override suspend fun getMangaById(mangaId: Long): SavedMangaEntity? = null

        override suspend fun getMangaByApi(api: String): List<SavedMangaEntity> = emptyList()

        override suspend fun getMangaIdsByApi(api: String): List<Long> = emptyList()
    }

    private fun fileService(): FileService =
        FileService(
            object : AppFileSystem {
                override val filesDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
                override val cacheDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

                override fun fileSystem(): FileSystem = FileSystem.SYSTEM
            },
        )

    private fun ch(n: String) = Chapter(number = n, name = "Ch $n", url = "c/$n", date = null, isDownloaded = false, isBookmarked = false)

    private fun repo(
        deo: LibraryDeo,
        mangaId: Long?,
    ) = LibraryRepositoryImpl(
        FakeMangaDao(mangaId),
        deo,
        FakeChapterDao(),
        RecordingNotificationDao(),
        RecordingHistoryDao(),
        FakeChapterDownloadDao(),
        FakeDownloadRepository(),
        fileService(),
        RecordingReadProgressRepository(),
        testDispatchers,
    )

    @Test
    fun persists_only_new_urls_flagged_isNew_and_reversed() =
        runTest {
            val deo = FakeLibraryDeo(seededUrls = listOf("c/1", "c/2"))
            val repo = repo(deo, mangaId = 7L)
            // Source ships newest-first: c/4 and c/3 are new, c/2/c/1 already saved.
            val result = repo.persistNewChapters("src", "en", "Naruto", listOf(ch("4"), ch("3"), ch("2"), ch("1")))

            assertTrue(result.isSuccess)
            assertEquals(2, deo.inserted.size, "only the two not-yet-saved chapters are inserted")
            assertTrue(deo.inserted.all { it.isNew }, "inserted chapters are flagged NEW")
            assertTrue(deo.inserted.all { it.fetchedAt > 0L }, "inserted chapters carry a discovery timestamp")
            assertTrue(deo.inserted.all { it.mangaId == 7L }, "inserted chapters carry the resolved mangaId")
            // reversed() so the newest-first input lands oldest-first (autoincrement id ascends with recency).
            assertEquals(listOf("c/3", "c/4"), deo.inserted.map { it.url })
        }

    @Test
    fun andNotify_writesNotificationRowPerNewChapter_andNoneOnRefresh() =
        runTest {
            val deo = FakeLibraryDeo(seededUrls = listOf("c/1"))
            val chapterDao = FakeChapterDao(mapOf("c/2" to 102L, "c/3" to 103L))
            val notif = RecordingNotificationDao()
            val repo =
                LibraryRepositoryImpl(
                    FakeMangaDao(7L),
                    deo,
                    chapterDao,
                    notif,
                    RecordingHistoryDao(),
                    FakeChapterDownloadDao(),
                    FakeDownloadRepository(),
                    fileService(),
                    RecordingReadProgressRepository(),
                    testDispatchers,
                )
            val manga =
                Manga(
                    api = "src",
                    language = "en",
                    title = "Naruto",
                    url = "m/naruto",
                    coverUrl = "cover.jpg",
                    rating = null,
                    genres = emptyList(),
                )

            val result = repo.persistNewChaptersAndNotify(manga, listOf(ch("3"), ch("2"), ch("1")))

            assertTrue(result.isSuccess)
            assertEquals(setOf("c/2", "c/3"), notif.inserted.map { it.chapterUrl }.toSet(), "one notification per genuinely-new chapter")
            assertTrue(notif.inserted.all { it.mangaId == 7L }, "notifications carry the resolved mangaId")
            assertTrue(notif.inserted.all { it.chapterId > 0L }, "notifications carry the resolved chapterId")
            assertTrue(notif.inserted.all { it.mangaImageUrl == "cover.jpg" }, "cover maps to mangaImageUrl")

            notif.inserted.clear()
            // Re-refresh: c/2 + c/3 are now saved → nothing new → no duplicate notifications.
            repo.persistNewChaptersAndNotify(manga, listOf(ch("3"), ch("2"), ch("1")))
            assertTrue(notif.inserted.isEmpty(), "no duplicate notifications on a re-refresh")
        }

    @Test
    fun andNotify_neverAttachesToAnotherMangasChapterRow() =
        runTest {
            // 2026-07 audit: the DAO's notify-pass resolution is mangaId-scoped (ChapterDao KDoc) so a
            // chapter url legally reused under a DIFFERENT manga can't win and attach the notification
            // to the wrong manga's row. Here c/2's only saved_chapters row (id 999) belongs to manga 8,
            // while the refresh runs for manga 7: the scoped lookup must resolve nothing for c/2 (no
            // wrong-row notification), while c/3 — owned by manga 7 — is notified normally.
            val deo = FakeLibraryDeo(seededUrls = listOf("c/1"))
            val chapterDao =
                FakeChapterDao(
                    idsByUrl = mapOf("c/2" to 999L, "c/3" to 103L),
                    mangaIdByUrl = mapOf("c/2" to 8L, "c/3" to 7L),
                )
            val notif = RecordingNotificationDao()
            val repo =
                LibraryRepositoryImpl(
                    FakeMangaDao(7L),
                    deo,
                    chapterDao,
                    notif,
                    RecordingHistoryDao(),
                    FakeChapterDownloadDao(),
                    FakeDownloadRepository(),
                    fileService(),
                    RecordingReadProgressRepository(),
                    testDispatchers,
                )
            val manga =
                Manga(
                    api = "src",
                    language = "en",
                    title = "Naruto",
                    url = "m/naruto",
                    coverUrl = "cover.jpg",
                    rating = null,
                    genres = emptyList(),
                )

            val result = repo.persistNewChaptersAndNotify(manga, listOf(ch("3"), ch("2"), ch("1")))

            assertTrue(result.isSuccess)
            assertEquals(
                listOf("c/3"),
                notif.inserted.map { it.chapterUrl },
                "the foreign-manga url is skipped, not misattached",
            )
            assertTrue(
                notif.inserted.none { it.chapterId == 999L },
                "manga 8's chapter row never receives manga 7's notification",
            )
        }

    @Test
    fun is_idempotent_on_second_run() =
        runTest {
            val deo = FakeLibraryDeo(seededUrls = listOf("c/1"))
            val repo = repo(deo, mangaId = 7L)
            repo.persistNewChapters("src", "en", "Naruto", listOf(ch("2"), ch("1")))
            deo.inserted.clear()

            // Second refresh with the same list: c/2 is now saved, nothing new.
            val result = repo.persistNewChapters("src", "en", "Naruto", listOf(ch("2"), ch("1")))

            assertTrue(result.isSuccess)
            assertTrue(deo.inserted.isEmpty(), "a re-refresh inserts nothing (idempotent)")
        }

    @Test
    fun no_op_when_not_in_library() =
        runTest {
            val deo = FakeLibraryDeo(seededUrls = emptyList())
            val repo = repo(deo, mangaId = null) // getIdByApiAndTitle → null = not in library

            val result = repo.persistNewChapters("src", "en", "Naruto", listOf(ch("1")))

            assertEquals(0, (result as me.manga.kira.core.result.AppResult.Success).value)
            assertTrue(deo.inserted.isEmpty())
        }
}
