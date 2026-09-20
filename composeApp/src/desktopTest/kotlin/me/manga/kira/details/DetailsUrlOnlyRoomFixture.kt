package me.manga.kira.details

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.download.artifacts.ChapterArtifactRecovery
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.ReaderProgressConstraints
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.repository.library.LibraryOwnerTransactions
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.platform.download.DownloadOperationExclusion
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.assertTrue

/** Real file-backed Room/repositories; source, finite test policy and scoped membership timing are controlled. */
internal class DetailsUrlOnlyRoomFixture(
    private val dispatcher: CoroutineDispatcher,
) {
    private val root = Files.createTempDirectory("kira-details-persistence-").toString().toPath()
    private val store = ViewModelStore()
    private var viewModelCleared = false
    val operations = DownloadOperationExclusion()
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
    private var runtime = DetailsUrlOnlyLibraryRuntime(db, fileSystem, artifacts, dispatchers)
    val owners: LibraryOwnerTransactions get() = runtime.owners
    val library = DeferredDetailsMembership(runtime.repository)
    val source = DetailsRoomSource()
    val vm =
        createDetailsRoomViewModel(
            DetailsRoomEnvironment(db, fileSystem, artifacts, dispatchers, owners = owners, operations = operations),
            library,
            source,
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

    fun clearViewModel() {
        store.clear()
        viewModelCleared = true
    }

    fun reopen() {
        check(viewModelCleared) { "The old ViewModel must be cleared before its database is closed" }
        db.close()
        db = openDatabase()
        artifacts = newArtifacts()
        runtime = DetailsUrlOnlyLibraryRuntime(db, fileSystem, artifacts, dispatchers)
    }

    fun close() {
        store.clear()
        db.close()
        FileSystem.SYSTEM.deleteRecursively(root)
    }

    private fun openDatabase(): MangaDatabase =
        Room
            .databaseBuilder<MangaDatabase>(name = (root / "details.db").toString())
            .addCallback(ReaderProgressConstraints)
            .setDriver(DetailsUrlOnlyForeignKeysDriver(BundledSQLiteDriver()))
            // Keep Room queries, VM work and invalidations on the same deterministic test scheduler.
            .setQueryCoroutineContext(dispatcher)
            .build()
}

/** Delays scoped membership authority, never substitutes its result; every refresh is real Room work. */
internal class DeferredDetailsMembership(
    private val real: LibraryRepository,
) : LibraryRepository by real {
    val ready = CompletableDeferred<Unit>()
    val observedKeys = mutableListOf<WorkLocator>()
    val refreshBatches = mutableListOf<List<LibraryRefreshRequest>>()
    val notifyRequests = mutableListOf<Boolean>()
    val offeredParents: List<WorkLocator> get() = refreshBatches.flatten().map { it.fetched.requested }
    val results = mutableListOf<AppResult<List<LibraryRefreshReceipt>>>()

    override fun observeMembership(work: WorkLocator): Flow<AppResult<SavedWorkIdentity?>> =
        flow {
            observedKeys += work
            ready.await()
            emitAll(real.observeMembership(work))
        }

    override suspend fun refresh(
        requests: List<LibraryRefreshRequest>,
        notify: Boolean,
    ): AppResult<List<LibraryRefreshReceipt>> {
        refreshBatches += requests.toList()
        notifyRequests += notify
        return real.refresh(requests, notify).also { results += it }
    }
}

internal class DetailsRoomSource : MangaDetailsRepository {
    val requests = mutableListOf<Manga>()
    val cancelledRequests = mutableListOf<Manga>()
    val answers = mutableMapOf<String, AppResult<MangaDetails>>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> {
        requests += manga
        val answer = answers.getValue(manga.url)
        return try {
            gates[manga.url]?.await()
            answer
        } catch (cancelled: CancellationException) {
            cancelledRequests += manga
            throw cancelled
        }
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
