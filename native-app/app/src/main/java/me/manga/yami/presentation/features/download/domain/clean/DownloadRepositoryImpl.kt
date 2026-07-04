package me.manga.yamiapk.presentation.features.download.domain.clean

import android.app.Application
import android.content.Context
import androidx.lifecycle.asFlow
import androidx.lifecycle.map
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.R
import me.manga.yamiapk.core.network_connectivity.ConnectivityObserver
import me.manga.yamiapk.core.network_connectivity.ConnectivityObserver.Status
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toChapterDownloadEntities
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toChapterDownloadEntity
import me.manga.yamiapk.data.local.dao.ChapterDownloadDao
import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.presentation.features.download.data.DownloadingState
import me.manga.yamiapk.presentation.features.download.domain.ChapterDownloadService
import me.manga.yamiapk.presentation.features.download.ui.test2.DownloadWorkerV2
import javax.inject.Inject

class DownloadRepositoryImpl @Inject constructor(
    private val application: Application,
    private val workManager: WorkManager,
    private val dao: ChapterDownloadDao,
    private val connectivityObserver: ConnectivityObserver,
    private val chapterDownloadService: ChapterDownloadService,
    @ApplicationContext private val context: Context,
    ) : DownloadRepository {


    companion object {
        private const val WORK_NAME = "mangaDownloadv2"
        private const val PAGE_SIZE = 50
        private const val PREFETCH_DISTANCE = 15

    }

    override fun isDownloading() =
        workManager.getWorkInfosForUniqueWorkLiveData(WORK_NAME)
            .map { infos ->
            infos.any { it.state == WorkInfo.State.ENQUEUED ||
                    it.state == WorkInfo.State.RUNNING }
        }.asFlow()

    override fun observeRunningChapter(): Flow<ChapterDownloadEntity?> = dao.observeRunningChapter()


    override fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>> = dao.observeAllDownloads()
    override fun queuedCount(): Flow<Int> = dao.getQueuedCount()
    override fun queuedChapterIds(): Flow<List<Long>> = dao.getAllQueuedChapterIds()
    override fun networkStatus():  Flow<Status> = connectivityObserver.observe()

    private fun enqueueRequest() = OneTimeWorkRequestBuilder<DownloadWorkerV2>()
        .setConstraints(Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build())
        .build().also { workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, it) }

    override suspend fun enqueueChapterDownload(chapter: SavedChapterEntity, title : String, mangaApi: String) {
        dao.insert(chapter.toChapterDownloadEntity(apiName = mangaApi, title = title))?.let { enqueueRequest() }
    }
    override suspend fun deleteDownload(chapterId: Long) {
        dao.deleteByChapterId(chapterId)
    }
    override suspend fun enqueueChaptersDownload(chapters: List<SavedChapterEntity>, title : String, mangaApi: String) {
        if (dao.insertAll(chapters.toChapterDownloadEntities(apiName = mangaApi, title = title)).isNotEmpty()) {
            enqueueRequest()
        }
    }


    override suspend fun onCancel(chapterId: Long) {

        dao.updateFailure(chapterId, context.getString(R.string.cancelled_by_user))

    }

    override fun observeAllDownloadsPaged(): Flow<PagingData<ChapterDownloadEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PREFETCH_DISTANCE,
                enablePlaceholders = false,
                initialLoadSize = PAGE_SIZE
            ),
            pagingSourceFactory = { dao.observeAllDownloadsPaged() }
        ).flow
    }

    override fun observeDownloadsByStatePaged(states: List<DownloadingState>): Flow<PagingData<ChapterDownloadEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PREFETCH_DISTANCE,
                enablePlaceholders = false,
                initialLoadSize = PAGE_SIZE
            ),
            pagingSourceFactory = { dao.observeDownloadsByStatePaged(states) }
        ).flow
    }

    override suspend fun cancelAllDownloads() {
        dao.markAllRunningOrQueuedAsFailed()
        workManager.cancelUniqueWork(WORK_NAME)
    }

    override suspend fun cancelARunningChapter(chapterId: Long, mangaId: Long) {
        workManager.cancelUniqueWork(WORK_NAME)
        chapterDownloadService.deleteChapterFiles(mangaId,chapterId)
        onCancel(chapterId)
        enqueueRequest()
    }

    override suspend fun clearFailedAndQueued() {
        dao.clearByState(DownloadingState.FAILED)
        dao.clearByState(DownloadingState.QUEUED)
    }

}

