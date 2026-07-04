package me.manga.yamiapk.presentation.features.library.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.yamiapk.R
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.storage.SharedPrefsHelper
import me.manga.yamiapk.core.util.notification.ChapterNotificationHelper
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.domain.model.MangaDisplayItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.repos.MangaRepository
import me.manga.yamiapk.presentation.features.library.domain.LibraryRepository
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.yamiapk.presentation.features.settings.domain.SettingsRepository
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val mangaRepository: MangaRepository,
    private val prefs: SharedPrefsHelper,
    private val settingsRepository: SettingsRepository,
    private val sourcesRepository: SourcesRepository
) : ViewModel() {

    /** --- UI State data class --- */
    data class UiState(
        val isRefreshing: Boolean = false,
        val items: State<List<MangaDisplayItem>> = State.Loading,
        val errorMessage: String? = null,
        val lastUpdated: LocalDateTime? = null,
        val filter: FilterType = FilterType.ALL,
        val sort: SortType = SortType.ALPHABETIC,
        val tabs: FilterTabs = FilterTabs.NAN,
        val ascending: Boolean = true,
        val showDetails: Boolean = true,
        val showButtons: Boolean = true,
        val showTabs: Boolean = true,
        val showSource: Boolean = true,
        val showCount: Boolean = true,
        val randomSeed: Long? = null,          // <— new
        val itemsPerRow: Int = 2,
        val searchQuery: String = ""
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** --- Enums instead of raw strings --- */
    enum class FilterType {
        ALL, DOWNLOADED, UNREAD, STARTED, BOOKMARKED, COMPLETED;

        fun getDisplayName(context: Context): String {
            return when (this) {
                ALL        -> context.getString(R.string.filter_all)
                DOWNLOADED -> context.getString(R.string.filter_downloaded)
                UNREAD     -> context.getString(R.string.filter_unread)
                STARTED    -> context.getString(R.string.filter_started)
                BOOKMARKED -> context.getString(R.string.filter_bookmarked)
                COMPLETED  -> context.getString(R.string.filter_completed)
            }
        }
    }

    enum class FilterTabs {
        NAN, WATCHING_NOW,LIKED, ;
        fun getDisplayName(context: Context): String =
            when (this) {
            NAN -> context.getString(R.string.filter_all)
            LIKED -> context.getString(R.string.likes)
            WATCHING_NOW -> context.getString(R.string.watching_now)
        }
    }
    fun onTabChanged(newTab: FilterTabs) {
        updateState { copy(tabs = newTab) }
    }
        val darkMode: StateFlow<Boolean> = settingsRepository.darkModeFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, settingsRepository.isDarkMode())
    val followSystem: StateFlow<Boolean> = settingsRepository.followSystemFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, settingsRepository.isFollowSystem())

    enum class SortType {
        ALPHABETIC, TOTAL_CHAPTERS, LAST_READ, UNREAD_COUNT, DATE_ADDED, RANDOM;

        fun getDisplayName(context: Context): String {
            return when (this) {
                ALPHABETIC     -> context.getString(R.string.sort_alphabetic)
                TOTAL_CHAPTERS -> context.getString(R.string.sort_total_chapters)
                LAST_READ      -> context.getString(R.string.sort_last_read)
                UNREAD_COUNT   -> context.getString(R.string.sort_unread_count)
                DATE_ADDED     -> context.getString(R.string.sort_date_added)
                RANDOM         -> context.getString(R.string.sort_random)
            }
        }
    }


    companion object {
        private const val KEY_LAST_UPDATED  = "library_last_updated"
        private const val KEY_ITEMS_PER_ROW = "library_items_per_row"
        private const val KEY_SHOW_DETAILS  = "library_show_details"
        private const val KEY_SHOW_BUTTONS  = "library_show_buttons"
        private const val KEY_SHOW_TABS  = "library_show_tabs"

        private const val KEY_SHOW_SOURCE   = "library_show_source"
        private const val KEY_SHOW_COUNT    = "library_show_count"
        private const val KEY_SORT_ASC      = "library_sort_asc"
        private const val KEY_FILTER        = "library_filter"
        private const val KEY_SORT           = "library_sort"
        private const val KEY_SEED          = "library_random_seed"

    }

    init {

        _uiState.value =_uiState.value.copy(items = State.Loading)
        viewModelScope.launch(Dispatchers.IO) {


            val savedState = UiState(
                lastUpdated = prefs.getString(KEY_LAST_UPDATED)
                    .takeIf(String::isNotBlank)
                    ?.let { LocalDateTime.parse(it) },
                filter = prefs.getString(KEY_FILTER, FilterType.ALL.name)
                    .let { FilterType.valueOf(it) },
                sort = prefs.getString(KEY_SORT, SortType.ALPHABETIC.name)
                    .let {
                        SortType.valueOf(it) },
                tabs = FilterTabs.NAN,
                ascending = prefs.getBoolean(KEY_SORT_ASC, true),
                showDetails = prefs.getBoolean(KEY_SHOW_DETAILS, true),
                showButtons = prefs.getBoolean(KEY_SHOW_BUTTONS, true),
                showTabs = prefs.getBoolean(KEY_SHOW_TABS, true),
                showSource = prefs.getBoolean(KEY_SHOW_SOURCE, true),
                showCount = prefs.getBoolean(KEY_SHOW_COUNT, true),
                itemsPerRow = prefs.getInt(KEY_ITEMS_PER_ROW, 0),
                randomSeed =prefs.getLong(KEY_SEED,64464L) ,
                searchQuery = ""
            )
                _uiState.value = savedState




        // whenever any of these change, rebuild the filtered & sorted list
        combine(
            libraryRepository.getDisplayItemsFlow(),
            _uiState.map { it.filter },
            _uiState.map { it.sort },
            _uiState.map { it.ascending },
            settingsRepository.downloadedOnlyFlow,
            _uiState.map { it.searchQuery },
            _uiState.map { it.randomSeed }

        ) { values: Array<Any?> ->
            LibraryParams(
                list = values[0] as List<MangaDisplayItem>,
                filter = values[1] as FilterType,
                sort = values[2] as SortType,
                ascending = values[3] as Boolean,
                downloadedOnly = values[4] as Boolean,
                searchQuery = values[5] as String,
                randomSeed = values[6] as Long?,
            )
        }
            .mapLatest { params ->
            // 1. Filter
            val filtered = params.list.filter { item ->
                if (params.downloadedOnly) {
                    item.downloadedCount > 0
                } else {
                    when (params.filter) {
                        FilterType.DOWNLOADED -> item.downloadedCount > 0
                        FilterType.UNREAD -> (item.totalChapters - item.readCount) > 0
                        FilterType.STARTED -> item.readCount > 0
                        FilterType.BOOKMARKED -> item.bookmarkedCount > 0
                        FilterType.COMPLETED -> item.totalChapters > 0 && item.readCount == item.totalChapters
                        else -> true
                    }
                }
            }.filter { item ->
                val matchTab = when (_uiState.value.tabs) {
                    FilterTabs.LIKED -> item.manga.isLiked
                    FilterTabs.WATCHING_NOW -> item.manga.isWatchingNow
                    else -> true
                }
                matchTab && item.manga.title.contains(params.searchQuery, ignoreCase = true)
            }

            // 2. Sort
            val sorted = when (params.sort) {
                SortType.ALPHABETIC -> filtered.sortedBy { it.manga.title.lowercase() }
                SortType.TOTAL_CHAPTERS -> filtered.sortedBy { it.totalChapters }
                SortType.LAST_READ -> {
                    // requires lastReadTs in MangaDisplayItem
                    filtered.sortedBy { it.manga.lastOpenTimestamp ?: 0L }
                }
                SortType.UNREAD_COUNT -> filtered.sortedBy { it.totalChapters - it.readCount }
                SortType.DATE_ADDED -> filtered.sortedBy { it.manga.savedTimestamp }
                SortType.RANDOM -> {
                    params.randomSeed?.let { seed ->
                        filtered.shuffled(Random(seed))
                    } ?: filtered.shuffled()
                }
            }
            val finalList = if (params.sort == SortType.RANDOM || params.ascending) sorted else sorted.asReversed()
            finalList
        }
            .flowOn(Dispatchers.Default)
            .onEach { displayList ->
                withContext(Dispatchers.Main) {
                _uiState.update { it.copy(items = State.Success(displayList)) }
                }
            }
            .catch { e ->
                _uiState.update { it.copy(errorMessage = e.message) }
            }
            .launchIn(viewModelScope)
        }
    }

    fun toggleLiked(manga: SavedMangaEntity) {
        val updated = manga.copy(isLiked = !manga.isLiked)
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepository.updateManga(updated)
        }
    }

    fun toggleWatchingNow(manga: SavedMangaEntity) {
        val updated = manga.copy(isWatchingNow = !manga.isWatchingNow)
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepository.updateManga(updated)
        }
    }
    val lastUpdatedFlow: StateFlow<LocalDateTime?> =
        prefs.stringPrefFlow(KEY_LAST_UPDATED)
            .map { raw ->
                raw.takeIf { it.isNotBlank() }
                    ?.let { LocalDateTime.parse(it) }
            }.flowOn(Dispatchers.IO)
            .stateIn(
                scope       = viewModelScope,
                started     = SharingStarted.Companion.WhileSubscribed(5_000),
                initialValue = null
            )

    val lastUpdated: StateFlow<LocalDateTime?> = lastUpdatedFlow




    // receiver‐lambda: 'this' is the UiState, no 'it'
    fun onFilterChanged(new: FilterType) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.putString(KEY_FILTER, new.name)
        }
        updateState { copy(filter = new) }
    }

    fun onSortChanged(new: SortType) {
        if (new == SortType.RANDOM) {
            val seed = System.currentTimeMillis()
            _uiState.update { it.copy(sort = new, randomSeed = seed) }

            viewModelScope.launch(Dispatchers.IO) {
                prefs.putLong(KEY_SEED, seed)
                prefs.putString(KEY_SORT, new.name)
            }
        } else {
            _uiState.update { it.copy(sort = new) }
            viewModelScope.launch(Dispatchers.IO) {
                prefs.putString(KEY_SORT, new.name)
            }
        }
    }

    fun onSortDirectionChanged(asc: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {

            prefs.putBoolean(KEY_SORT_ASC, asc)
        }
        updateState { copy(ascending = asc) }
    }

    fun onSearchChanged(q: String) =
        updateState { copy(searchQuery = q) }


    fun onToggleDetails(show: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {

        prefs.putBoolean(KEY_SHOW_DETAILS, show)
        }
        updateState { copy(showDetails = show) }
    }


    fun onToggleButtons(show: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {

            prefs.putBoolean(KEY_SHOW_BUTTONS, show)
        }
        updateState { copy(showButtons = show) }
    }



    // small helper to update uiState
    private fun updateState(block: UiState.() -> UiState) {
        _uiState.update(block)
    }
    // in LibraryViewModel, alongside your other onToggle… methods:
    fun onToggleSource(show: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {

            prefs.putBoolean(KEY_SHOW_SOURCE, show)
        }
        updateState { copy(showSource = show) }

    }

    fun onToggleCount(show: Boolean) {

            prefs.putBoolean(KEY_SHOW_COUNT, show)
            updateState { copy(showCount = show) }

    }

    fun onToggleTabs(show: Boolean) {

        prefs.putBoolean(KEY_SHOW_TABS, show)
        updateState { copy(showTabs = show) }

    }
    fun onItemsPerRowChange(count: Int) {

        prefs.putInt(KEY_ITEMS_PER_ROW, count)
        updateState { copy(itemsPerRow = count) }

    }


    fun removeManga(mangaId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            mangaRepository.removeMangaById(mangaId)
        }
    }

//    fun getChaptersFlow(mangaId: Long): Flow<List<SavedChapterEntity>> =
//        libraryRepository
//            .getChaptersByMangaId(mangaId.toString())
//            .flowOn(Dispatchers.IO)

    /** ----- private models & helpers ----- */
    private data class LibraryParams(
        val list: List<MangaDisplayItem>,
        val filter: FilterType,
        val sort: SortType,
        val ascending: Boolean,
        val downloadedOnly: Boolean,
        val searchQuery: String,
        val randomSeed: Long? = null,          // <— new

    )

    private data class ItemMetrics(
        val manga: SavedMangaEntity,
        val total: Int,
        val read: Int,
        val downloaded: Int,
        val bookmarked: Int,
        val lastReadTs: Long?,
        val fetchDate: LocalDateTime
    ) {
        companion object {
            fun from(manga: SavedMangaEntity, chapters: List<SavedChapterEntity>, fetchDate: LocalDateTime) =
                ItemMetrics(
                    manga = manga,
                    total = chapters.size,
                    read = chapters.count { it.isRead },
                    downloaded = chapters.count { it.isDownloaded },
                    bookmarked = chapters.count { it.isBookmarked },
                    lastReadTs = chapters.maxOfOrNull { it.lastReadDate } ?: 0L,
                    fetchDate = fetchDate
                )
        }
    }

    private fun List<ItemMetrics>.filterBy(filter: FilterType, downloadedOnly: Boolean) = when {
        downloadedOnly -> filter { it.downloaded > 0 }
        else -> when (filter) {
            FilterType.DOWNLOADED -> filter { it.downloaded > 0 }
            FilterType.UNREAD -> filter { it.total - it.read > 0 }
            FilterType.STARTED -> filter { it.read > 0 }
            FilterType.BOOKMARKED -> filter { it.bookmarked > 0 }
            FilterType.COMPLETED -> filter { it.total > 0 && it.read == it.total }
            else                   -> this
        }
    }

    private fun List<ItemMetrics>.sortedBy(sort: SortType, asc: Boolean,   randomSeed: Long?
    ) = run {
        val base = when (sort) {
            SortType.ALPHABETIC -> sortedBy { it.manga.title.lowercase() }
            SortType.TOTAL_CHAPTERS -> sortedBy { it.total }
            SortType.LAST_READ -> sortedBy { it.lastReadTs }
            SortType.UNREAD_COUNT -> sortedBy { it.total - it.read }
            SortType.DATE_ADDED -> sortedBy { it.manga.savedTimestamp }
            SortType.RANDOM -> {
                randomSeed
                    ?.let { shuffled(Random(it)) }  // stable shuffle
                    ?: shuffled()
            }
        }
        if (sort == SortType.RANDOM || asc) base else base.asReversed()
    }


    fun buildImageRequest (context : Context, url :String, api : String): ImageRequest {
        return sourcesRepository.getRepoByName(api).buildImageRequest(context,url,0)
    }

    suspend fun buildItemsImageRequest(
        context: Context,
        url: String,
        api: String
    ): ImageRequest = withContext(Dispatchers.IO) {
        sourcesRepository.getRepoByName(api)
            .buildItemsImageRequest(context, url, 0)
    }

}