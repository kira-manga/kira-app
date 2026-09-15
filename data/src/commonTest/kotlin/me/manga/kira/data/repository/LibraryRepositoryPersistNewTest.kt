package me.manga.kira.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.data.local.dao.LibraryDeo
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.data.local.entity.ChapterNotification
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

        override suspend fun insertChapters(chapters: List<SavedChapterEntity>): List<Long> {
            val firstId = inserted.size + 1L
            inserted += chapters
            savedUrls += chapters.map { it.url } // so a second call sees them as already-saved
            return chapters.indices.map { firstId + it }
        }

        var discoveryRows = emptyList<ChapterNotification>()
        var discoveryOwner: Pair<String, String>? = null
        var discoveryCandidates = emptyList<SavedChapterEntity>()

        override suspend fun persistChapterDiscoveries(
            api: String,
            mangaUrl: String,
            chapters: List<SavedChapterEntity>,
            expectedMangaId: Long?,
        ): List<ChapterNotification> {
            check(expectedMangaId == null)
            discoveryOwner = api to mangaUrl
            discoveryCandidates = chapters
            return discoveryRows
        }

        override suspend fun insertManga(manga: SavedMangaEntity): Long = 0L

        override suspend fun getDiscoveryManga(api: String, mangaUrl: String): SavedMangaEntity? = error("unused")

        override suspend fun insertDiscoveryNotifications(notifications: List<ChapterNotification>): List<Long> = error("unused")

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
        override suspend fun getIdByApiAndUrl(
            api: String,
            mangaUrl: String,
        ): Long? = if (api == "src" && mangaUrl == "m/naruto") id else null

        override suspend fun getIdByApiAndTitle(
            api: String,
            title: String,
        ): Long? = id

        override fun getAllChapterMetricsFlow(): Flow<List<MangaChapterMetrics>> = flowOf(emptyList())

        override suspend fun updateManga(manga: SavedMangaEntity): Int = 0
        override suspend fun toggleLiked(mangaId: Long) = error("unused")
        override suspend fun toggleWatchingNow(mangaId: Long) = error("unused")
        override suspend fun updateSavedCover(mangaId: Long, imageUrl: String) = error("unused")
        override suspend fun updateHistoryCover(mangaId: Long, mangaUrl: String, imageUrl: String) = error("unused")
        override suspend fun updateNotificationCover(mangaId: Long, imageUrl: String) = error("unused")

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
        fakeArtifactRuntime().ownership,
    )

    @Test
    fun persists_only_new_urls_flagged_isNew_and_reversed() =
        runTest {
            val deo = FakeLibraryDeo(seededUrls = listOf("c/1", "c/2"))
            val repo = repo(deo, mangaId = 7L)
            // Source ships newest-first: c/4 and c/3 are new, c/2/c/1 already saved.
            val result = repo.persistNewChapters("src", "m/naruto", listOf(ch("4"), ch("3"), ch("2"), ch("1")))

            assertTrue(result.isSuccess)
            assertEquals(2, deo.inserted.size, "only the two not-yet-saved chapters are inserted")
            assertTrue(deo.inserted.all { it.isNew }, "inserted chapters are flagged NEW")
            assertTrue(deo.inserted.all { it.fetchedAt > 0L }, "inserted chapters carry a discovery timestamp")
            assertTrue(deo.inserted.all { it.mangaId == 7L }, "inserted chapters carry the resolved mangaId")
            // reversed() so the newest-first input lands oldest-first (autoincrement id ascends with recency).
            assertEquals(listOf("c/3", "c/4"), deo.inserted.map { it.url })
        }

    @Test
    fun andNotify_delegatesExactParentAndCountsOnlyCommittedRows() =
        runTest {
            // This is a boundary/delegation test. Real Room concurrency and ownership are covered
            // in desktopTest, not by pretending these fake methods supply a transaction.
            val deo = FakeLibraryDeo(seededUrls = emptyList())
            val manga = Manga("src", "en", "Naruto", "m/naruto", "cover.jpg", null, emptyList())
            deo.discoveryRows = listOf(
                ChapterNotification(
                    id = 9, api = manga.api, language = manga.language, mangaId = 7,
                    mangaTitle = manga.title, mangaImageUrl = manga.coverUrl, mangaUrl = manga.url,
                    chapterId = 103, chapterNumber = "3", chapterUrl = "c/3",
                ),
            )
            // No api+title lookup is available; refresh must use the DAO's exact-parent boundary.
            val repo = repo(deo, mangaId = null)
            val result = repo.persistNewChaptersAndNotify(manga, listOf(ch("3"), ch("2"), ch("1")))

            assertEquals(me.manga.kira.core.result.AppResult.Success(1), result)
            assertEquals(manga.api to manga.url, deo.discoveryOwner)
            assertEquals(listOf("c/1", "c/2", "c/3"), deo.discoveryCandidates.map { it.url })
            assertTrue(deo.inserted.isEmpty(), "repository must not perform a separate chapter insertion")
        }

    @Test
    fun andNotify_emptyCommittedOutcomeDoesNotCountStaleCandidates() =
        runTest {
            val deo = FakeLibraryDeo(seededUrls = emptyList())
            val manga = Manga("src", "en", "Naruto", "m/naruto", "cover.jpg", null, emptyList())
            val result = repo(deo, mangaId = 7L).persistNewChaptersAndNotify(manga, listOf(ch("1")))

            assertEquals(me.manga.kira.core.result.AppResult.Success(0), result)
            assertEquals(listOf("c/1"), deo.discoveryCandidates.map { it.url })
            assertTrue(deo.inserted.isEmpty())
        }

    @Test
    fun is_idempotent_on_second_run() =
        runTest {
            val deo = FakeLibraryDeo(seededUrls = listOf("c/1"))
            val repo = repo(deo, mangaId = 7L)
            repo.persistNewChapters("src", "m/naruto", listOf(ch("2"), ch("1")))
            deo.inserted.clear()

            // Second refresh with the same list: c/2 is now saved, nothing new.
            val result = repo.persistNewChapters("src", "m/naruto", listOf(ch("2"), ch("1")))

            assertTrue(result.isSuccess)
            assertTrue(deo.inserted.isEmpty(), "a re-refresh inserts nothing (idempotent)")
        }

    @Test
    fun no_op_when_not_in_library() =
        runTest {
            val deo = FakeLibraryDeo(seededUrls = emptyList())
            val repo = repo(deo, mangaId = null) // exact api + URL lookup → null = not in library

            val result = repo.persistNewChapters("src", "m/naruto", listOf(ch("1")))

            assertEquals(0, (result as me.manga.kira.core.result.AppResult.Success).value)
            assertTrue(deo.inserted.isEmpty())
        }
}
