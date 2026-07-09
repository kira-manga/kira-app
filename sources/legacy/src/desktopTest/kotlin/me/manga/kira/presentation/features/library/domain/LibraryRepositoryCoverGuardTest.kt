package me.manga.kira.presentation.features.library.domain

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.FileSystem
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for the dead-source cover-blanking bug (2026-07 source-lifecycle hardening):
 * a library refresh against an unknown/retired api used to resolve to EmptyMangaRepository, whose
 * empty-Success (imageUrl = "") flowed into [LibraryRepository.updateMangaImageUrlEverywhere] and
 * wiped the saved cover across saved_manga/history/notifications. The fix is two-layered — the
 * Android LibraryRefreshWorker now uses the strict `getOrRepoByName` lookup and skips blank covers,
 * and this repository-level guard rejects a blank URL no matter who calls. This test pins the
 * guard: dead api + existing cover ⇒ cover remains unchanged.
 */
class LibraryRepositoryCoverGuardTest {
    private lateinit var db: MangaDatabase
    private lateinit var repository: LibraryRepository

    @BeforeTest
    fun open() {
        db =
            Room
                .inMemoryDatabaseBuilder<MangaDatabase>()
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.Default)
                .build()
        repository =
            LibraryRepository(
                mangaDao = db.mangaDao(),
                chapterDao = db.chapterDao(),
                libraryDeo = db.libraryDeo(),
                notificationDao = db.notificationDao(),
                historyDao = db.historyDao(),
                fileService = FileService(NoopAppFileSystem),
            )
    }

    @AfterTest
    fun close() = db.close()

    @Test
    fun blank_image_url_does_not_overwrite_the_existing_cover() =
        runTest {
            val id = db.libraryDeo().insertManga(manga(imageUrl = COVER))
            db.historyDao().insertHistory(history(mangaId = id, imageUrl = COVER))

            // The exact write a dead-api refresh used to make (EmptyMangaRepository's imageUrl = "").
            repository.updateMangaImageUrlEverywhere(id, "")

            assertEquals(COVER, db.mangaDao().getMangaById(id)?.imageUrl, "saved cover must survive")
            assertEquals(
                COVER,
                db.historyDao().getHistoryItemByMangaUrl(MANGA_URL)?.mangaImageUrl,
                "history cover must survive",
            )
        }

    @Test
    fun non_blank_image_url_still_updates_everywhere() =
        runTest {
            val id = db.libraryDeo().insertManga(manga(imageUrl = COVER))
            db.historyDao().insertHistory(history(mangaId = id, imageUrl = COVER))
            val newCover = "https://azora.example/new-cover.jpg"

            repository.updateMangaImageUrlEverywhere(id, newCover)

            assertEquals(newCover, db.mangaDao().getMangaById(id)?.imageUrl)
            assertEquals(newCover, db.historyDao().getHistoryItemByMangaUrl(MANGA_URL)?.mangaImageUrl)
        }

    private fun manga(imageUrl: String) =
        SavedMangaEntity(
            id = 0,
            api = "RetiredSource",
            language = "(AR)",
            url = MANGA_URL,
            imageUrl = imageUrl,
            title = "Solo Leveling",
            description = "desc",
            status = "Ongoing",
            rating = null,
            genres = listOf("action"),
            savedTimestamp = 100,
            lastOpenTimestamp = 100,
            isLiked = false,
            isWatchingNow = false,
        )

    private fun history(
        mangaId: Long,
        imageUrl: String,
    ) = HistoryItemD(
        api = "RetiredSource",
        language = "(AR)",
        mangaId = mangaId,
        mangaUrl = MANGA_URL,
        mangaTitle = "Solo Leveling",
        mangaImageUrl = imageUrl,
        chapterUrl = "$MANGA_URL/chapter-1",
        chapterTitle = "Chapter 1",
        isDownloaded = false,
        localImagePaths = emptyList(),
        lastReadDate = LocalDateTime(2026, 7, 1, 12, 0),
        lastReadPage = 1,
        totalPages = 20,
    )

    /** Never exercised — [FileService] is a required ctor param but no test path deletes files. */
    private object NoopAppFileSystem : AppFileSystem {
        override val filesDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "cover-guard-test-files"
        override val cacheDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "cover-guard-test-cache"

        override fun fileSystem(): FileSystem = FileSystem.SYSTEM
    }

    private companion object {
        const val COVER = "https://azora.example/cover.jpg"
        const val MANGA_URL = "https://retired.example/manga/solo-leveling"
    }
}
