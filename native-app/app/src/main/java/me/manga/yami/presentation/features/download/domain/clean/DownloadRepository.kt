package me.manga.yamiapk.presentation.features.download.domain.clean

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.core.network_connectivity.ConnectivityObserver.Status
import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.presentation.features.download.data.DownloadingState

interface DownloadRepository {

    fun observeRunningChapter(): Flow<ChapterDownloadEntity?>

    fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>>
    fun isDownloading():Flow<Boolean>
    fun queuedCount(): Flow<Int>
    fun queuedChapterIds(): Flow<List<Long>>

    fun networkStatus():   Flow<Status>
    suspend fun enqueueChapterDownload(chapter: SavedChapterEntity, title : String, mangaApi: String)
    suspend fun enqueueChaptersDownload(chapters: List<SavedChapterEntity>,title : String,  mangaApi: String)
    suspend fun deleteDownload(chapterId: Long)

    suspend fun onCancel(chapterId : Long)
    // NEW: Paged version
    fun observeAllDownloadsPaged(): Flow<PagingData<ChapterDownloadEntity>>
    fun observeDownloadsByStatePaged(states: List<DownloadingState>): Flow<PagingData<ChapterDownloadEntity>>

    suspend fun cancelAllDownloads()
    suspend fun cancelARunningChapter(chapterId : Long,mangaId : Long)
    suspend fun clearFailedAndQueued()
}