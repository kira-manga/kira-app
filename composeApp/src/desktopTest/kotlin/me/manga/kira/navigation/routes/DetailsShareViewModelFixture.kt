package me.manga.kira.navigation.routes

import me.manga.kira.core.dispatchers.DefaultDispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.SavedWorkDetails
import me.manga.kira.domain.repository.MangaDetailsRepository
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

/** Builds the real Details VM from real use cases and explicit, in-memory domain ports. */
internal class DetailsShareViewModelFixture : MangaDetailsRepository {
    val seed = Manga("fixture", "en", "Navigation title", "https://old.source.example/manga", "", null, emptyList())
    val owner = SavedWorkIdentity(41L, WorkLocator(seed.api, seed.url))
    val resolved =
        MangaDetails(
            api = seed.api,
            language = seed.language,
            title = "Resolved source title",
            url = "https://source.example/manga",
            coverUrl = "",
            description = "",
            author = "",
            rating = "",
            status = "",
            genres = emptyList(),
            chapters = emptyList(),
        )
    val requests = mutableListOf<Manga>()
    // Empty chapters still fetch, while the saved title/URL remain distinct from the response.
    val reads = DetailsShareReadPorts(SavedWorkDetails(owner, resolved.copy(title = seed.title, url = seed.url)))
    val library = DetailsShareLibrary(owner, resolved)
    private val enqueue = EnqueueDownloadUseCase(DetailsShareDownloadActions)
    private val cancel = CancelDownloadUseCase(DetailsShareDownloadActions)
    private val enqueueAll =
        EnqueueAllChaptersDownloadUseCase(
            DetailsShareChapterPorts,
            enqueue,
            DefaultDispatcherProvider(),
        )

    override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> {
        requests += manga
        return AppResult.Success(resolved)
    }

    fun create(): DetailsViewModel =
        DetailsViewModel(
            fetchDetails = FetchMangaDetailsUseCase(this),
            isAdultContent = IsAdultContentUseCase(reads),
            observeInLibrary = ObserveInLibraryUseCase(library),
            observeSavedDetails = ObserveSavedMangaDetailsUseCase(reads),
            toggleInLibrary = ToggleInLibraryUseCase(library),
            enqueueAllChaptersDownload = enqueueAll,
            toggleChapterRead = ToggleChapterReadUseCase(DetailsShareChapterPorts),
            toggleChapterBookmark = ToggleChapterBookmarkUseCase(DetailsShareChapterPorts),
            markChaptersRead = MarkChaptersReadUseCase(DetailsShareChapterPorts),
            enqueueDownload = enqueue,
            cancelChapterDownload = CancelChapterDownloadUseCase(DetailsShareChapterPorts, cancel),
            cancelRunningDownload = CancelRunningDownloadUseCase(DetailsShareDownloadActions),
            cancelAllDownloads = CancelAllDownloadsUseCase(DetailsShareDownloadActions),
            deleteDownloadedChapter = DeleteDownloadedChapterUseCase(DetailsShareDownloadActions),
            observeDownloads = ObserveDownloadsUseCase(reads),
            resolveChapterId = ResolveChapterIdUseCase(DetailsShareChapterPorts),
            markMangaOpened = MarkMangaOpenedUseCase(library),
            persistNewChapters = PersistNewChaptersUseCase(library),
            clearChapterNew = ClearChapterNewUseCase(DetailsShareChapterPorts),
            deleteChapter = DeleteChapterUseCase(DetailsShareChapterPorts),
            observeConnectivity = ObserveConnectivityUseCase(reads),
            logMangaOpen = LogMangaOpenUseCase(reads),
            observeCompressionDeferred = ObserveCompressionDeferredUseCase(reads),
        )
}
