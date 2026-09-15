package me.manga.kira.presentation.details

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.repository.AdultContentClassifier
import me.manga.kira.domain.repository.AnalyticsPort
import me.manga.kira.domain.repository.ChapterDeletionRepository
import me.manga.kira.domain.repository.ChapterNewBadgeRepository
import me.manga.kira.domain.repository.CompressionDeferralRepository
import me.manga.kira.domain.repository.ConnectivityRepository
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** Exact-owner controls complement the private, single-owner Details regression fixture. */
internal class DetailsOwnerFixture(
    idsByMangaUrl: Map<String, Long>,
    dispatcher: CoroutineDispatcher,
    var chapters: List<Chapter>,
) {
    val downloads = OwnerDownloads()
    val actions = OwnerDownloadActions()
    val resolver = OwnerResolver(idsByMangaUrl)
    val deletedChapters = mutableListOf<Long>()
    val failingRowDeletes = mutableSetOf<Long>()
    var deleteGate: CompletableDeferred<Unit>? = null
    var onRowDeleted: (Long) -> Unit = {}
    val chaptersByMangaUrl = mutableMapOf<String, List<Chapter>>()
    val fetchRequests = mutableListOf<Manga>()

    // Single-owner overlay control only: the legacy saved port cannot distinguish identical metadata.
    // Opposite-owner scenarios use the exact-URL fetch map instead of pretending this port can.
    val savedDetails = MutableStateFlow<MangaDetails?>(null)
    var fetchGate: CompletableDeferred<Unit>? = null
    var fetchFailure: AppError? = null
    val library = FakeLibraryRepository().apply { emitInLibrary(true) }
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
            override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> {
                fetchRequests += manga
                fetchGate?.await()
                fetchFailure?.let { return AppResult.Failure(it) }
                return AppResult.Success(detailsFor(manga, chaptersByMangaUrl[manga.url] ?: chapters))
            }
        }
    private val saved =
        object : SavedMangaDetailsRepository {
            override fun observeSavedDetails(
                api: String,
                title: String,
            ): Flow<MangaDetails?> = savedDetails
        }
    private val classifier =
        object : AdultContentClassifier {
            override fun isAdultContent(
                api: String,
                genres: List<String>,
            ): Boolean = false
        }
    private val badges =
        object : ChapterNewBadgeRepository {
            override suspend fun clearNew(
                manga: Manga,
                chapterUrl: String,
            ) = Unit
        }
    private val deletion =
        object : ChapterDeletionRepository {
            override suspend fun deleteChapter(chapterId: Long) {
                actions.deleteOrder += "row:$chapterId"
                deleteGate?.await()
                check(chapterId !in failingRowDeletes) { "row deletion failed" }
                deletedChapters += chapterId
                onRowDeleted(chapterId)
            }
        }
    private val connectivity =
        object : ConnectivityRepository {
            override fun observeIsOnline(): Flow<Boolean> = flowOf(true)
        }
    private val analytics =
        object : AnalyticsPort {
            override fun logAppOpen() = Unit

            override fun logMangaOpen(
                api: String,
                title: String,
                sourceScreen: String,
            ) = Unit
        }
    private val compression =
        object : CompressionDeferralRepository {
            override fun observeLowPowerDeferral(): Flow<Boolean> = flowOf(false)
        }
    val enqueueAll = EnqueueAllChaptersDownloadUseCase(resolver, enqueue, dispatchers)

    val vm =
        DetailsViewModel(
            fetchDetails = FetchMangaDetailsUseCase(fetch),
            isAdultContent = IsAdultContentUseCase(classifier),
            observeInLibrary = ObserveInLibraryUseCase(library),
            observeSavedDetails = ObserveSavedMangaDetailsUseCase(saved),
            toggleInLibrary = ToggleInLibraryUseCase(library),
            enqueueAllChaptersDownload = enqueueAll,
            toggleChapterRead = ToggleChapterReadUseCase(reads),
            toggleChapterBookmark = ToggleChapterBookmarkUseCase(RecordingChapterBookmarkRepository()),
            markChaptersRead = MarkChaptersReadUseCase(reads),
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

    fun pauseResolution(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { resolver.gate = it }

    fun select(chapters: List<Chapter> = this.chapters) {
        vm.submit(DetailsIntent.OnSelectionClear)
        chapters.forEach { vm.submit(DetailsIntent.OnChapterLongClick(it)) }
    }

    fun assertLoadedOwner(manga: Manga): DetailsState {
        val current = vm.state.value
        assertEquals(manga.url, current.manga?.url)
        assertEquals(manga.url, assertNotNull(current.details).url)
        assertFalse(current.isLoading)
        return current
    }

    fun assertEnqueued(
        manga: Manga,
        chapterIds: List<Long>,
    ) {
        assertEquals(chapterIds.map { Triple(it, manga.title, manga.api) }, actions.enqueued)
    }

    fun assertResolution(
        manga: Manga,
        singleUrls: List<String>,
        bulkUrls: List<List<String>>,
    ) {
        assertEquals(singleUrls.map { manga to it }, resolver.singleRequests)
        assertEquals(bulkUrls.map { manga to it }, resolver.bulkRequests)
    }
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

internal fun detailsFor(
    manga: Manga,
    chapters: List<Chapter>,
): MangaDetails =
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
