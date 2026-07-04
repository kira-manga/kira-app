package me.manga.yamiapk.presentation.features.download.ui.test2

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.network_connectivity.ConnectivityObserver.Status
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toSavedChapterEntity
import me.manga.yamiapk.data.local.dao.ChapterDownloadDao
import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
import me.manga.yamiapk.data.local.entity.ChapterNotification
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.presentation.features.download.data.DownloadingState
import me.manga.yamiapk.presentation.features.download.domain.clean.DownloadRepository
import javax.inject.Inject

@HiltViewModel
class DownloadViewModelv2  @Inject constructor(
    private val repository: DownloadRepository,
    private val downloadRepo: DownloadRepository,
) : ViewModel() {

    val runningChapter: StateFlow<ChapterDownloadEntity?> =
        repository.observeRunningChapter()
            .stateIn(
                scope       = viewModelScope,
                started     = SharingStarted.Lazily,
                initialValue = null
            )

    val isDownloading: StateFlow<Boolean> = repository.isDownloading()
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    @Deprecated("Use downloadsPaged instead")
    val downloads: StateFlow<List<ChapterDownloadEntity>> =
        repository.observeAllDownloads()
            .stateIn(
                scope       = viewModelScope,
                started     = SharingStarted.Lazily,
                initialValue = emptyList()
            )



    val downloadsPaged: Flow<PagingData<ChapterDownloadEntity>> =
        repository.observeAllDownloadsPaged()
            .cachedIn(viewModelScope)

    fun getDownloadsByState(states: List<DownloadingState>): Flow<PagingData<ChapterDownloadEntity>> =
        repository.observeDownloadsByStatePaged(states)
            .cachedIn(viewModelScope)

    val queuedCount: StateFlow<Int> = repository.queuedCount()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val queuedChapterIds: StateFlow<List<Long>> = repository.queuedChapterIds()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val networkAvailable: StateFlow<Status> = repository.networkStatus()
        .stateIn(viewModelScope, SharingStarted.Eagerly, Status.Lost)


    fun downloadChapterNotification(notification: ChapterNotification, mangaApi: String,title: String) {
        viewModelScope.launch(Dispatchers.IO) { repository.enqueueChapterDownload(notification.toSavedChapterEntity(), title = title, mangaApi = mangaApi) } }
    fun downloadChapter(chapter: SavedChapterEntity, mangaApi: String,title: String) {
        viewModelScope.launch(Dispatchers.IO) { repository.enqueueChapterDownload(chapter, mangaApi = mangaApi, title = title) }
    }

    fun downloadChapters(chapters: List<SavedChapterEntity>, manga: SavedMangaEntity) {
        viewModelScope.launch(Dispatchers.IO) { repository.enqueueChaptersDownload(chapters, title = manga.title, mangaApi = manga.api) }
    }


    fun deleteDownload(chapterId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteDownload(chapterId)
        }
    }
    fun cancelDownloads() {
        viewModelScope.launch(Dispatchers.IO) { repository.cancelAllDownloads() }
    }

    fun clearDownloads() {
        viewModelScope.launch (Dispatchers.IO){ repository.clearFailedAndQueued() }
    }

    fun cancelRunningDownload(chapterId: Long,mangaId: Long,){
        viewModelScope.launch(Dispatchers.IO) { repository.cancelARunningChapter(chapterId,mangaId) }

    }
    /** Called by the UI when the user taps “Cancel” on a specific chapter. */
    fun onCancelChapterTapped(chapterId: Long) {
        // 1) update the local DB state immediately for instant UI feedback:
        viewModelScope.launch(Dispatchers.IO)  {


            downloadRepo.onCancel(chapterId)

        }

        // 2) tell the Worker to stop that chapter’s Job
    }







}



