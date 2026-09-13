package me.manga.kira.presentation.details

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.repository.AdultContentClassifier
import me.manga.kira.domain.repository.AnalyticsPort
import me.manga.kira.domain.repository.ChapterDeletionRepository
import me.manga.kira.domain.repository.ChapterIdResolver
import me.manga.kira.domain.repository.ChapterNewBadgeRepository
import me.manga.kira.domain.repository.CompressionDeferralRepository
import me.manga.kira.domain.repository.ConnectivityRepository
import me.manga.kira.domain.repository.DownloadsActionRepository
import me.manga.kira.domain.repository.DownloadsRepository
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.repository.SavedMangaDetailsRepository
import me.manga.kira.domain.usecase.analytics.LogMangaOpenUseCase
import me.manga.kira.domain.usecase.connectivity.ObserveConnectivityUseCase
import me.manga.kira.domain.usecase.details.ClearChapterNewUseCase
import me.manga.kira.domain.usecase.details.DeleteChapterUseCase
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.domain.usecase.details.IsAdultContentUseCase
import me.manga.kira.domain.usecase.details.ObserveSavedMangaDetailsUseCase
import me.manga.kira.domain.usecase.details.ResolveChapterIdUseCase
import me.manga.kira.domain.usecase.downloads.CancelAllDownloadsUseCase
import me.manga.kira.domain.usecase.downloads.CancelChapterDownloadUseCase
import me.manga.kira.domain.usecase.downloads.CancelDownloadUseCase
import me.manga.kira.domain.usecase.downloads.CancelRunningDownloadUseCase
import me.manga.kira.domain.usecase.downloads.DeleteDownloadedChapterUseCase
import me.manga.kira.domain.usecase.downloads.EnqueueAllChaptersDownloadUseCase
import me.manga.kira.domain.usecase.downloads.EnqueueChapterDownloadUseCase
import me.manga.kira.domain.usecase.downloads.EnqueueDownloadUseCase
import me.manga.kira.domain.usecase.downloads.ObserveCompressionDeferredUseCase
import me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase
import me.manga.kira.domain.usecase.library.MarkMangaOpenedUseCase
import me.manga.kira.domain.usecase.library.ObserveInLibraryUseCase
import me.manga.kira.domain.usecase.library.PersistNewChaptersUseCase
import me.manga.kira.domain.usecase.library.ToggleInLibraryUseCase
import me.manga.kira.domain.usecase.reader.MarkChaptersReadUseCase
import me.manga.kira.domain.usecase.reader.ToggleChapterBookmarkUseCase
import me.manga.kira.domain.usecase.reader.ToggleChapterReadUseCase
import me.manga.kira.presentation.testing.FakeLibraryRepository
import me.manga.kira.presentation.testing.RecordingChapterBookmarkRepository
import me.manga.kira.presentation.testing.RecordingMarkChapterReadRepository

/** No initial scoped emission: tests can inspect the rebind gap before the new Room query returns. */
internal class OwnerDownloads : DownloadsRepository {
    private val streams = mutableMapOf<String, MutableSharedFlow<List<DownloadedChapter>>>()
    private val latest = linkedMapOf<String, List<DownloadedChapter>>()
    private val global = MutableStateFlow<List<DownloadedChapter>>(emptyList())
    val scopedOwners = mutableListOf<Manga>()
    val cancelledOwners = mutableListOf<String>()
    var globalSubscriptions = 0
        private set

    override fun observeAll(): Flow<List<DownloadedChapter>> = global.onStart { globalSubscriptions++ }

    override fun observeForManga(manga: Manga): Flow<List<DownloadedChapter>> =
        stream(manga.url)
            .onStart { scopedOwners += manga }
            .onCompletion { cancelledOwners += manga.url }

    suspend fun publish(manga: Manga, rows: List<DownloadedChapter>) {
        latest[manga.url] = rows
        global.value = latest.values.flatten()
        stream(manga.url).emit(rows)
    }

    private fun stream(url: String): MutableSharedFlow<List<DownloadedChapter>> =
        streams.getOrPut(url) { MutableSharedFlow() }
}

internal class OwnerResolver(
    private val idsByMangaUrl: Map<String, Long>,
) : ChapterIdResolver {
    val singleRequests = mutableListOf<Pair<Manga, String>>()
    val bulkRequests = mutableListOf<Pair<Manga, List<String>>>()

    override suspend fun resolveChapterId(manga: Manga, chapterUrl: String): Long? {
        singleRequests += manga to chapterUrl
        return id(manga, chapterUrl)
    }

    override suspend fun resolveChapterIds(manga: Manga, chapterUrls: List<String>): Map<String, Long> {
        bulkRequests += manga to chapterUrls.toList()
        return chapterUrls.mapNotNull { url -> id(manga, url)?.let { url to it } }.toMap()
    }

    private fun id(manga: Manga, chapterUrl: String): Long? =
        idsByMangaUrl[manga.url]?.let { first ->
            when (chapterUrl) {
                CHAPTER_URL -> first
                SECOND_CHAPTER_URL -> first + 1
                else -> null
            }
        }
}

internal class OwnerDownloadActions : DownloadsActionRepository {
    val runningCancelled = mutableListOf<Pair<Long, Long>>()
    val queuedCancelled = mutableListOf<Long>()
    val enqueued = mutableListOf<Triple<Long, String, String>>()
    val fileDeleteAttempts = mutableListOf<Long>()
    val failingDeletes = mutableSetOf<Long>()
    val deleteOrder = mutableListOf<String>()

    override suspend fun enqueueDownload(chapterId: Long, mangaTitle: String, api: String): Result<Unit> {
        enqueued += Triple(chapterId, mangaTitle, api)
        return Result.success(Unit)
    }

    override suspend fun cancelDownload(chapterId: Long): Result<Unit> {
        queuedCancelled += chapterId
        return Result.success(Unit)
    }

    override suspend fun cancelRunningDownload(chapterId: Long, mangaId: Long): Result<Unit> {
        runningCancelled += chapterId to mangaId
        return Result.success(Unit)
    }

    override suspend fun retryDownload(chapterId: Long): Result<Unit> = error("unused")

    override suspend fun cancelAllDownloads(): Result<Unit> = error("unused")

    override suspend fun deleteDownload(chapterId: Long): Result<Unit> = error("unused")

    override suspend fun deleteDownloadedChapter(chapterId: Long): Result<Unit> {
        fileDeleteAttempts += chapterId
        deleteOrder += "files:$chapterId"
        return if (chapterId in failingDeletes) {
            Result.failure(IllegalStateException("file deletion failed"))
        } else {
            Result.success(Unit)
        }
    }

    override suspend fun reconcileInterrupted(): Result<Unit> = error("unused")
}

/** The existing Details fixture is private and hardwires no-op actions; reuse its public fakes only. */
internal class DetailsOwnerFixture(
    idsByMangaUrl: Map<String, Long>,
    dispatcher: CoroutineDispatcher,
    var chapters: List<Chapter>,
) {
    val downloads = OwnerDownloads()
    val actions = OwnerDownloadActions()
    val resolver = OwnerResolver(idsByMangaUrl)
    val deletedChapters = mutableListOf<Long>()
    private val library = FakeLibraryRepository().apply { emitInLibrary(true) }
    private val reads = RecordingMarkChapterReadRepository()
    private val enqueue = EnqueueDownloadUseCase(actions)
    private val dispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = dispatcher
            override val mainImmediate: CoroutineDispatcher = dispatcher
            override val default: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
            override val unconfined: CoroutineDispatcher = dispatcher
        }
    private val fetch =
        object : MangaDetailsRepository {
            override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> =
                AppResult.Success(detailsFor(manga, chapters))
        }
    private val saved =
        object : SavedMangaDetailsRepository {
            override fun observeSavedDetails(api: String, title: String): Flow<MangaDetails?> = flowOf(null)
        }
    private val classifier =
        object : AdultContentClassifier {
            override fun isAdultContent(api: String, genres: List<String>): Boolean = false
        }
    private val badges =
        object : ChapterNewBadgeRepository {
            override suspend fun clearNew(manga: Manga, chapterUrl: String) = Unit
        }
    private val deletion =
        object : ChapterDeletionRepository {
            override suspend fun deleteChapter(chapterId: Long) {
                actions.deleteOrder += "row:$chapterId"
                deletedChapters += chapterId
            }
        }
    private val connectivity =
        object : ConnectivityRepository {
            override fun observeIsOnline(): Flow<Boolean> = flowOf(true)
        }
    private val analytics =
        object : AnalyticsPort {
            override fun logAppOpen() = Unit

            override fun logMangaOpen(api: String, title: String, sourceScreen: String) = Unit
        }
    private val compression =
        object : CompressionDeferralRepository {
            override fun observeLowPowerDeferral(): Flow<Boolean> = flowOf(false)
        }

    val vm =
        DetailsViewModel(
            fetchDetails = FetchMangaDetailsUseCase(fetch),
            isAdultContent = IsAdultContentUseCase(classifier),
            observeInLibrary = ObserveInLibraryUseCase(library),
            observeSavedDetails = ObserveSavedMangaDetailsUseCase(saved),
            toggleInLibrary = ToggleInLibraryUseCase(library),
            enqueueAllChaptersDownload = EnqueueAllChaptersDownloadUseCase(resolver, enqueue, dispatchers),
            toggleChapterRead = ToggleChapterReadUseCase(reads),
            toggleChapterBookmark = ToggleChapterBookmarkUseCase(RecordingChapterBookmarkRepository()),
            markChaptersRead = MarkChaptersReadUseCase(reads),
            enqueueChapterDownload = EnqueueChapterDownloadUseCase(resolver, enqueue),
            enqueueDownload = enqueue,
            cancelChapterDownload = CancelChapterDownloadUseCase(resolver, CancelDownloadUseCase(actions)),
            cancelRunningDownload = CancelRunningDownloadUseCase(actions),
            cancelAllDownloads = CancelAllDownloadsUseCase(actions),
            deleteDownloadedChapter = DeleteDownloadedChapterUseCase(actions),
            observeDownloads = ObserveDownloadsUseCase(downloads),
            resolveChapterId = ResolveChapterIdUseCase(resolver),
            markMangaOpened = MarkMangaOpenedUseCase(library),
            persistNewChapters = PersistNewChaptersUseCase(library),
            clearChapterNew = ClearChapterNewUseCase(badges),
            deleteChapter = DeleteChapterUseCase(deletion),
            observeConnectivity = ObserveConnectivityUseCase(connectivity),
            logMangaOpen = LogMangaOpenUseCase(analytics),
            observeCompressionDeferred = ObserveCompressionDeferredUseCase(compression),
        )
}

internal const val CHAPTER_URL = "chapter/shared"
internal const val SECOND_CHAPTER_URL = "chapter/second"

internal fun sharedChapter(): Chapter = Chapter("1", "shared", CHAPTER_URL, null, false, false)

internal fun download(
    chapterId: Long,
    mangaId: Long,
    state: DownloadState,
    progress: Int = 0,
    sizeBytes: Long = 0,
): DownloadedChapter =
    DownloadedChapter(
        chapterId = chapterId,
        mangaId = mangaId,
        number = "1",
        mangaTitle = "Same title",
        state = state,
        progress = progress,
        errorMsg = null,
        url = CHAPTER_URL,
        sizeBytes = sizeBytes,
    )

internal fun detailsFor(manga: Manga, chapters: List<Chapter>): MangaDetails =
    MangaDetails(
        api = manga.api,
        language = manga.language,
        title = manga.title,
        url = manga.url,
        coverUrl = manga.coverUrl,
        description = "",
        author = "",
        rating = "",
        status = "",
        genres = manga.genres,
        chapters = chapters,
    )
