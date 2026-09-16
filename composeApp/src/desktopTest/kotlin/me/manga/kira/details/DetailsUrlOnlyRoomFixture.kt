package me.manga.kira.details

import androidx.lifecycle.ViewModelStore
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.download.artifacts.ChapterArtifactRecovery
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.repository.LibraryRepositoryImpl
import me.manga.kira.data.repository.ReadProgressRepositoryImpl
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.domain.service.FileService
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.assertTrue

/** Real file-backed Room and production repositories; only source and membership timing are controlled. */
internal class DetailsUrlOnlyRoomFixture(
    private val dispatcher: CoroutineDispatcher,
) {
    private val root = Files.createTempDirectory("kira-details-persistence-").toString().toPath()
    private val store = ViewModelStore()
    val fileSystem =
        object : AppFileSystem {
            override val filesDir: Path = root / "files"
            override val cacheDir: Path = root / "cache"

            override fun fileSystem(): FileSystem = FileSystem.SYSTEM
        }
    val dispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = dispatcher
            override val mainImmediate: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
            override val unconfined: CoroutineDispatcher = dispatcher
        }
    var db: MangaDatabase = openDatabase()
        private set
    private fun newArtifacts(): ChapterArtifacts = ChapterArtifacts(
        db.chapterArtifactDao(), ChapterArtifactRecovery(db.chapterArtifactDao(), db.chapterArtifactCommitDao(), fileSystem,
            me.manga.kira.platform.media.DesktopPageMediaInspector()),
    )
    var artifacts = newArtifacts()
        private set
    val library =
        DeferredDetailsMembership(
            LibraryRepositoryImpl(
                mangaDao = db.mangaDao(),
                libraryDeo = db.libraryDeo(),
                chapterDao = db.chapterDao(),
                notificationDao = db.notificationDao(),
                historyDao = db.historyDao(),
                chapterDownloadDao = db.chapterDownloadingDao(),
                downloadRepository = UnusedDetailsDownloadEngine,
                fileService = FileService(fileSystem),
                readProgress = ReadProgressRepositoryImpl(MapSettings()),
                dispatchers = dispatchers,
                artifacts = artifacts,
            ),
        )
    val source = DetailsRoomSource()
    val vm = createDetailsRoomViewModel(
        DetailsRoomEnvironment(db, fileSystem, artifacts, dispatchers), library, source,
    ).also { store.put("details", it) }

    suspend fun seed(
        manga: Manga,
        chapter: Chapter,
    ): SavedChapterEntity {
        val mangaId = db.backupDao().insertMangaRow(savedManga(manga))
        assertTrue(mangaId > 0)
        val row =
            SavedChapterEntity(
                mangaId = mangaId,
                name = chapter.name,
                number = chapter.number,
                url = chapter.url,
                date = null,
                isRead = true,
                isBookmarked = true,
                lastReadDate = 123L,
            )
        val id = db.backupDao().insertChapterRow(row)
        assertTrue(id > 0)
        return row.copy(id = id)
    }

    fun clearViewModel() = store.clear()

    fun reopen() {
        db.close()
        db = openDatabase()
        artifacts = newArtifacts()
    }

    fun close() {
        store.clear()
        db.close()
        FileSystem.SYSTEM.deleteRecursively(root)
    }

    private fun openDatabase(): MangaDatabase =
        Room
            .databaseBuilder<MangaDatabase>(name = (root / "details.db").toString())
            .setDriver(BundledSQLiteDriver())
            // Keep Room queries, VM work and invalidations on the same deterministic test scheduler.
            .setQueryCoroutineContext(dispatcher)
            .build()
}

/** Delays ONLY the UI membership observer; every write still executes the production repository. */
internal class DeferredDetailsMembership(
    private val real: LibraryRepository,
) : LibraryRepository by real {
    val ready = CompletableDeferred<Unit>()
    val observedKeys = mutableListOf<MangaKey>()
    val offeredParents = mutableListOf<Pair<String, String>>()
    val results = mutableListOf<AppResult<Int>>()

    override fun observeIsInLibrary(
        api: String,
        language: String,
        title: String,
    ): Flow<Boolean> =
        flow {
            observedKeys += MangaKey(api, language, title)
            ready.await()
            emitAll(real.observeIsInLibrary(api, language, title))
        }

    override suspend fun persistNewChapters(
        api: String,
        mangaUrl: String,
        fetched: List<Chapter>,
    ): AppResult<Int> {
        offeredParents += api to mangaUrl
        return real.persistNewChapters(api, mangaUrl, fetched).also { results += it }
    }
}

internal class DetailsRoomSource : MangaDetailsRepository {
    val requests = mutableListOf<Manga>()
    val answers = mutableMapOf<String, AppResult<MangaDetails>>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> {
        requests += manga
        val answer = answers.getValue(manga.url)
        gates[manga.url]?.await()
        return answer
    }
}

internal fun roomManga(slug: String): Manga =
    Manga("source", "en", "Manga $slug", "https://details.test/$slug", "cover-$slug", null, emptyList())

internal fun roomChapter(
    manga: Manga,
    number: String,
): Chapter = Chapter(number, "Chapter $number", "${manga.url}/$number", null, false, false)

internal fun roomDetails(
    manga: Manga,
    chapters: List<Chapter>,
): MangaDetails =
    MangaDetails(
        api = manga.api,
        language = manga.language,
        title = manga.title,
        url = manga.url,
        coverUrl = manga.coverUrl,
        description = "Description",
        author = "Author",
        rating = "4",
        status = "Ongoing",
        genres = manga.genres,
        chapters = chapters,
    )

private fun savedManga(manga: Manga): SavedMangaEntity =
    SavedMangaEntity(
        api = manga.api,
        language = manga.language,
        title = manga.title,
        url = manga.url,
        imageUrl = manga.coverUrl,
        description = "Saved description",
        status = "Ongoing",
        rating = null,
        genres = manga.genres,
        savedTimestamp = 1L,
        lastOpenTimestamp = 1L,
    )
