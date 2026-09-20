package me.manga.kira.navigation.routes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.model.library.SavedWorkDetails
import me.manga.kira.domain.repository.AdultContentClassifier
import me.manga.kira.domain.repository.AnalyticsPort
import me.manga.kira.domain.repository.ChapterBookmarkRepository
import me.manga.kira.domain.repository.ChapterDeletionRepository
import me.manga.kira.domain.repository.ChapterIdResolver
import me.manga.kira.domain.repository.ChapterNewBadgeRepository
import me.manga.kira.domain.repository.CompressionDeferralRepository
import me.manga.kira.domain.repository.ConnectivityRepository
import me.manga.kira.domain.repository.DownloadsActionRepository
import me.manga.kira.domain.repository.DownloadsRepository
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.repository.MarkChapterReadRepository
import me.manga.kira.domain.repository.SavedMangaDetailsRepository

/** Scoped in-memory observations for the real route ViewModel; no data/platform graph. */
internal class DetailsShareReadPorts(
    private val requested: WorkLocator,
) : AdultContentClassifier,
    SavedMangaDetailsRepository,
    DownloadsRepository,
    ConnectivityRepository,
    CompressionDeferralRepository,
    AnalyticsPort {
    val savedObservations = mutableListOf<WorkLocator>()
    val downloadObservations = mutableListOf<WorkLocator>()

    override fun isAdultContent(api: String, genres: List<String>): Boolean = false

    override fun observeSavedDetails(work: WorkLocator): Flow<AppResult<SavedWorkDetails?>> {
        check(work == requested)
        savedObservations += work
        return flowOf(AppResult.Success(null))
    }

    override fun observeForManga(manga: Manga): Flow<List<DownloadedChapter>> {
        val work = WorkLocator(manga.api, manga.url)
        check(work == requested)
        downloadObservations += work
        return flowOf(emptyList())
    }

    override fun observeAll(): Flow<List<DownloadedChapter>> = unexpected()

    override fun observeIsOnline(): Flow<Boolean> = flowOf(true)

    override fun observeLowPowerDeferral(): Flow<Boolean> = flowOf(false)

    override fun logAppOpen() = Unit

    override fun logMangaOpen(api: String, title: String, sourceScreen: String) = Unit
}

/** One canned accepted fetch, not a Room/alias-policy simulation or title-keyed membership fake. */
internal class DetailsShareLibrary(
    private val owner: SavedWorkIdentity,
    private val resolved: MangaDetails,
) : LibraryRepository {
    val observedKeys = mutableListOf<WorkLocator>()
    val refreshRequests = mutableListOf<LibraryRefreshRequest>()

    override fun observeLibrary(): Flow<List<LibraryManga>> = unexpected()

    override fun observeMembership(work: WorkLocator): Flow<AppResult<SavedWorkIdentity?>> {
        check(work == owner.locator)
        observedKeys += work
        return flowOf(AppResult.Success(owner))
    }

    override suspend fun get(work: WorkLocator): AppResult<LibraryManga?> = unexpected()

    override suspend fun addToLibrary(fetched: FetchedWorkDetails): AppResult<SavedWorkIdentity> = unexpected()

    override suspend fun refresh(
        requests: List<LibraryRefreshRequest>,
        notify: Boolean,
    ): AppResult<List<LibraryRefreshReceipt>> {
        check(!notify)
        check(requests == listOf(LibraryRefreshRequest(owner, FetchedWorkDetails(owner.locator, resolved))))
        refreshRequests += requests
        return AppResult.Success(listOf(LibraryRefreshReceipt(owner, 0, emptyList())))
    }

    override suspend fun removeFromLibrary(owner: SavedWorkIdentity): AppResult<Unit> = unexpected()

    override suspend fun removeAllFromLibrary(owners: List<SavedWorkIdentity>): AppResult<Int> = unexpected()

    override suspend fun toggleLiked(owner: SavedWorkIdentity): AppResult<Unit> = unexpected()

    override suspend fun toggleWatchingNow(owner: SavedWorkIdentity): AppResult<Unit> = unexpected()

    override suspend fun markOpened(owner: SavedWorkIdentity): AppResult<Unit> = unexpected()
}

internal object DetailsShareChapterPorts :
    ChapterIdResolver,
    MarkChapterReadRepository,
    ChapterBookmarkRepository,
    ChapterNewBadgeRepository,
    ChapterDeletionRepository {
    override suspend fun resolveChapterId(manga: Manga, chapterUrl: String): Long? = unexpected()

    override suspend fun resolveChapterIds(manga: Manga, chapterUrls: List<String>): Map<String, Long> = unexpected()

    override suspend fun markRead(manga: Manga, chapterUrl: String): Unit = unexpected()

    override suspend fun toggleRead(manga: Manga, chapterUrl: String): Unit = unexpected()

    override suspend fun markRead(manga: Manga, chapterUrls: List<String>): Unit = unexpected()

    override fun observeBookmark(manga: Manga, chapterUrl: String): Flow<Boolean> = unexpected()

    override suspend fun toggleBookmark(manga: Manga, chapterUrl: String): Boolean = unexpected()

    override suspend fun toggleBookmark(manga: Manga, chapterUrls: List<String>): Unit = unexpected()

    override suspend fun clearNew(manga: Manga, chapterUrl: String): Unit = unexpected()

    override suspend fun deleteChapter(chapterId: Long): Unit = unexpected()
}

internal object DetailsShareDownloadActions : DownloadsActionRepository {
    override suspend fun enqueueDownload(
        chapterId: Long,
        mangaTitle: String,
        api: String,
    ): Result<Unit> = unexpected()

    override suspend fun retryDownload(chapterId: Long): Result<Unit> = unexpected()

    override suspend fun cancelDownload(chapterId: Long): Result<Unit> = unexpected()

    override suspend fun cancelRunningDownload(chapterId: Long, mangaId: Long): Result<Unit> = unexpected()

    override suspend fun cancelAllDownloads(): Result<Unit> = unexpected()

    override suspend fun deleteDownload(chapterId: Long): Result<Unit> = unexpected()

    override suspend fun deleteDownloadedChapter(chapterId: Long): Result<Unit> = unexpected()

    override suspend fun reconcileInterrupted(): Result<Unit> = unexpected()
}

private fun unexpected(): Nothing = error("Unexpected non-Share action in Details route fixture")
