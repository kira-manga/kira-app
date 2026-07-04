package me.manga.kira.presentation.testing

import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
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
import me.manga.kira.domain.repository.MangaKey

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
): LibraryManga = LibraryManga(
    manga = sampleManga(title = title),
    addedAt = Instant.fromEpochMilliseconds(addedAtEpochMillis),
    unreadCount = unreadCount,
    hasDownloads = hasDownloads,
    totalChapters = totalChapters,
    lastReadAt = lastReadAtEpochMillis?.let { Instant.fromEpochMilliseconds(it) },
    lastOpenedAt = Instant.fromEpochMilliseconds(lastOpenedAtEpochMillis),
    bookmarkedCount = bookmarkedCount,
    downloadedCount = downloadedCount,
    isLiked = isLiked,
    isWatchingNow = isWatchingNow,
)

class FakeLibraryRepository : LibraryRepository {
    private val library = MutableStateFlow<List<LibraryManga>>(emptyList())
    private val inLibrary = MutableStateFlow(false)
    val calls: MutableList<String> = mutableListOf()

    fun emitLibrary(value: List<LibraryManga>) { library.value = value }

    /** Lets a test flip the reactive "in library" flag the Details VM gates persistence on. */
    fun emitInLibrary(value: Boolean) { inLibrary.value = value }

    /** Chapters captured by the most recent [persistNewChapters] call (#3 refresh-persist test). */
    var lastPersistedNewChapters: List<Chapter> = emptyList()
        private set

    override fun observeLibrary(): Flow<List<LibraryManga>> = library.asStateFlow()
    override fun observeIsInLibrary(api: String, language: String, title: String): Flow<Boolean> =
        inLibrary.asStateFlow()

    override suspend fun get(api: String, language: String, title: String): AppResult<LibraryManga?> {
        calls += "get($title)"
        return AppResult.Success(library.value.firstOrNull { it.manga.title == title })
    }
    var lastAddedChapters: List<Chapter> = emptyList()
        private set

    override suspend fun addToLibrary(manga: Manga, chapters: List<Chapter>): AppResult<Unit> {
        calls += "addToLibrary(${manga.title},chapters=${chapters.size})"
        lastAddedChapters = chapters
        return AppResult.Success(Unit)
    }
    override suspend fun persistNewChapters(
        api: String,
        language: String,
        title: String,
        fetched: List<Chapter>,
    ): AppResult<Int> {
        calls += "persistNewChapters($title,fetched=${fetched.size})"
        lastPersistedNewChapters = fetched
        return AppResult.Success(fetched.size)
    }
    override suspend fun persistNewChaptersAndNotify(manga: Manga, fetched: List<Chapter>): AppResult<Int> {
        calls += "persistNewChaptersAndNotify(${manga.title},fetched=${fetched.size})"
        lastPersistedNewChapters = fetched
        return AppResult.Success(fetched.size)
    }
    override suspend fun updateCoverIfChanged(
        api: String,
        language: String,
        title: String,
        newCoverUrl: String,
    ): AppResult<Unit> {
        calls += "updateCoverIfChanged($title,$newCoverUrl)"; return AppResult.Success(Unit)
    }
    override suspend fun removeFromLibrary(api: String, language: String, title: String): AppResult<Unit> {
        calls += "removeFromLibrary($title)"; return AppResult.Success(Unit)
    }
    override suspend fun removeAllFromLibrary(keys: List<MangaKey>): AppResult<Int> {
        calls += "removeAllFromLibrary(${keys.size})"; return AppResult.Success(keys.size)
    }
    override suspend fun toggleLiked(key: MangaKey): AppResult<Unit> {
        calls += "toggleLiked(${key.title})"; return AppResult.Success(Unit)
    }
    override suspend fun toggleWatchingNow(key: MangaKey): AppResult<Unit> {
        calls += "toggleWatchingNow(${key.title})"; return AppResult.Success(Unit)
    }
    override suspend fun markOpened(api: String, language: String, title: String): AppResult<Unit> {
        calls += "markOpened($title)"; return AppResult.Success(Unit)
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
    override fun observeAll(): Flow<List<DownloadedChapter>> = all.asStateFlow()
}
