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
 * Verifies the native-parity add-to-library persist path (bug fix, 2026-06-01):
 * [LibraryRepositoryImpl.addToLibrary] must persist the manga row AND its chapters via
 * [LibraryDeo.saveMangaWithChapters] (previously it dropped chapters and only inserted the manga
 * row), and it must apply the native `.reversed()` insertion-order detail.
 *
 * Exercises the REAL [LibraryDeo.saveMangaWithChapters] `@Transaction` default body (an in-memory
 * fake implements only the abstract DAO members; the default method runs unchanged), so the
 * insert-then-resolve-id + new-URL-only filtering is covered too. Matches the existing fake-based
 * `:data` commonTest style (kotlin-test + a test [DispatcherProvider]).
 */
class LibraryRepositoryAddTest {

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
    }

    /** In-memory [LibraryDeo]; the `@Transaction` default methods run their real bodies over these. */
    private class FakeLibraryDeo : LibraryDeo {
        var nextId = 1L
        val mangaByUrl = mutableMapOf<String, Long>()
        val chapters = mutableListOf<SavedChapterEntity>()

        override suspend fun insertManga(manga: SavedMangaEntity): Long {
            // IGNORE-conflict semantics: a second insert for the same url is a no-op (returns -1).
            val existing = mangaByUrl[manga.url]
            if (existing != null) return -1L
            val id = nextId++
            mangaByUrl[manga.url] = id
            return id
        }

        override suspend fun getMangaIdByUrl(url: String): Long? = mangaByUrl[url]

        override suspend fun getSavedChapterUrls(mangaId: Long): List<String> =
            chapters.filter { it.mangaId == mangaId }.map { it.url }

        override suspend fun insertChapters(chapters: List<SavedChapterEntity>) {
            this.chapters += chapters
        }

        override fun getSavedMangaApiTitleFlow(): Flow<List<ApiTitle>> = flowOf(emptyList())
        override suspend fun getMangaIdByTitle(title: String): Long? = null
        override suspend fun getMangaIdByApiAndTitle(api: String, title: String): Long? = null
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

    /** A [FileService] over the system filesystem; the add path never deletes files, so it is inert. */
    private fun fileService(): FileService = FileService(
        object : AppFileSystem {
            override val filesDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
            override val cacheDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
            override fun fileSystem(): FileSystem = FileSystem.SYSTEM
        },
    )

    /** Minimal [MangaDao]; the add path never touches it. */
    private class FakeMangaDao : MangaDao {
        override fun getAllChapterMetricsFlow(): Flow<List<MangaChapterMetrics>> = flowOf(emptyList())
        override suspend fun updateManga(manga: SavedMangaEntity): Int = 0
        override suspend fun update(manga: SavedMangaEntity) {}
        override fun getAllSavedMangaFlow(): Flow<List<SavedMangaEntity>> = flowOf(emptyList())
        override suspend fun getApiByMangaId(mangaId: Long): String? = null
        override suspend fun updateLastOpenTimestamp(mangaId: Long, timestamp: Long) {}
        override suspend fun getIdByApiAndTitle(api: String, title: String): Long? = null
        override suspend fun getMangaById(mangaId: Long): SavedMangaEntity? = null
        override suspend fun getMangaByApi(api: String): List<SavedMangaEntity> = emptyList()
        override suspend fun getMangaIdsByApi(api: String): List<Long> = emptyList()
    }

    private fun manga() = Manga(
        api = "src",
        language = "en",
        title = "Naruto",
        url = "https://x/naruto",
        coverUrl = "",
        rating = null,
        genres = emptyList(),
    )

    private fun chapter(number: String, url: String) = Chapter(
        number = number,
        name = "Ch $number",
        url = url,
        date = null,
        isDownloaded = false,
        isBookmarked = false,
    )

    @Test
    fun addToLibrary_persists_the_manga_and_its_chapters() = runTest {
        val deo = FakeLibraryDeo()
        val repo = LibraryRepositoryImpl(FakeMangaDao(), deo, FakeChapterDao(), RecordingNotificationDao(), RecordingHistoryDao(), FakeChapterDownloadDao(), FakeDownloadRepository(), fileService(), RecordingReadProgressRepository(), testDispatchers)
        // Source ships newest-first.
        val chapters = listOf(chapter("3", "c/3"), chapter("2", "c/2"), chapter("1", "c/1"))

        val result = repo.addToLibrary(manga(), chapters)

        assertTrue(result.isSuccess, "add must succeed")
        assertEquals(1, deo.mangaByUrl.size, "the manga row must be persisted")
        assertEquals(3, deo.chapters.size, "ALL chapters must be persisted (not dropped)")
        // Native `.reversed()` detail: the newest-first source list lands oldest-first in Room.
        assertEquals(listOf("c/1", "c/2", "c/3"), deo.chapters.map { it.url })
        // Every chapter was stamped with the resolved mangaId by saveMangaWithChapters.
        val mangaId = deo.mangaByUrl.values.first()
        assertTrue(deo.chapters.all { it.mangaId == mangaId }, "chapters must carry the resolved mangaId")
    }

    @Test
    fun addToLibrary_is_idempotent_and_inserts_only_new_chapter_urls() = runTest {
        val deo = FakeLibraryDeo()
        val repo = LibraryRepositoryImpl(FakeMangaDao(), deo, FakeChapterDao(), RecordingNotificationDao(), RecordingHistoryDao(), FakeChapterDownloadDao(), FakeDownloadRepository(), fileService(), RecordingReadProgressRepository(), testDispatchers)
        repo.addToLibrary(manga(), listOf(chapter("1", "c/1"), chapter("2", "c/2")))

        // Re-add with one overlapping + one new chapter: manga is a no-op, only the new URL inserts.
        val result = repo.addToLibrary(manga(), listOf(chapter("2", "c/2"), chapter("3", "c/3")))

        assertTrue(result.isSuccess)
        assertEquals(1, deo.mangaByUrl.size, "re-add must not duplicate the manga row")
        assertEquals(setOf("c/1", "c/2", "c/3"), deo.chapters.map { it.url }.toSet())
        assertEquals(3, deo.chapters.size, "only the truly-new chapter URL is added on re-add")
    }

    @Test
    fun addToLibrary_with_empty_chapters_persists_only_the_manga_row() = runTest {
        val deo = FakeLibraryDeo()
        val repo = LibraryRepositoryImpl(FakeMangaDao(), deo, FakeChapterDao(), RecordingNotificationDao(), RecordingHistoryDao(), FakeChapterDownloadDao(), FakeDownloadRepository(), fileService(), RecordingReadProgressRepository(), testDispatchers)

        val result = repo.addToLibrary(manga(), emptyList())

        assertTrue(result.isSuccess)
        assertEquals(1, deo.mangaByUrl.size)
        assertTrue(deo.chapters.isEmpty(), "no chapters to persist when the caller has none")
    }
}
