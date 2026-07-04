package me.manga.yamiapk.presentation.common.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.presentation.features.library.domain.LibraryRepository
import me.manga.yamiapk.presentation.features.reader.data.ReadingMode
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.yamiapk.presentation.features.settings.domain.SettingsRepository
import me.manga.yamiapk.presentation.features.statistics.domain.StatisticsRepository
import javax.inject.Inject

@HiltViewModel
class ChaptersViewModel@Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val statisticsRepo: StatisticsRepository,
    private val sourcesRepository: SourcesRepository,

    )
    : ViewModel() {



    private val _readingMode = MutableStateFlow(ReadingMode.DEFAULT)
    val readingMode: StateFlow<ReadingMode> = _readingMode.asStateFlow()

    private var currentJob: Job? = null
    private var mangaDetailsJob: Job? = null

    // StateFlow for chapter images
    private val _chapterImgs = MutableStateFlow<State<List<String>>>(State.Loading)
    val chapterImgs: StateFlow<State<List<String>>> = _chapterImgs.asStateFlow()


    private val _bookmarked = MutableStateFlow(false)
    val bookmarked: StateFlow<Boolean> = _bookmarked




    init {

        viewModelScope.launch {
            settingsRepo.readingModeFlow
                .distinctUntilChanged()
                .collect { modeString ->
                    val mode = ReadingMode.valueOf(modeString)
                    _readingMode.value = mode
                }
        }





    }






    /** Change and persist reading mode **/
    fun setReadingMode(mode: ReadingMode) {
        viewModelScope.launch {
            _readingMode.value = mode
            settingsRepo.setReadingMode(mode.name)
        }
    }





    fun getChaptersDataR(chapterUrl: String): Flow<State<MangaInfo>> = flow {
        try {
            sourcesRepository.activeRepo.first().fetchMangaChaptersF(chapterUrl).collect { images ->
                emit(images)
            }
        } catch (e: Exception) {
            emit(State.Error(0, e.message.toString()))
        }
    }



    override fun onCleared() {

        super.onCleared()


        currentJob?.cancel()
        mangaDetailsJob?.cancel()
    }

}