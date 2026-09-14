package me.manga.kira.data.repository

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.filters.FilterSelections
import me.manga.kira.domain.model.home.FeaturedManga
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Test-only inherited lifecycle and real-Room/filesystem setup for the ownership regressions. */
open class ChapterOwnershipFixture {
    protected lateinit var root: Path
    protected lateinit var appFs: AppFileSystem
    protected lateinit var db: MangaDatabase
    protected val fs = FileSystem.SYSTEM
    protected val mangaA = manga("a")
    protected val mangaB = manga("b")
    protected val dispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = Dispatchers.Default
            override val mainImmediate: CoroutineDispatcher = Dispatchers.Default
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val io: CoroutineDispatcher = Dispatchers.Default
            override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
        }

    @BeforeTest
    fun open() {
        root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "kira-chapter-owner-${Random.nextLong().toULong()}"
        appFs =
            object : AppFileSystem {
                override val filesDir: Path = root / "files"
                override val cacheDir: Path = root / "cache"

                override fun fileSystem(): FileSystem = fs
            }
        fs.createDirectories(appFs.filesDir)
        db = openDatabase()
    }

    @AfterTest
    fun close() {
        db.close()
        fs.deleteRecursively(root)
    }

    protected fun openDatabase(): MangaDatabase =
        Room
            .databaseBuilder<MangaDatabase>(name = (root / "identity.db").toString())
            .setDriver(ForeignKeysOnDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .build()

    protected suspend fun seed(
        manga: Manga,
        urls: List<String> = listOf(CHAPTER_OWNERSHIP_URL),
        bookmarked: Boolean = false,
    ): List<SavedChapterEntity> {
        val mangaId =
            db.libraryDeo().insertManga(
                SavedMangaEntity(
                    api = manga.api,
                    language = manga.language,
                    url = manga.url,
                    imageUrl = manga.coverUrl,
                    title = manga.title,
                    description = "",
                    status = "",
                    rating = null,
                    genres = emptyList(),
                    savedTimestamp = 1,
                    lastOpenTimestamp = 1,
                ),
            )
        assertTrue(mangaId > 0)
        val rows =
            urls.map {
                SavedChapterEntity(
                    mangaId = mangaId,
                    name = "shared",
                    number = "1",
                    url = it,
                    date = null,
                    isBookmarked = bookmarked,
                    isNew = true,
                    lastReadDate = 7,
                )
            }
        val ids = db.chapterDao().insertChapters(rows)
        assertTrue(ids.all { it > 0 })
        return rows.zip(ids) { chapter, id -> chapter.copy(id = id) }
    }

    protected suspend fun row(id: Long): SavedChapterEntity = assertNotNull(db.chapterDao().getChapterByIdSuspend(id))

    protected fun download(chapter: SavedChapterEntity) =
        ChapterDownloadEntity(
            number = chapter.number,
            chapterId = chapter.id,
            mangaId = chapter.mangaId,
            api = mangaA.api,
            mangaTitle = mangaA.title,
            url = chapter.url,
            state = DownloadingState.RUNNING,
            progress = 12,
        )

    protected fun extractedPage(chapter: SavedChapterEntity): Path =
        appFs.cacheDir / "cbz_extract" / chapter.mangaId.toString() / chapter.id.toString() / "page.webp"

    protected fun writeFile(
        path: Path,
        contents: String,
    ): Path {
        fs.createDirectories(assertNotNull(path.parent))
        fs.write(path) { writeUtf8(contents) }
        return path
    }

    protected fun manga(slug: String): Manga =
        Manga(
            "source",
            "en",
            "Same title",
            "https://owner.test/$slug",
            "",
            null,
            emptyList(),
        )
}

internal const val CHAPTER_OWNERSHIP_URL = "chapter/shared"

/** Mirrors the production factory's per-connection FK setting without opening its user database. */
private class ForeignKeysOnDriver(
    private val delegate: SQLiteDriver = BundledSQLiteDriver(),
) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection =
        delegate.open(fileName).also {
            it.execSQL("PRAGMA foreign_keys = ON")
        }
}

internal class OwnerPagesSource : MangaSourceClient {
    override val api: String = "source"
    val requests = mutableListOf<Pair<Manga, Chapter>>()

    override fun pages(
        manga: Manga,
        chapter: Chapter,
    ): Flow<AppResult<List<Page>>> {
        requests += manga to chapter
        return flowOf(AppResult.Success(listOf(Page("${manga.url}/network-page", emptyMap()))))
    }

    override suspend fun home(page: Int): AppResult<List<HomeFeedItem>> = error("unused")

    override suspend fun featured(page: Int): AppResult<List<FeaturedManga>> = error("unused")

    override suspend fun search(
        query: String,
        page: Int,
        filters: FilterSelections,
    ): AppResult<List<HomeFeedItem>> = error("unused")

    override suspend fun details(manga: Manga): AppResult<MangaDetails> = error("unused")
}

internal fun chapterOwnershipRegistry(source: MangaSourceClient): SourceRegistry =
    object : SourceRegistry {
        override fun get(api: String): MangaSourceClient = source

        override fun isConfigBacked(api: String): Boolean = true

        override fun descriptor(api: String): RuntimeSourceDescriptor? = null

        override fun genericDescriptors(): List<RuntimeSourceDescriptor> = emptyList()
    }
