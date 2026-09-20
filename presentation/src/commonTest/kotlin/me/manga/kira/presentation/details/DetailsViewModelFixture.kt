package me.manga.kira.presentation.details

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.SavedWorkDetails
import me.manga.kira.domain.repository.AdultContentClassifier
import me.manga.kira.domain.repository.AnalyticsPort
import me.manga.kira.domain.repository.ChapterDeletionRepository
import me.manga.kira.domain.repository.ChapterIdResolver
import me.manga.kira.domain.repository.ChapterNewBadgeRepository
import me.manga.kira.domain.repository.ConnectivityRepository
import me.manga.kira.domain.repository.DownloadsActionRepository
import me.manga.kira.domain.repository.DownloadsRepository
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.repository.MarkChapterReadRepository
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

internal class FakeMangaDetailsRepository(var result: AppResult<MangaDetails>) : MangaDetailsRepository {
    var fetchCount = 0
        private set
    val requests = mutableListOf<Manga>()
    var respond: suspend (Manga) -> AppResult<MangaDetails> = { result }

    override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> {
        fetchCount++
        requests += manga
        return respond(manga)
    }
}

/** A fixture has one explicit retained owner; different requested addresses never share its row. */
internal class FakeSavedMangaDetailsRepository(val owner: SavedWorkIdentity) : SavedMangaDetailsRepository {
    val saved = MutableStateFlow<MangaDetails?>(null)
    val failure = MutableStateFlow<AppError?>(null)
    val currentOwner = MutableStateFlow(owner)

    override fun observeSavedDetails(work: WorkLocator): Flow<AppResult<SavedWorkDetails?>> =
        combine(saved, failure, currentOwner) { details, error, retained ->
            when {
                work != retained.locator -> AppResult.Success(null)
                error != null -> AppResult.Failure(error)
                else -> AppResult.Success(details?.let { SavedWorkDetails(retained, it) })
            }
        }
}

internal class VmFixtureOptions {
    var downloadsRepo: DownloadsRepository = EmptyDownloadsRepository
    var idResolver: ChapterIdResolver = NullChapterIdResolver
    var downloadActions: DownloadsActionRepository = NoopDownloadsActionRepository
    var libraryRepo: FakeLibraryRepository = FakeLibraryRepository()
    var badgeRepo: ChapterNewBadgeRepository = RecordingChapterNewBadgeRepository()
    var deletionRepo: ChapterDeletionRepository = RecordingChapterDeletionRepository()
    var markReadRepo: MarkChapterReadRepository = NoopMarkChapterReadRepository
    var connectivity: ConnectivityRepository = FakeConnectivityRepository(online = true)
    var analytics: AnalyticsPort = RecordingAnalyticsPort()
    var compressionDeferred: Flow<Boolean> = MutableStateFlow(false)
    var adultClassifier: AdultContentClassifier = NoAdultClassifier
}

internal fun createVmWithFetchFake(
    fetch: AppResult<MangaDetails>,
    saved: SavedMangaDetailsRepository,
    options: VmFixtureOptions,
    testDispatchers: DispatcherProvider,
): Pair<DetailsViewModel, FakeMangaDetailsRepository> {
    val fetchFake = FakeMangaDetailsRepository(fetch)
    return createDetailsViewModel(fetchFake, saved, options, testDispatchers) to fetchFake
}

private fun createDetailsViewModel(
    fetch: MangaDetailsRepository,
    saved: SavedMangaDetailsRepository,
    options: VmFixtureOptions,
    dispatchers: DispatcherProvider,
): DetailsViewModel = DetailsViewModel(
    fetchDetails = FetchMangaDetailsUseCase(fetch),
    isAdultContent = IsAdultContentUseCase(options.adultClassifier),
    observeInLibrary = ObserveInLibraryUseCase(options.libraryRepo),
    observeSavedDetails = ObserveSavedMangaDetailsUseCase(saved),
    toggleInLibrary = ToggleInLibraryUseCase(options.libraryRepo),
    enqueueAllChaptersDownload = enqueueAll(options, dispatchers),
    toggleChapterRead = ToggleChapterReadUseCase(options.markReadRepo),
    toggleChapterBookmark = ToggleChapterBookmarkUseCase(NoopChapterBookmarkRepository),
    markChaptersRead = MarkChaptersReadUseCase(options.markReadRepo),
    enqueueDownload = EnqueueDownloadUseCase(options.downloadActions),
    cancelChapterDownload = cancelChapter(),
    cancelRunningDownload = CancelRunningDownloadUseCase(NoopDownloadsActionRepository),
    cancelAllDownloads = CancelAllDownloadsUseCase(NoopDownloadsActionRepository),
    deleteDownloadedChapter = DeleteDownloadedChapterUseCase(options.downloadActions),
    observeDownloads = ObserveDownloadsUseCase(options.downloadsRepo),
    resolveChapterId = ResolveChapterIdUseCase(options.idResolver),
    markMangaOpened = MarkMangaOpenedUseCase(options.libraryRepo),
    persistNewChapters = PersistNewChaptersUseCase(options.libraryRepo),
    clearChapterNew = ClearChapterNewUseCase(options.badgeRepo),
    deleteChapter = DeleteChapterUseCase(options.deletionRepo),
    observeConnectivity = ObserveConnectivityUseCase(options.connectivity),
    logMangaOpen = LogMangaOpenUseCase(options.analytics),
    observeCompressionDeferred = ObserveCompressionDeferredUseCase(FakeCompressionDeferralRepository(options.compressionDeferred)),
)

private fun enqueueAll(options: VmFixtureOptions, dispatchers: DispatcherProvider) =
    EnqueueAllChaptersDownloadUseCase(
        chapterIdResolver = options.idResolver,
        enqueueDownload = EnqueueDownloadUseCase(options.downloadActions),
        dispatchers = dispatchers,
    )

private fun cancelChapter() = CancelChapterDownloadUseCase(
    chapterIdResolver = NullChapterIdResolver,
    cancelDownload = CancelDownloadUseCase(NoopDownloadsActionRepository),
)

internal fun savedOwner(id: Long = 1L, seed: Manga = manga()): SavedWorkIdentity =
    SavedWorkIdentity(id, WorkLocator(seed.api, seed.url))

internal class DetailsIdentityFixture(
    dispatchers: DispatcherProvider,
    owner: SavedWorkIdentity,
    cached: MangaDetails?,
    network: AppResult<MangaDetails>,
    val options: VmFixtureOptions,
) {
    val saved = FakeSavedMangaDetailsRepository(owner).apply { this.saved.value = cached }
    private val created = createVmWithFetchFake(network, saved, options, dispatchers)
    val vm: DetailsViewModel = created.first
    val fetch: FakeMangaDetailsRepository = created.second
    val library: FakeLibraryRepository = options.libraryRepo
}

internal fun manga(api: String = "src", title: String = "Naruto") = Manga(
    api = api,
    language = "en",
    title = title,
    url = "https://x/naruto",
    coverUrl = "https://x/c.jpg",
    rating = null,
    genres = emptyList(),
)

internal fun chapter(url: String, isRead: Boolean = false, isDownloaded: Boolean = false) = Chapter(
    number = url.substringAfterLast('/'),
    name = "",
    url = url,
    date = null,
    isDownloaded = isDownloaded,
    isBookmarked = false,
    isRead = isRead,
)

internal fun details(chapters: List<Chapter>) = MangaDetails(
    api = "src",
    language = "en",
    title = "Naruto",
    url = "https://x/naruto",
    coverUrl = "https://x/c.jpg",
    description = "",
    author = "",
    rating = "",
    status = "",
    genres = emptyList(),
    chapters = chapters,
)
