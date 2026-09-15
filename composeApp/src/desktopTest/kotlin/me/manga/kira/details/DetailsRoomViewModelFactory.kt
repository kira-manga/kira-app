package me.manga.kira.details
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.repository.ChapterBookmarkRepositoryImpl
import me.manga.kira.data.repository.ChapterDeletionRepositoryImpl
import me.manga.kira.data.repository.ChapterIdResolverImpl
import me.manga.kira.data.repository.ChapterNewBadgeRepositoryImpl
import me.manga.kira.data.repository.DownloadsActionRepositoryImpl
import me.manga.kira.data.repository.DownloadsRepositoryImpl
import me.manga.kira.data.repository.MarkChapterReadRepositoryImpl
import me.manga.kira.data.repository.SavedMangaDetailsRepositoryImpl
import me.manga.kira.domain.repository.AdultContentClassifier
import me.manga.kira.domain.repository.AnalyticsPort
import me.manga.kira.domain.repository.CompressionDeferralRepository
import me.manga.kira.domain.repository.ConnectivityRepository
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
import me.manga.kira.presentation.details.DetailsViewModel
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository

/** Composition-root test wiring: real VM/use cases and Room adapters, without starting platform services. */
internal fun createDetailsRoomViewModel(fixture: DetailsUrlOnlyRoomFixture): DetailsViewModel =
    DetailsRoomViewModelFactory(fixture).create()

private class DetailsRoomViewModelFactory(
    private val fixture: DetailsUrlOnlyRoomFixture,
) {
    private val dao = fixture.db.chapterDao()
    private val library = fixture.library
    private val resolver = ChapterIdResolverImpl(dao)
    private val reads = MarkChapterReadRepositoryImpl(dao)
    private val downloads = roomDownloadActions(fixture)
    private val enqueue = EnqueueDownloadUseCase(downloads)
    private val saved = SavedMangaDetailsRepositoryImpl(fixture.db.mangaDao(), dao, fixture.dispatchers)
    private val observedDownloads =
        DownloadsRepositoryImpl(UnusedDetailsDownloadEngine, fixture.db.chapterDownloadingDao())

    fun create(): DetailsViewModel =
        DetailsViewModel(
            fetchDetails = FetchMangaDetailsUseCase(fixture.source),
            isAdultContent = IsAdultContentUseCase(DetailsRoomDevicePorts),
            observeInLibrary = ObserveInLibraryUseCase(library),
            observeSavedDetails = ObserveSavedMangaDetailsUseCase(saved),
            toggleInLibrary = ToggleInLibraryUseCase(library),
            enqueueAllChaptersDownload = EnqueueAllChaptersDownloadUseCase(resolver, enqueue, fixture.dispatchers),
            toggleChapterRead = ToggleChapterReadUseCase(reads),
            toggleChapterBookmark = ToggleChapterBookmarkUseCase(ChapterBookmarkRepositoryImpl(dao)),
            markChaptersRead = MarkChaptersReadUseCase(reads),
            enqueueDownload = enqueue,
            cancelChapterDownload = CancelChapterDownloadUseCase(resolver, CancelDownloadUseCase(downloads)),
            cancelRunningDownload = CancelRunningDownloadUseCase(downloads),
            cancelAllDownloads = CancelAllDownloadsUseCase(downloads),
            deleteDownloadedChapter = DeleteDownloadedChapterUseCase(downloads),
            observeDownloads = ObserveDownloadsUseCase(observedDownloads),
            resolveChapterId = ResolveChapterIdUseCase(resolver),
            markMangaOpened = MarkMangaOpenedUseCase(library),
            persistNewChapters = PersistNewChaptersUseCase(library),
            clearChapterNew = ClearChapterNewUseCase(ChapterNewBadgeRepositoryImpl(dao)),
            deleteChapter = DeleteChapterUseCase(ChapterDeletionRepositoryImpl(dao)),
            observeConnectivity = ObserveConnectivityUseCase(DetailsRoomDevicePorts),
            logMangaOpen = LogMangaOpenUseCase(DetailsRoomDevicePorts),
            observeCompressionDeferred = ObserveCompressionDeferredUseCase(DetailsRoomDevicePorts),
        )
}

private fun roomDownloadActions(fixture: DetailsUrlOnlyRoomFixture): DownloadsActionRepositoryImpl =
    DownloadsActionRepositoryImpl(
        legacy = UnusedDetailsDownloadEngine,
        chapterDownloadDao = fixture.db.chapterDownloadingDao(),
        chapterDao = fixture.db.chapterDao(),
        appFileSystem = fixture.fileSystem,
        artifacts = fixture.artifacts,
    )

private object DetailsRoomDevicePorts :
    AdultContentClassifier,
    ConnectivityRepository,
    CompressionDeferralRepository,
    AnalyticsPort {
    override fun isAdultContent(
        api: String,
        genres: List<String>,
    ): Boolean = false

    override fun observeIsOnline(): Flow<Boolean> = flowOf(true)

    override fun observeLowPowerDeferral(): Flow<Boolean> = flowOf(false)

    override fun logAppOpen() = Unit

    override fun logMangaOpen(
        api: String,
        title: String,
        sourceScreen: String,
    ) = Unit
}

/** No download engine, filesystem cleanup, worker or network service may start during this witness. */
internal object UnusedDetailsDownloadEngine : DownloadRepository {
    override fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>> = error("unused")

    override suspend fun enqueueChapterDownload(
        chapter: SavedChapterEntity,
        title: String,
        mangaApi: String,
    ): Unit = error("unused")

    override suspend fun deleteDownload(chapterId: Long): Unit = error("unused")

    override suspend fun onCancel(chapterId: Long): Unit = error("unused")

    override suspend fun cancelARunningChapter(
        chapterId: Long,
        mangaId: Long,
    ): Unit = error("unused")

    override suspend fun cancelAllDownloads(): Unit = error("unused")

    override suspend fun reconcileInterruptedDownloads(): Unit = error("unused")
}
