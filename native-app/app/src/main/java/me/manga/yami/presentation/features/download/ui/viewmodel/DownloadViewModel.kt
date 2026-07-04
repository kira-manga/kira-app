//package me.manga.yamiapk.presentation.features.download.ui.viewmodel
//
//import android.app.Application
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.content.IntentFilter
//import android.util.Log
//import androidx.core.content.ContextCompat
//import androidx.lifecycle.ViewModel
//import androidx.lifecycle.asFlow
//import androidx.lifecycle.map
//import androidx.lifecycle.viewModelScope
//import androidx.localbroadcastmanager.content.LocalBroadcastManager
//import androidx.work.ExistingWorkPolicy
//import androidx.work.OneTimeWorkRequestBuilder
//import androidx.work.WorkInfo
//import androidx.work.WorkManager
//import androidx.work.workDataOf
//import dagger.hilt.android.lifecycle.HiltViewModel
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow
//import kotlinx.coroutines.flow.filterNotNull
//import kotlinx.coroutines.flow.flowOn
//import kotlinx.coroutines.flow.launchIn
//import kotlinx.coroutines.flow.map
//import kotlinx.coroutines.flow.onEach
//import kotlinx.coroutines.launch
//import me.manga.yamiapk.data.local.entity.ChapterNotification
//import me.manga.yamiapk.data.local.entity.SavedChapterEntity
//import me.manga.yamiapk.domain.service.DownloadService
//import me.manga.yamiapk.work.MangaDownloadWorker
//import java.util.concurrent.TimeUnit
//import javax.inject.Inject
//
//@HiltViewModel
//class DownloadViewModel @Inject constructor(
//    private val application: Application,
//    private val workManager: WorkManager
//
//) : ViewModel() {
//    companion object {
//        private const val DOWNLOAD_WORK_NAME = "mangaDownload"
//
//
//    }
//    private val _currentChapter = MutableStateFlow<Long?>(null)
//    val currentChapter: StateFlow<Long?> = _currentChapter
//
//
//
//    private val _overallProgress = MutableStateFlow(0)
//    val overallProgress: StateFlow<Int> = _overallProgress
//
//    private val _errorsCount = MutableStateFlow(0)
//    val errorsCount: StateFlow<Int> = _errorsCount
//
//    // Observe *all* WorkInfos for the unique name
//    private val downloadWorkInfos =
//        workManager.getWorkInfosForUniqueWorkLiveData(DOWNLOAD_WORK_NAME)
//
//    // 2) Map them directly with the LiveData.map extension:
//
//
//    // whether any of that chain is still enqueued or running:
//    val isWorkRunning: Flow<Boolean> = downloadWorkInfos.map { infos ->
//        infos.any { it.state == WorkInfo.State.ENQUEUED ||
//                it.state == WorkInfo.State.RUNNING }
//    }.asFlow()
//
//    private val _downloadingChapters = MutableStateFlow<Set<Long>>(emptySet())
//    val downloadingChapters: StateFlow<Set<Long>> = _downloadingChapters
//
//    private val _downloadingNotificationChapters = MutableStateFlow<Set<Long>>(emptySet())
//    val downloadingNotificationChapters: StateFlow<Set<Long>> = _downloadingNotificationChapters
//
//    private val _error = MutableStateFlow<String?>(null)
//    val error: StateFlow<String?> = _error
//
//    private val _chaptersFinished = MutableStateFlow(0)
//    val chaptersFinished: StateFlow<Int> = _chaptersFinished
//
//    private var downloadAllJob: Job? = null
//
//
//
//
//    private val downloadCompleteReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            when (intent?.action) {
//                DownloadService.Companion.ACTION_CHAPTER_DOWNLOAD_COMPLETE -> {
//                    val chapterId = intent.getLongExtra(DownloadService.Companion.EXTRA_CHAPTER_ID, -1)
//                    if (chapterId != -1L) {
//                        onDownloadComplete(chapterId)
//                    }
//                }
//                DownloadService.Companion.ACTION_CHAPTER_DOWNLOAD_ERROR -> {
//                    val chapterId = intent.getLongExtra(DownloadService.Companion.EXTRA_CHAPTER_ID, -1)
//                    if (chapterId != -1L) {
//                        onDownloadError(chapterId)
//                    }
//                }
//            }
//        }
//    }
//
//    private val notificationReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            when (intent?.action) {
//                DownloadService.Companion.ACTION_NOTIFICATION_DOWNLOAD_COMPLETE -> {
//                    val notificationId = intent.getLongExtra(DownloadService.Companion.EXTRA_NOTIFICATION_ID, -1)
//                    if (notificationId != -1L) {
//                        onDownloadNotificationComplete(notificationId)
//
//                    }
//                }
//                DownloadService.Companion.ACTION_NOTIFICATION_DOWNLOAD_ERROR -> {
//                    val notificationId = intent.getLongExtra(DownloadService.Companion.EXTRA_NOTIFICATION_ID, -1)
//                    if (notificationId != -1L) {
//                        onDownloadNotificationError(notificationId)
//                    }
//                }
//            }
//        }
//    }
//
//
//    private val broadcastReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            intent ?: return
//            val id = intent.getLongExtra(MangaDownloadWorker.Companion.EXTRA_CHAPTER_ID, -1L)
//            when (intent.action) {
//                MangaDownloadWorker.Companion.ACTION_CHAPTER_COMPLETE -> {
//                    viewModelScope.launch {   onDownloadComplete(id) }
//                }
//                MangaDownloadWorker.Companion.ACTION_CHAPTER_ERROR -> {
//                    viewModelScope.launch {  onDownloadComplete(id) }
//                }
//            }
//        }
//    }
//
//    init {
//
//
//        workManager
//            .getWorkInfosForUniqueWorkLiveData(DOWNLOAD_WORK_NAME)
//            .asFlow()
//            .map { infos ->
//                // You’ll typically only have one WorkInfo for the unique chain:
//                infos.firstOrNull()
//            }
//            .filterNotNull()
//            .onEach { info ->
//                val prog = info.progress
//                val currentId   = prog.getLong(MangaDownloadWorker.Companion.KEY_CHAPTER_ID, -1L)
//                val doneCount   = prog.getInt(MangaDownloadWorker.Companion.KEY_COMPLETED_COUNT, 0)
//                val percent     = prog.getInt(MangaDownloadWorker.Companion.KEY_PROGRESS, 0)
//                val errorCount  = prog.getInt(MangaDownloadWorker.Companion.KEY_ERROR_COUNT, 0)
//
//
//
//                // emit to UI
//                _currentChapter.value      = if (currentId >= 0) currentId else null
//                _chaptersFinished.value    = doneCount
//                _overallProgress.value     = percent
//                _errorsCount.value         = errorCount
//
//
//                when (info.state) {
//
//                    WorkInfo.State.SUCCEEDED -> {
//                        resetState()
//
//                    }
//                    WorkInfo.State.FAILED -> {
//                        resetState()
//                    }
//                    WorkInfo.State.CANCELLED -> {
//                        resetState()
//                    }
//                    else -> {
//                    }
//                }
//            }.flowOn(Dispatchers.IO)
//            .launchIn(viewModelScope)
//
//
//        LocalBroadcastManager.getInstance(application).registerReceiver(downloadCompleteReceiver,
//            IntentFilter().apply {
//            addAction(DownloadService.Companion.ACTION_CHAPTER_DOWNLOAD_COMPLETE )
//            addAction(DownloadService.Companion.ACTION_CHAPTER_DOWNLOAD_ERROR    )
//        })
//
//        LocalBroadcastManager.getInstance(application).registerReceiver(notificationReceiver, IntentFilter().apply {
//            addAction(DownloadService.Companion.ACTION_NOTIFICATION_DOWNLOAD_COMPLETE)
//            addAction(DownloadService.Companion.ACTION_NOTIFICATION_DOWNLOAD_ERROR)
//        })
//        LocalBroadcastManager.getInstance(application).registerReceiver(
//            broadcastReceiver,
//            IntentFilter().apply {
//                addAction(MangaDownloadWorker.Companion.ACTION_CHAPTER_COMPLETE)
//                addAction(MangaDownloadWorker.Companion.ACTION_CHAPTER_ERROR)
//            }
//        )
//    }
//
//    fun downloadChapter(chapter: SavedChapterEntity) {
//        _downloadingChapters.value += chapter.id
//        val intent = Intent(application, DownloadService::class.java).apply {
//            putExtra(DownloadService.Companion.EXTRA_CHAPTER_ID, chapter)
//        }
//        ContextCompat.startForegroundService(application, intent)
//    }
//
//    fun downloadNotificationChapter(notification: ChapterNotification) {
//        _downloadingNotificationChapters.value += notification.id
//        val intent = Intent(application, DownloadService::class.java).apply {
//            putExtra(DownloadService.Companion.EXTRA_NOTIFICATION_ID, notification)
//        }
//        ContextCompat.startForegroundService(application, intent)
//    }
//
//
//    private fun onDownloadNotificationComplete(notificationId: Long) {
//
//        _downloadingNotificationChapters.value -= notificationId
//    }
//
//    private fun onDownloadNotificationError(notificationId: Long) {
//        _downloadingNotificationChapters.value -= notificationId
//        _error.value = "Error downloading chapter $notificationId"
//    }
//
//    private fun onDownloadComplete(chapterId: Long) {
//        _downloadingChapters.value -= chapterId
//    }
//
//    private fun onDownloadError(chapterId: Long) {
//        _downloadingChapters.value -= chapterId
//        _error.value = "Error downloading chapter $chapterId"
//    }
//
//
//
//    override fun onCleared() {
//        super.onCleared()
//        downloadAllJob?.cancel()
//        LocalBroadcastManager.getInstance(application).unregisterReceiver(downloadCompleteReceiver)
//        LocalBroadcastManager.getInstance(application).unregisterReceiver(broadcastReceiver)
//
//    }
//
//
//
//    fun downloadChapters(chapterIds: List<Long>) {
//        viewModelScope.launch {
//            _downloadingChapters.value =chapterIds.toSet()
//
//            val request = OneTimeWorkRequestBuilder<MangaDownloadWorker>()
//            .setInputData(workDataOf("KEY_URLS" to chapterIds.toLongArray()))
//            .setInitialDelay(5, TimeUnit.SECONDS)
//            .build()
//
//        workManager.enqueueUniqueWork(
//            DOWNLOAD_WORK_NAME,
//            ExistingWorkPolicy.KEEP,
//            request
//        )
//        }
//    }
//
//
//    private fun resetState() {
//        _currentChapter.value        = null
//        _overallProgress.value       = 0
//        _downloadingChapters.value   = emptySet()
//        _chaptersFinished.value      = 0
//        _error.value                 = null
//    }
//    /** Optional: cancel the running download. */
//    fun cancelDownload() {
//        workManager.cancelUniqueWork(DOWNLOAD_WORK_NAME)
//        // Also clear state flows
//        resetState()
//    }
//}