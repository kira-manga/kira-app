package me.manga.kira.presentation.testing

import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.FetchedWorkDetails
import me.manga.kira.domain.model.library.LibraryActivity
import me.manga.kira.domain.model.library.LibraryAffinity
import me.manga.kira.domain.model.library.LibraryChapterCounts
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.domain.model.library.GridDensity
import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.domain.model.library.LibraryDisplay
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.model.library.SortDirection
import me.manga.kira.domain.repository.DownloadsRepository
import me.manga.kira.domain.repository.LibraryPrefsRepository
import me.manga.kira.domain.repository.LibraryRefreshRepository
import me.manga.kira.domain.repository.LibraryRepository

/**
 * Hand fakes for the four repositories `LibraryViewModel`'s 25 use cases wrap, plus
 * `LibraryManga` factories. Local to `:presentation` commonTest because module test source sets
 * are not shared (the `:domain` test fakes are invisible here); kept intentionally close to the
 * `:domain` originals.
 */

fun sampleManga(
    api: String = "api",
    language: String = "en",
    title: String = "Title",
    url: String = "https://example.test/$title",
    coverUrl: String = "",
    rating: Int? = null,
    genres: List<String> = emptyList(),
): Manga = Manga(api, language, title, url, coverUrl, rating, genres)

fun sampleLibraryManga(
    title: String,
    unreadCount: Int = 0,
    totalChapters: Int = 0,
    hasDownloads: Boolean = false,
    bookmarkedCount: Int = 0,
    downloadedCount: Int = 0,
    isLiked: Boolean = false,
    isWatchingNow: Boolean = false,
    addedAtEpochMillis: Long = 0L,
    lastReadAtEpochMillis: Long? = null,
    lastOpenedAtEpochMillis: Long = 0L,
    id: Long = 1L,
): LibraryManga = LibraryManga(
    manga = sampleManga(title = title),
    identity = SavedWorkIdentity(id, WorkLocator("api", "https://example.test/$title")),
    activity = LibraryActivity(
        Instant.fromEpochMilliseconds(addedAtEpochMillis),
        Instant.fromEpochMilliseconds(lastOpenedAtEpochMillis),
        lastReadAtEpochMillis?.let(Instant::fromEpochMilliseconds),
    ),
    counts = LibraryChapterCounts(totalChapters, unreadCount, maxOf(downloadedCount, if (hasDownloads) 1 else 0), bookmarkedCount),
    affinity = LibraryAffinity(isLiked, isWatchingNow),
)

/** Explicit-address orchestration fake; persistence/aliases are covered by real-Room data tests. */
class FakeLibraryRepository : LibraryRepository {
    private val library = MutableStateFlow<List<LibraryManga>>(emptyList())
    private val membership = MutableStateFlow<Map<WorkLocator, AppResult<SavedWorkIdentity?>>>(emptyMap())
    val calls = mutableListOf<String>()
    var lastPersistedNewChapters: List<Chapter> = emptyList()
        private set
    var lastAddedDetails: MangaDetails? = null
        private set
    var lastRemovedOwner: SavedWorkIdentity? = null
        private set
    var lastRefreshRequests: List<LibraryRefreshRequest> = emptyList()
        private set
    val refreshRequests = mutableListOf<List<LibraryRefreshRequest>>()
    var lastBulkOwners: List<SavedWorkIdentity> = emptyList()
        private set
    var refreshResult: AppResult<List<LibraryRefreshReceipt>>? = null
    var beforeRefresh: suspend (List<LibraryRefreshRequest>) -> Unit = {}

    fun emitLibrary(value: List<LibraryManga>) { library.value = value }
    fun emitMembership(owner: SavedWorkIdentity) {
        membership.value = membership.value + (owner.locator to AppResult.Success(owner))
    }
    fun emitMembershipFailure(work: WorkLocator, error: me.manga.kira.core.error.AppError) {
        membership.value = membership.value + (work to AppResult.Failure(error))
    }

    override fun observeLibrary(): Flow<List<LibraryManga>> = library.asStateFlow()
    override fun observeMembership(work: WorkLocator): Flow<AppResult<SavedWorkIdentity?>> =
        combine(library, membership) { rows, overrides ->
            overrides[work] ?: AppResult.Success(rows.singleOrNull { it.identity.locator == work }?.identity)
        }

    override suspend fun get(work: WorkLocator): AppResult<LibraryManga?> {
        calls += "get($work)"
        return AppResult.Success(library.value.singleOrNull { it.identity.locator == work })
    }

    override suspend fun addToLibrary(fetched: FetchedWorkDetails): AppResult<SavedWorkIdentity> {
        calls += "addToLibrary(${fetched.details.title},chapters=${fetched.details.chapters.size})"
        lastAddedDetails = fetched.details
        return AppResult.Success(SavedWorkIdentity(1L, fetched.requested))
    }

    override suspend fun refresh(
        requests: List<LibraryRefreshRequest>,
        notify: Boolean,
    ): AppResult<List<LibraryRefreshReceipt>> {
        calls += "refresh(${requests.size},notify=$notify)"
        lastRefreshRequests = requests
        refreshRequests += requests
        lastPersistedNewChapters = requests.flatMap { it.fetched.details.chapters }
        beforeRefresh(requests)
        return refreshResult ?: AppResult.Success(requests.map {
            LibraryRefreshReceipt(it.owner, it.fetched.details.chapters.size, emptyList())
        })
    }

    override suspend fun removeFromLibrary(owner: SavedWorkIdentity): AppResult<Unit> {
        calls += "removeFromLibrary(${owner.id})"
        lastRemovedOwner = owner
        return AppResult.Success(Unit)
    }
    override suspend fun removeAllFromLibrary(owners: List<SavedWorkIdentity>): AppResult<Int> {
        calls += "removeAllFromLibrary(${owners.size})"
        lastBulkOwners = owners
        return AppResult.Success(owners.distinct().size)
    }
    override suspend fun toggleLiked(owner: SavedWorkIdentity): AppResult<Unit> {
        calls += "toggleLiked(${owner.id})"
        return AppResult.Success(Unit)
    }
    override suspend fun toggleWatchingNow(owner: SavedWorkIdentity): AppResult<Unit> {
        calls += "toggleWatchingNow(${owner.id})"
        return AppResult.Success(Unit)
    }
    override suspend fun markOpened(owner: SavedWorkIdentity): AppResult<Unit> {
        calls += "markOpened(${owner.id})"
        return AppResult.Success(Unit)
    }
}

class FakeLibraryPrefsRepository : LibraryPrefsRepository {
    private val sort = MutableStateFlow(LibrarySort.ALPHABETIC)
    private val direction = MutableStateFlow(SortDirection.ASCENDING)
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val density = MutableStateFlow(GridDensity.COMFORTABLE)
    private val itemsPerRow = MutableStateFlow(0)
    private val category = MutableStateFlow(LibraryCategory.NAN)
    private val lastUpdated = MutableStateFlow<Instant?>(null)
    private val display = MutableStateFlow(LibraryDisplay())

    private val randomSeed = MutableStateFlow(64464L)
    override fun observeSort(): Flow<LibrarySort> = sort.asStateFlow()
    override suspend fun setSort(sort: LibrarySort) { this.sort.value = sort }
    override fun observeRandomSeed(): Flow<Long> = randomSeed.asStateFlow()
    override suspend fun setRandomSeed(seed: Long) { this.randomSeed.value = seed }
    override fun observeSortDirection(): Flow<SortDirection> = direction.asStateFlow()
    override suspend fun setSortDirection(direction: SortDirection) { this.direction.value = direction }
    override fun observeFilter(): Flow<LibraryFilter> = filter.asStateFlow()
    override suspend fun setFilter(filter: LibraryFilter) { this.filter.value = filter }
    override fun observeGridDensity(): Flow<GridDensity> = density.asStateFlow()
    override suspend fun setGridDensity(density: GridDensity) { this.density.value = density }
    override fun observeItemsPerRow(): Flow<Int> = itemsPerRow.asStateFlow()
    override suspend fun setItemsPerRow(itemsPerRow: Int) { this.itemsPerRow.value = itemsPerRow }
    override fun observeCategory(): Flow<LibraryCategory> = category.asStateFlow()
    override suspend fun setCategory(category: LibraryCategory) { this.category.value = category }
    override fun observeLastUpdated(): Flow<Instant?> = lastUpdated.asStateFlow()
    override suspend fun setLastUpdated(timestamp: Instant) { this.lastUpdated.value = timestamp }
    override fun observeDisplay(): Flow<LibraryDisplay> = display.asStateFlow()
    override suspend fun setShowSource(showSource: Boolean) { display.value = display.value.copy(showSource = showSource) }
    override suspend fun setShowCount(showCount: Boolean) { display.value = display.value.copy(showCount = showCount) }
    override suspend fun setShowDetails(showDetails: Boolean) { display.value = display.value.copy(showDetails = showDetails) }
    override suspend fun setShowButtons(showButtons: Boolean) { display.value = display.value.copy(showButtons = showButtons) }
    override suspend fun setShowTabs(showTabs: Boolean) { display.value = display.value.copy(showTabs = showTabs) }
}

class FakeLibraryRefreshRepository : LibraryRefreshRepository {
    private val refreshing = MutableStateFlow(false)
    private val lastResult = MutableStateFlow<AppResult<Int>?>(null)
    override fun refresh() { /* no-op for projection tests */ }
    override fun observeIsRefreshing(): Flow<Boolean> = refreshing.asStateFlow()
    override fun observeLastRefreshResult(): Flow<AppResult<Int>?> = lastResult.asStateFlow()
}

class FakeDownloadsRepository : DownloadsRepository {
    private val all = MutableStateFlow<List<DownloadedChapter>>(emptyList())
    override fun observeForManga(manga: Manga): Flow<List<DownloadedChapter>> = error("unused scoped observation")

    override fun observeAll(): Flow<List<DownloadedChapter>> = all.asStateFlow()
}
