//package me.manga.yamiapk.domain.service
//
//import android.app.Notification
//import android.app.NotificationChannel
//import android.app.NotificationManager
//import android.app.Service
//import android.content.Context
//import android.content.Intent
//import android.os.Build
//import android.os.IBinder
//import android.util.Log
//import androidx.core.app.NotificationCompat
//import androidx.localbroadcastmanager.content.LocalBroadcastManager
//import dagger.hilt.android.AndroidEntryPoint
//import dagger.hilt.android.qualifiers.ApplicationContext
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.SupervisorJob
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.flow.catch
//import kotlinx.coroutines.flow.onEach
//import kotlinx.coroutines.launch
//import me.manga.yamiapk.R
//import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toSavedChapterEntity
//import me.manga.yamiapk.data.local.entity.ChapterNotification
//import me.manga.yamiapk.data.local.entity.SavedChapterEntity
//import me.manga.yamiapk.data.local.dao.NotificationDao
//import me.manga.yamiapk.presentation.features.library.domain.LibraryRepository
//import me.manga.yamiapk.presentation.features.download.domain.DownloadRepository
//import me.manga.yamiapk.presentation.features.download.data.DownloadRequest
//import me.manga.yamiapk.presentation.features.download.data.DownloadState
//import javax.inject.Inject
//
//@AndroidEntryPoint
//class DownloadService : Service() {
//    @Inject
//    lateinit var downloadRepository: DownloadRepository
//    @Inject
//    lateinit var libraryRepository: LibraryRepository
//    @Inject
//    lateinit var notificationDao: NotificationDao
//    @Inject
//    @ApplicationContext
//    lateinit var context: Context
//
//    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
//    private var currentDownloadJob: Job? = null
//    private var currentChapter: SavedChapterEntity? = null
//
//    companion object {
//        private const val CHANNEL_ID = "download_channel"
//        private const val NOTIFICATION_ID = 1
//        const val ACTION_CHAPTER_DOWNLOAD_COMPLETE = "me.manga.x.CHAPTER_DOWNLOAD_COMPLETE"
//        const val ACTION_CHAPTER_DOWNLOAD_ERROR    = "me.manga.x.CHAPTER_DOWNLOAD_ERROR"
//        const val EXTRA_CHAPTER_ID                = "extra_chapter_id"
//
//        // notification download
//        const val ACTION_NOTIFICATION_DOWNLOAD_COMPLETE = "me.manga.x.NOTIFICATION_DOWNLOAD_COMPLETE"
//        const val ACTION_NOTIFICATION_DOWNLOAD_ERROR    = "me.manga.x.NOTIFICATION_DOWNLOAD_ERROR"
//        const val EXTRA_NOTIFICATION_ID                 = "extra_notification_id"
//    }
//    private val pending = ArrayDeque<DownloadRequest>()
//
//    override fun onCreate() {
//        super.onCreate()
//        createNotificationChannel()
//    }
//
//    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        intent
//            ?.let { chapter ->
//                when {
//                    chapter.hasExtra(EXTRA_CHAPTER_ID) -> {
//                        val chapter = chapter.getParcelableExtra<SavedChapterEntity>(EXTRA_CHAPTER_ID)
//                        if (chapter != null) {
//
//                            pending += DownloadRequest.Chapter(chapter)}
//                    }
//
//                    chapter.hasExtra(EXTRA_NOTIFICATION_ID) -> {
//                        val notification = chapter.getParcelableExtra<ChapterNotification>(EXTRA_NOTIFICATION_ID)
//                        if (notification != null) {
//
//                            pending += DownloadRequest.Notification(notification)
//                        }
//                    }
//                }
//
//
//                val initState = DownloadState.InProgress(
//                    totalImages = 0,
//                    downloadedImages = 0,
//                    currentImageUrl = ""
//                )
//                // Use the same notification ID & channel you’ve already set up
//                startForeground(NOTIFICATION_ID, createNotification(initState))
//
//                // kick off if nothing is running
//                if (currentDownloadJob == null) {
//                    processNext()
//                }
//            }
//        return START_NOT_STICKY
//    }
//
//
//    private fun processNext() {
//        val chapter = pending.firstOrNull() ?: run {
//            serviceScope.launch {
//                // Wait a bit so the “complete” notification is visible
//                delay(1_000)
//                // Tear down the foreground notification and stop the service
//                stopForeground(true)
//                stopSelf()
//            }
//            return
//        }
//        currentDownloadJob = when (chapter) {
//            is DownloadRequest.Chapter -> downloadChapterFlow(chapter.chapter)
//            is DownloadRequest.Notification -> downloadNotificationFlow(chapter.notification)
//        }
//    }
//
//    private fun downloadChapterFlow(chapter: SavedChapterEntity) = serviceScope.launch {
//        // exactly what you had before…
//        currentChapter = chapter
//
//        downloadRepository
//            .downloadChapterFlow(chapter)
//            .onEach { updateNotification(it) }
//            .catch { e ->
//                // Flow-level exception: update notification & broadcast error
//                updateNotification(DownloadState.Error(e, downloadedImages = 0, totalImages = 0))
//                showCompletionNotification(success = false, errorMessage = e.message)
//                broadcastChapterError(chapter.id)
//            }
//            .collect { finalState ->
//
//                when (finalState) {
//
//                    is DownloadState.Complete -> {
//                        // Final successful state
//                        notificationDao.addLocalImagePathByChapterId(chapter.id,finalState.localPaths)
//                        libraryRepository.updateChapterLocalPaths(chapter.id, finalState.localPaths)
//                        showCompletionNotification(success = true)
//                        broadcastChapterComplete(chapter.id)
//                        pending.removeFirst()
//                        delay(1_000)    // let user see the final notification
//                        processNext()
//                    }
//                    is DownloadState.Error -> {
//                        // Already reported via catch, but you can do extra cleanup here
//                        broadcastChapterError(chapter.id)
//                        pending.removeFirst()
//                        delay(1_000)    // let user see the final notification
//                        processNext()
//                    }
//                    else -> {
//                        // InProgress: nothing to do here, already handled in onEach
//                    }
//                }
//            }
//
//
//    }
//
//    private fun downloadNotificationFlow(notification: ChapterNotification) = serviceScope.launch {
//
//        val original = notification.toSavedChapterEntity()
//        // 2) look up whether we already have that URL in the DB
//        val existingId = libraryRepository.getChapterIdByUrl(original.url)
//        val chapter = if (existingId != null) {
//            original.copy(id = existingId)
//        } else {
//
//            original
//        }
//
//        currentChapter = chapter
//
//
//        downloadRepository
//            .downloadChapterFlow(chapter)
//            .onEach { updateNotification(it) }
//            .catch { e ->
//                // Flow-level exception: update notification & broadcast error
//                updateNotification(DownloadState.Error(e, downloadedImages = 0, totalImages = 0))
//                showCompletionNotification(success = false, errorMessage = e.message)
//                broadcastNotificationError(notification.id)
//            }
//            .collect { finalState ->
//
//                when (finalState) {
//                    is DownloadState.Complete -> {
//                        // Final successful state
//                        notificationDao.addLocalImagePath(notification.id,finalState.localPaths)
//                        libraryRepository.updateChapterLocalPaths(chapter.id, finalState.localPaths)
//
//                        showCompletionNotification(success = true)
//                        broadcastNotificationComplete(notification.id)
//                        pending.removeFirst()
//                        delay(1_000)    // let user see the final notification
//                        processNext()
//                    }
//                    is DownloadState.Error -> {
//                        // Already reported via catch, but you can do extra cleanup here
//                        broadcastNotificationError(notification.id)
//                        pending.removeFirst()
//                        delay(1_000)    // let user see the final notification
//                        processNext()
//                    }
//                    else -> {
//                        // InProgress: nothing to do here, already handled in onEach
//                    }
//                }
//            }
//
//
//
//    }
//
//
//
//    private fun broadcastNotificationComplete(notificationId: Long) {
//        LocalBroadcastManager.getInstance(this)
//            .sendBroadcast(Intent(ACTION_NOTIFICATION_DOWNLOAD_COMPLETE).apply {
//                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
//            })
//    }
//
//
//    private fun broadcastNotificationError(notificationId: Long) {
//        LocalBroadcastManager.getInstance(this)
//            .sendBroadcast(Intent(ACTION_NOTIFICATION_DOWNLOAD_ERROR).apply {
//                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
//            })
//    }
//
//
//    private fun broadcastChapterComplete(chapterId: Long) {
//        LocalBroadcastManager.getInstance(this)
//            .sendBroadcast(Intent(ACTION_CHAPTER_DOWNLOAD_COMPLETE).apply {
//                putExtra(EXTRA_CHAPTER_ID, chapterId)
//            })
//    }
//    private fun broadcastChapterError(chapterId: Long) {
//        val intent = Intent(ACTION_CHAPTER_DOWNLOAD_ERROR).apply {
//            putExtra(EXTRA_CHAPTER_ID, chapterId)
//        }
//        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
//    }
//
//    private fun showCompletionNotification(success: Boolean, errorMessage: String? = null) {
//        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
//        val notification = if (success) {
//            createCompletionNotification(
//                getString(
//                    R.string.chapter_downloaded_successfully,
//                    currentChapter?.number
//                ))
//        } else {
//            createErrorNotification(
//                getString(
//                    R.string.failed_to_download_chapter,
//                    currentChapter?.number
//                ), errorMessage)
//        }
//        notificationManager.notify(NOTIFICATION_ID, notification)
//    }
//
//    private fun createNotification(state: DownloadState): Notification {
//
//        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
//            .setSmallIcon(R.drawable.ic_launcher_foreground)
//            .setOnlyAlertOnce(true)
//            .setPriority(NotificationCompat.PRIORITY_LOW)
//
//        when (state) {
//            is DownloadState.InProgress -> {
//                builder
//                    .setContentTitle(getString(R.string.manga_download))
//                    .setContentText(getString(R.string.downloading_chapter, currentChapter?.number))
//                    .setProgress(state.totalImages, state.downloadedImages, false)
//            }
//            is DownloadState.Complete -> {
//                builder
//                    .setContentTitle(getString(R.string.download_complete))
//                    .setContentText("Chapter ${currentChapter?.number} saved (${state.localPaths.size} images)")
//                    .setProgress(0, 0, false)
//                    .setAutoCancel(true)
//            }
//            is DownloadState.Error -> {
//                builder
//                    .setContentTitle(getString(R.string.download_error))
//                    .setContentText("Failed at ${state.downloadedImages}/${state.totalImages}: ${state.exception.localizedMessage}")
//                    .setProgress(0, 0, false)
//                    .setAutoCancel(true)
//            }
//        }
//
//        return builder.build()
//    }
//
//    private fun updateNotification(state: DownloadState) {
//
//        val notification = createNotification(state)
//        val notificationManager =
//            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
//        notificationManager.notify(NOTIFICATION_ID, notification)
//    }
//
//
//    private fun createNotificationChannel() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            val channel = NotificationChannel(
//                CHANNEL_ID,
//                "Download Channel",
//                NotificationManager.IMPORTANCE_LOW
//            )
//
//            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
//            notificationManager.createNotificationChannel(channel)
//        }
//    }
//
//
//    private fun createCompletionNotification(content: String): Notification {
//        return NotificationCompat.Builder(this, CHANNEL_ID)
//            .setContentTitle(getString(R.string.download_complete))
//            .setContentText(content)
//            .setSmallIcon(R.drawable.ic_launcher_foreground)
//            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
//            .setAutoCancel(true)
//            .build()
//    }
//
//    private fun createErrorNotification(title: String, errorMessage: String?): Notification {
//        return NotificationCompat.Builder(this, CHANNEL_ID)
//            .setContentTitle(getString(R.string.download_error))
//            .setContentText("$title\n${errorMessage ?: "Unknown error"}")
//            .setSmallIcon(R.drawable.ic_launcher_foreground)
//            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
//            .setAutoCancel(true)
//            .build()
//    }
//
//    override fun onBind(intent: Intent?): IBinder? = null
//
//    override fun onDestroy() {
//        super.onDestroy()
//
//        currentDownloadJob?.cancel()
//    }
//}