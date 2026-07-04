package me.manga.yamiapk.presentation.features.statistics.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import me.manga.yamiapk.presentation.features.statistics.domain.StatisticsRepository
import javax.inject.Inject

@HiltViewModel
class StatisticsViewModel @Inject constructor(
      repository: StatisticsRepository
) : ViewModel() {

    val inLibrary: StateFlow<Int> = repository.inLibraryFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, 0,)

    val readDuration: StateFlow<String> = repository.readDurationFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, "0h 0m")

    val completedEntries: StateFlow<Int> = repository.completedEntriesFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, 0)



    val entriesStarted: StateFlow<Int> = repository.startedEntriesFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, 0)



    val chaptersTotal: StateFlow<Int> = repository.chaptersTotalFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, 0)

    val chaptersRead: StateFlow<Int> = repository.chaptersReadFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, 0)

    val chaptersDownloaded: StateFlow<Int> = repository.chaptersDownloadedFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, 0)

    val chaptersBookmarked: StateFlow<Int> = repository.chaptersBookmarkedFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, 0)



}