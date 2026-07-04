package me.manga.yamiapk.presentation.features.library_details.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.manga.yamiapk.R
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.states.State.Error.Companion.fromCode
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.presentation.features.library.domain.LibraryRepository
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class LibraryDetailsViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val sourcesRepository: SourcesRepository,

    ) : ViewModel() {

    // drive which manga we’re observing
    private val _mangaId = MutableStateFlow<Long?>(null)

    // the current manga details
    private val _manga = MutableStateFlow(HandelDataClasses.emptySavedMangaEntity())
    val manga: StateFlow<SavedMangaEntity> = _manga


    // Single enum-based filter state
    private val _filterType = MutableStateFlow(FilterType.ALL)
    val filterType: StateFlow<FilterType> = _filterType
    private val _sortType      = MutableStateFlow(SortType.ID)
    val sortType: StateFlow<SortType> = _sortType


    private val _error         = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _sortAscending = MutableStateFlow(false)
    val sortAscending: StateFlow<Boolean> = _sortAscending

    // 1) derive a stream of API strings, skipping the empty default
    private val apiStream = _manga
        .map { it.api to it.url } // emit a Pair(api, url)
        .distinctUntilChanged()
        .filter { (api, url) -> api.isNotBlank() && url.isNotBlank() }

    // 2) for each api, call fetchImageStatus exactly once, cache it in a StateFlow
    val imageStatus: StateFlow<State<Boolean>> = apiStream
        .flatMapLatest { (api, url) ->

            getSiteStatus(api, url) // pass both to your function
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Companion.WhileSubscribed(5_000),
            initialValue = State.Loading
        )



    @OptIn(ExperimentalCoroutinesApi::class)
    val chapters: StateFlow<List<SavedChapterEntity>> =
        combine(
            _mangaId.filterNotNull(),
            _sortAscending,
            _filterType,
            _sortType
        ) { mangaId, asc, filter, sort ->
            FilterSortParams(mangaId, asc, filter, sort)
        }
            .flatMapLatest { (mangaId, asc, filter, sort) ->
                libraryRepository.getChaptersByMangaId(mangaId)
                    .map { list ->
                        // 1) Filter
                        val filtered = when (filter) {
                            FilterType.ALL        -> list
                            FilterType.DOWNLOADED -> list.filter { it.isDownloaded }
                            FilterType.UNREAD     -> list.filter { !it.isRead }
                            FilterType.READED     -> list.filter { it.isRead }
                            FilterType.BOOKMARKED -> list.filter { it.isBookmarked }
                        }

                        // 2) Sort
                        val sorted = when (sort) {
                            SortType.ID             -> filtered.sortedBy { it.id }
                            SortType.NUMBER         -> filtered.sortedBy { it.number.toDoubleOrNull() ?: 0.0 }
                            SortType.DATE           -> filtered.sortedBy { it.date ?: LocalDate.MIN }
                            SortType.LAST_READ_DATE -> filtered.sortedBy { it.lastReadDate }
                        }

                        // 3) Ascending or Descending
                        if (asc) sorted else sorted.reversed()
                    }
            }
            .flowOn(Dispatchers.IO)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Lazily,
                initialValue = emptyList()
            )


    enum class FilterType {
        ALL, DOWNLOADED, UNREAD, READED, BOOKMARKED, ;

        fun getDisplayName(context: Context): String {
            return when (this) {
                ALL        -> context.getString(R.string.filter_all)
                DOWNLOADED -> context.getString(R.string.filter_downloaded)
                UNREAD     -> context.getString(R.string.filter_unread)
                READED    -> context.getString(R.string.filter_readed)
                BOOKMARKED -> context.getString(R.string.filter_bookmarked)
            }
        }
    }
    enum class SortType {
        ID,  NUMBER, DATE,LAST_READ_DATE,;

        fun getDisplayName(context: Context): String {
            return when (this) {
                ID     -> "Id"
                NUMBER      -> "Number"
                DATE -> "Date"
                LAST_READ_DATE   -> "Last Read Date"
            }
        }
    }

    fun setFilter(type: FilterType) {
        _filterType.value = type
    }

    fun setSortType(type: SortType) {
        _sortType.value = type
    }


    fun loadMangaDetails(mangaId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepository.getMangaById(mangaId)?.let { m ->
                _manga.value   = m
                _mangaId.value = m.id

                initializeSite()

            }
        }
    }


    fun initializeSite() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentManga = manga.value
                if (currentManga.api.isNotBlank()) {
                    sourcesRepository.getRepoByName(currentManga.api).initSite()
                    Log.d("LibraryDetailsViewModel", "Site initialized for: ${currentManga.api}")
                }
            } catch (e: Exception) {
                Log.e("LibraryDetailsViewModel", "Failed to initialize site", e)
                _error.value = "Failed to initialize site: ${e.message}"
            }
        }
    }


    fun getSiteStatus(api: String, url: String): Flow<State<Boolean>> = flow {

        Log.i("sdflkjasdklfjsdfsdfasdfsd","getSiteStatus   $api ============ $url")
//        if (api != "Lekmanga") {
//            return@flow
//        } else {
            emit(State.Loading)

            try {
                 val client = OkHttpClient()

                val repo = sourcesRepository.getRepoByName(api)
                // 1) build headers (including your cached ones + referer)
                val headers = Headers.Builder().apply {
                    repo.defaultHeaders.forEach { (name, value) -> add(name, value) }
                }.build()

                // 2) build & execute request
                val request = Request.Builder()
                    .url(url)
                    .headers(headers)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        emit(State.Success(true))
                    } else {

                        emit(fromCode(response.code))
                    }
                }
            } catch (e: Exception) {

                emit(State.Error(0, e.localizedMessage ?: "Unknown error"))
            }

//        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = State.Loading
        )

    fun toggleSort() {
        // synchronous flip → immediate re-sort
        _sortAscending.value = !_sortAscending.value
    }

    fun refreshChapters() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            try {
                val current = manga.value
                if (current.id == 0L) return@launch

                sourcesRepository.getRepoByName(current.api)
                    .fetchMangaChaptersF(current.url)
                    .collect { state ->
                        state.toData()?.let { mangaInfo ->
                            val updatedManga = current.copy(
                                imageUrl = mangaInfo.imageUrl,
                                title = mangaInfo.title,
                                description = mangaInfo.description,
                                status = mangaInfo.status,
                                genres = mangaInfo.genres
                            )
                            if (mangaInfo.imageUrl != manga.value.imageUrl) {
                                libraryRepository.updateMangaImageUrlEverywhere(manga.value.id, mangaInfo.imageUrl)
                            }


                            // Update local state so UI reflects changes immediately
                            _manga.value = updatedManga

                            // 2. Process chapters
                            val local = libraryRepository
                                .getChaptersByMangaId(current.id)
                                .first()

                            val toInsert = mangaInfo.chapters
                                .filter { rc -> local.none { it.url == rc.url } }
                                .map { new ->
                                    SavedChapterEntity(
                                        mangaId = current.id,
                                        name = new.name,
                                        number = new.number,
                                        url = new.url,
                                        date = new.date ?: LocalDate.now(),
                                        isNew = true
                                    )
                                }.reversed()

                            if (toInsert.isNotEmpty()) {
                                libraryRepository.insertChapters(toInsert)
                            }
                        }
                    }
            } catch (e: Throwable) {
                _error.value = "Failed to refresh: ${e.message}"
                Log.e("LibraryDetailsViewModel", "Error refreshing chapters", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }



    // In your ViewModel
    fun toggleChaptersBookmark(chapterIds: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepository.toggleChaptersBookmark(chapterIds)
        }
    }

    fun toggleChaptersRead(chapterIds: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepository.toggleChaptersRead(chapterIds)
        }
    }
    fun markChaptersRead(chapterIds: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepository.markChaptersRead(chapterIds)
        }
    }

    fun toggleChapterBookmark(chapterId: Long) {
        viewModelScope.launch (Dispatchers.IO){
            libraryRepository.toggleChapterBookmark(chapterId)
        }
    }

    fun updateLastOpen(mangaId: Long){
        viewModelScope.launch (Dispatchers.IO){

            libraryRepository.updateLastOpenTimestamp(mangaId)
        }
    }

    fun deleteAllChapters(chapters: Set<SavedChapterEntity>) =
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepository.deleteDownloadedChapters(chapters)
        }

    fun toggleChapterRead(chapterId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepository.toggleChapterRead(chapterId)
        }
    }

    fun setReadChapter(chapter: SavedChapterEntity) {
        viewModelScope.launch {
            libraryRepository.markChapterAsRead(chapter.id)
        }
    }

    fun setIsNewChapter(chapter: SavedChapterEntity) {
        viewModelScope.launch {
            libraryRepository.markChapterIsNew(chapter.id)
        }
    }
    fun deleteChapter(chapter: SavedChapterEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepository.deleteChapter(chapter)
        }
    }
    override fun onCleared() {
        super.onCleared()
    }
    private data class FilterParams(
        val mangaId: Long,
        val asc: Boolean,
        val onlyRead: Boolean,
        val onlyBookmarked: Boolean,
        val onlyDownloaded: Boolean
    )
    private data class FilterSortParams(
        val mangaId: Long,
        val asc: Boolean,
        val filter: FilterType,
        val sort: SortType
    )

}