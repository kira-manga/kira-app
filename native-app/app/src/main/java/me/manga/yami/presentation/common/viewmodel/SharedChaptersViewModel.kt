package me.manga.yamiapk.presentation.common.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.mapToReaderChapters
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toReaderChapters
import me.manga.yamiapk.data.local.entity.ChapterNotification
import me.manga.yamiapk.data.local.entity.HistoryItemD
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.ReaderChapters
import me.manga.yamiapk.presentation.features.home.data.ApiTitle
import me.manga.yamiapk.presentation.features.library.domain.LibraryRepository
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import javax.inject.Inject

@HiltViewModel
class SharedChaptersViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val state: SavedStateHandle,
private val sourcesRepository: SourcesRepository
    ) : ViewModel() {
    private val _chaptersList = MutableStateFlow<List<ReaderChapters>>(emptyList())
    val chaptersList: StateFlow<List<ReaderChapters>> = _chaptersList
    // holds the currently selected history item for navigation
    private val _currentHistoryItem = MutableStateFlow<HistoryItemD?>(null)
    val currentHistoryItem: StateFlow<HistoryItemD?> = _currentHistoryItem

    suspend fun getIdByUrl(url: String)=libraryRepository.getIdByUrl( url)
    suspend fun getIdByApiTitle(key: ApiTitle)=libraryRepository.getIdByApiTitle( key)



    suspend fun isMangaExists (id: Long): Boolean = libraryRepository.isMangaExists(id)


    fun setCurrentHistoryItem(item: HistoryItemD) {
        _currentHistoryItem.value = item
    }
    fun setSavedToReaderChaptersList(items : List<SavedChapterEntity>, mangaName:String){

        _chaptersList.value = items.mapToReaderChapters { it.toReaderChapters(mangaName) }
    }

    fun setChaptersToReaderChaptersList(items : List<ChapterItem>, mangaName:String){
        _chaptersList.value = items.mapToReaderChapters { it.toReaderChapters(mangaName) }

    }


    fun getChaptersByNotificationItem(chapterNotification: ChapterNotification) {
        clearChaptersList()
        viewModelScope.launch(Dispatchers.IO) {


            val chapters =
                libraryRepository.getChaptersByMangaId(chapterNotification.mangaId).first()

            setSavedToReaderChaptersList(chapters, chapterNotification.mangaTitle)


        }


    }

        fun clearChaptersList() {
        _chaptersList.value = emptyList()
    }



    override fun onCleared() {

        super.onCleared()
        _chaptersList.value = listOf()
    }


    fun getChaptersList(mangaUrl : String): Flow<State<List<ReaderChapters>>> {
        return flow {
            val chaptersState= state.get<List<ReaderChapters>>(mangaUrl.toString())


            if (chaptersState != null)
            {

                emit(State.Success(chaptersState))

            }else{
                if ( _chaptersList.value.size< 1500){
                    state.set(mangaUrl, _chaptersList.value)
                }
                emit(State.Success(_chaptersList.value))
            }
        }
    }

    fun clearSavedStateHandle() {
        state.clear()
    }

    fun SavedStateHandle.clear() {
        // copy the keys to avoid concurrent modification
        val allKeys = keys().toList()
        for (key in allKeys) {

            remove<Any?>(key)
        }
    }

    /**
     * Fetches chapters for the given HistoryItem (either from local DB or remote),
     * maps them to ReaderChapters, and returns the result as a List<ReaderChapters>.
     *
     * Call this from a coroutine (e.g. inside viewModelScope.launch { ... }).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getChaptersByHistoryItemFlow(
        historyItem: HistoryItemD
    ): Flow<State<List<ReaderChapters>>> {
        return flowOf(historyItem)
            .flatMapLatest { item ->
                flow {
                    // 1) Immediately emit Loading
                        emit(State.Loading)

                        if (item.mangaId != 0L) {



                            // ───── Local-DB path ─────
                            val savedEntities = libraryRepository
                                .getChaptersByMangaId(item.mangaId)
                                .first() // safe, finite Room query

                            val mapped = savedEntities.mapToReaderChapters {
                                it.toReaderChapters(item.mangaTitle)
                            }

                            // 2) Emit exactly one Success for local data, then return
                            emit(State.Success(mapped))
                                return@flow

                            }





                    // ───── Remote path ─────
                    // We’ll “take(1)” non-Loading from fetchMangaChaptersF(...) then emit once and return.

                    try {

                        val savedChaptersState = state.get<List<ReaderChapters>>(item.mangaUrl.toString())

                        if (savedChaptersState != null) {

                            emit(State.Success(savedChaptersState))

                            return@flow

                        } else{
                            state
                        sourcesRepository
                            .getRepoByName(item.api)
                            .fetchMangaChaptersF(item.mangaUrl)
                            .collect { innerState ->
                                when (innerState) {
                                    is State.Success -> {
                                        // Transform MangaInfo → List<ReaderChapters>
                                        val chapList = innerState.toData()!!.chapters
                                        val mapped = chapList.mapToReaderChapters {
                                            it.toReaderChapters(item.mangaTitle)
                                        }.reversed()

                                        if (mapped.size < 1500) {
                                            state.set<List<ReaderChapters>>(
                                                item.mangaUrl.toString(),
                                                mapped
                                            )
                                        }
                                        emit(State.Success(mapped))
                                    }

                                    is State.Error -> {
                                        emit(State.Error(0, innerState.message))
                                    }

                                    is State.Loading -> {
                                        // This should never happen, since filterNot removed Loading.
                                        emit(State.Loading)
                                    }
                                }
                                // After emitting once, return so the outer flow completes
                                return@collect
                            }

                    }
                    } catch (e: Throwable) {
                        // If fetchMangaChaptersF throws, turn it into a State.Error
                        emit(State.Error(0, e.message ?: "Unknown error while fetching chapters"))
                        return@flow
                    }

                }
            }
            .flowOn(Dispatchers.IO)
    }



    fun retryFetchChapters( historyItem: HistoryItemD){

    }
}