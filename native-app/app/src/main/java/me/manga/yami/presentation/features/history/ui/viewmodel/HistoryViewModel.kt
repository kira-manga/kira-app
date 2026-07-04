package me.manga.yamiapk.presentation.features.history.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.manga.yamiapk.data.local.entity.HistoryItemD
import me.manga.yamiapk.domain.repos.MangaRepository
import me.manga.yamiapk.presentation.features.history.data.HistoryUiState
import me.manga.yamiapk.presentation.features.history.domain.HistoryRepository
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.yamiapk.presentation.features.settings.domain.SettingsRepository
import java.time.LocalDateTime
import javax.inject.Inject


@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val mangaRepository: MangaRepository,
//    private val activeRepoProvider: ActiveRepoProvider,
    settingsRepository: SettingsRepository,
    private val sourcesRepository: SourcesRepository


    ) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()
    val incognitoMode: Flow<Boolean> = settingsRepository.incognitoFlow

    init {
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                historyRepository.getAllHistory()
                    .catch { e -> 
                        _uiState.update { 
                            it.copy(
                                isLoading = false,
                                error = e.message ?: "Failed to load history"
                            )
                        }
                    }
                    .collect { historyItems ->
                        _uiState.update { 
                            it.copy(
                                historyItems = historyItems.sortedByDescending { item -> item.lastReadDate },
                                isLoading = false,
                                error = null
                            )
                        }
                    }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load history"
                    )
                }
            }
        }
    }

    fun insertHistory(historyItem: HistoryItemD) {
        viewModelScope.launch(Dispatchers.IO){
            val isIncognito = incognitoMode.first()

            // If incognito is enabled, bail out and don’t insert
            if (isIncognito) return@launch
            try {
                historyRepository.insertHistory(historyItem)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Failed to insert history item")
                }
            }
        }
    }

    fun updateHistoryItem(
        id: Long,
        chapterUrl: String,
        chapterTitle: String,
        isDownloaded: Boolean,
        localImagePaths: List<String>,
        lastReadPage: Int =0,
        totalPages: Int =0
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val isIncognito = incognitoMode.first()

            // If incognito is enabled, bail out and don’t insert
            if (isIncognito) return@launch
            try {
                historyRepository.updateHistoryItem(
                    id = id,
                    chapterUrl = chapterUrl,
                    chapterTitle = chapterTitle,
                    isDownloaded = isDownloaded,
                    localImagePaths = localImagePaths,
                    lastReadDate = LocalDateTime.now(),
                    lastReadPage = lastReadPage,
                    totalPages = totalPages
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Failed to update history progress")
                }
            }
        }
    }
    // Expose this to the UI
    fun getLatestHistoryIdByManga(mangaUrl: String): Flow<Long?> =
        historyRepository.getHistoryByMangaUrl(mangaUrl)
    fun markChapterAsRead(chapterId: Long){
        viewModelScope.launch(Dispatchers.IO) {

            mangaRepository.markChapterAsRead(chapterId)

        }


        }

    fun deleteHistory(historyItemD: HistoryItemD) {
        viewModelScope.launch {
            try {
                historyRepository.deleteHistory(historyItemD)
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(error = e.message ?: "Failed to delete history item")
                }
            }
        }
    }

    fun deleteAllHistory() {
        viewModelScope.launch {
            try {
                historyRepository.deleteAllHistory()
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(error = e.message ?: "Failed to delete all history")
                }
            }
        }
    }

/*    fun mangaSavedState(mangaId: String): StateFlow<Boolean> =
        mangaRepository.isMangaSaved(mangaId)
            .stateIn(
                viewModelScope, 
                SharingStarted.WhileSubscribed(5_000), 
                false
            )*/

    fun chapterDownloadedState(url: String): StateFlow<Boolean> =
        mangaRepository.isChapterDownloaded(url)
            .stateIn(
                viewModelScope, 
                SharingStarted.WhileSubscribed(5_000), 
                false
            )


    fun buildImageRequest (context : Context, url :String, api : String): ImageRequest{
        return sourcesRepository.getRepoByName(api).buildImageRequest(context,url,0)
    }
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
} 
