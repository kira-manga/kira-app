//package me.manga.yamiapk.work
//
//import android.annotation.SuppressLint
//import android.app.NotificationChannel
//import android.app.NotificationManager
//import android.app.NotificationManager.IMPORTANCE_DEFAULT
//import android.app.NotificationManager.IMPORTANCE_LOW
//import android.app.NotificationManager.IMPORTANCE_MIN
//import android.app.PendingIntent
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.content.pm.ServiceInfo
//import android.os.Build
//import android.util.Log
//import androidx.core.app.NotificationCompat
//import androidx.hilt.work.HiltWorker
//import androidx.localbroadcastmanager.content.LocalBroadcastManager
//import androidx.work.CoroutineWorker
//import androidx.work.ForegroundInfo
//import androidx.work.WorkManager
//import androidx.work.WorkerParameters
//import androidx.work.workDataOf
//import dagger.assisted.Assisted
//import dagger.assisted.AssistedInject
//import kotlinx.coroutines.coroutineScope
//import me.manga.yamiapk.R
//import me.manga.yamiapk.data.local.dao.ChapterDao
//import me.manga.yamiapk.presentation.features.download.data.DownloadState
//import me.manga.yamiapk.presentation.features.download.domain.DownloadRepository
//import java.util.UUID
//import kotlin.coroutines.cancellation.CancellationException
//
//@HiltWorker
//class MangaDownloadWorker @AssistedInject constructor(
//    @Assisted private val context: Context,
//    @Assisted private val params: WorkerParameters,
//    private val downloadRepo: DownloadRepository,
//    private val dao: ChapterDao
//) : CoroutineWorker(context, params) {
//    private var lastNotifiedChapterId: Long? = null
//
//    companion object {
//        private const val KEY_URLS            = "KEY_URLS"
//         const val KEY_CHAPTER_ID      = "KEY_CHAPTER_ID"
//         const val KEY_PROGRESS        = "KEY_PROGRESS"
//         const val KEY_COMPLETED_COUNT = "KEY_COMPLETED_COUNT"
//        const val KEY_ERROR_COUNT = "KEY_ERROR_COUNT"
//        const val KEY_TOTAL_COUNT = "KEY_COMPLETED_COUNT"
//
//        private const val TAG = "MangaDownloadWorker"
//
//
//        private const val CHANNEL_ALL = "download_all_channel"
//        private const val CHANNEL_CHAPTER = "download_chapter_channel"
//        private const val CHANNEL_SUMMARY = "download_summary_channel"
//
//        private const val NOTIF_ALL_ID = 1
//        private const val NOTIF_SUMMARY_ID = 2
//        private const val NOTIF_CHAPTER_BASE = 100
//        const val ACTION_CHAPTER_COMPLETE = "me.manga.yamiapk.work.CHAPTER_COMPLETE"
//        const val ACTION_CHAPTER_ERROR = "me.manga.yamiapk.work.CHAPTER_ERROR"
//        const val EXTRA_CHAPTER_ID = "EXTRA_CHAPTER_ID"
//
//        const val ACTION_CANCEL = "me.manga.yamiapk.ACTION_CANCEL_DOWNLOAD"
//        const val EXTRA_WORK_ID = "EXTRA_WORK_ID"
//
//        /** Builds WorkManager progress data */
//        private fun makeProgressData(
//            currentChapterId: Long,
//            percent: Int,
//            completed: Int,
//            errorCount :Int,
//            total: Int,
//        ) = workDataOf(
//            KEY_CHAPTER_ID to currentChapterId,
//            KEY_PROGRESS to percent,
//            KEY_COMPLETED_COUNT to completed,
//            KEY_ERROR_COUNT to errorCount,
//            KEY_TOTAL_COUNT to total,
//        )
//
//
//
//        /** Sets up all required notification channels */
//        fun setupChannels(context: Context) {
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                val mgr = context.getSystemService(NotificationManager::class.java)
//                mgr?.apply {
//                    createChannel(
//                        CHANNEL_ALL,
//                        "All Downloads",
//                        IMPORTANCE_DEFAULT,
//                        "Overall download progress"
//                    )
//                    createChannel(
//                        CHANNEL_CHAPTER,
//                        "Chapter Download",
//                        IMPORTANCE_LOW,
//                        "Single chapter progress"
//                    )
//                    createChannel(
//                        CHANNEL_SUMMARY,
//                        "Download Summary",
//                        IMPORTANCE_MIN,
//                        "Completion notifications"
//                    )
//                }
//            }
//        }
//
//        private fun NotificationManager.createChannel(
//            id: String,
//            name: String,
//            importance: Int,
//            description: String
//        ) {
//            createNotificationChannel(
//                NotificationChannel(id, name, importance).apply { this.description = description }
//            )
//        }
//
//
//
//
//
//    }
//
//    private var completedCount: Int = 0
//    private var errorCount: Int = 0
//
//    override suspend fun getForegroundInfo(): ForegroundInfo {
//        val cancelPi = buildCancelPendingIntent()
//        return buildForegroundInfo(0, completedCount, cancelPi)
//    }
//
//
//    @SuppressLint("SuspiciousIndentation")
//    override suspend fun doWork(): Result = coroutineScope {
//        setupChannels(context)
//        val ids = inputData.getLongArray(KEY_URLS) ?: return@coroutineScope Result.failure()
//        return@coroutineScope try {
//        val cancelPi = buildCancelPendingIntent()
//        completedCount = 0
//
//
//            ids.forEachIndexed { idx, id ->
//                val chapter = dao.getChapterByIdSuspend(id) ?: return@forEachIndexed
//                if (chapter.isDownloaded) {
//
//                    completedCount++
//
//                    sendBroadcastComplete(id)
//
//                } else {
//                    downloadRepo.downloadChapterFlow(chapter).collect { state ->
//                        if (isStopped) throw CancellationException()
//
//                        when (state) {
//
//                            is DownloadState.InProgress -> {
//                                notifyChapterProgress(
//                                    chapter.id,
//                                    chapter.number,
//                                    state.downloadedImages,
//                                    state.totalImages
//                                )
//                                val percent =
//                                    ((idx + (state.downloadedImages / state.totalImages.toFloat())) / ids.size * 100).toInt()
//                                setForegroundAsync(
//                                    buildForegroundInfo(
//                                        percent,
//                                        completedCount,
//                                        cancelPi
//                                    )
//                                )
//                                setProgressAsync(makeProgressData(chapter.id, percent, completedCount,errorCount,ids.size))
//                            }
//                            is DownloadState.Complete -> {
//                                completedCount++
//                                context.getSystemService(NotificationManager::class.java)
//                                    ?.cancel(NOTIF_CHAPTER_BASE + chapter.id.toInt())
//                                sendSummaryNotification(completedCount, ids.size)
//                                sendBroadcastComplete(id)
//
//                            }
//                            is DownloadState.Error -> {
//                                errorCount++
//                                // Clean up if needed
//
//                                context.getSystemService(NotificationManager::class.java)
//                                    ?.cancel(NOTIF_CHAPTER_BASE + chapter.id.toInt())
//                                sendBroadcastError(id)
//
//
//                            }
//                        }
//                    }
//                }
//            }
//
//            setForegroundAsync(buildForegroundInfo(100, completedCount, cancelPi))
//
//            Result.success()
//        } catch (e: Exception) {
//            Result.failure()
//        }finally {
//            // no matter what—success, failure, or cancellation—we clear our notifications
//            clearAllDownloadNotifications()
//        }
//    }
//
//
//    private fun clearAllDownloadNotifications() {
//        val notifMgr = context.getSystemService(NotificationManager::class.java)!!
//        notifMgr.cancel(NOTIF_ALL_ID)
//        lastNotifiedChapterId?.let { id ->
//            notifMgr.cancel(NOTIF_CHAPTER_BASE + id.toInt())
//        }
//    }
//
//
//
//    private fun buildForegroundInfo(
//        progress: Int,
//        completed: Int,
//        cancelPi: PendingIntent
//    ): ForegroundInfo {
//        val notif = NotificationCompat.Builder(context, CHANNEL_ALL)
//            .setContentTitle("Downloading Chapters")
//            .setContentText("$progress% — $completed chapters done")
//            .setSmallIcon(R.drawable.ic_launcher_foreground)
//            .setProgress(100, progress, false)
//            .addAction(R.drawable.cache_cleaner, "Cancel", cancelPi)
//            .setOngoing(true)
//            .setOnlyAlertOnce(true)
//            .build()
//        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//            ForegroundInfo(NOTIF_ALL_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
//        } else {
//            ForegroundInfo(NOTIF_ALL_ID, notif)
//        }
//    }
//
//
//    private fun buildCancelPendingIntent(): PendingIntent {
//        val intent = Intent(context, DownloadCancelReceiver::class.java).apply {
//            action = ACTION_CANCEL
//            putExtra(EXTRA_WORK_ID, id.toString())
//        }
//        return PendingIntent.getBroadcast(
//            context,
//            0,
//            intent,
//            // add FLAG_IMMUTABLE alongside your existing flag
//            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
//        )
//    }
//
//    /** Notifies chapter-level progress */
//    private fun notifyChapterProgress(
//        chapterId: Long,
//        chapterNumber: String,
//        downloaded: Int,
//        total: Int
//    ) {
//        val notifMgr = context.getSystemService(NotificationManager::class.java)!!
//
//
//        // 2) post this chapter’s progress
//        val chapterNotif = NotificationCompat.Builder(context, CHANNEL_CHAPTER)
//            .setContentTitle("Chapter $chapterNumber")
//            .setContentText("$downloaded / $total images")
//            .setSmallIcon(R.drawable.ic_launcher_foreground)
//            .setProgress(total, downloaded, false)
//            .build()
//
//        notifMgr.notify(NOTIF_CHAPTER_BASE + chapterId.toInt(), chapterNotif)
//
//        // 3) update our “last” pointer
//        lastNotifiedChapterId = chapterId
//    }
//
//    /** Sends a summary notification when a chapter completes */
//    private fun sendSummaryNotification(completed: Int, total: Int) {
//        val summaryNotif = NotificationCompat.Builder(context, CHANNEL_SUMMARY)
//            .setContentTitle("Downloaded $completed of $total chapters")
//            .setSmallIcon(R.drawable.ic_launcher_foreground)
//            .setAutoCancel(true)
//            .build()
//        context.getSystemService(NotificationManager::class.java)
//            ?.notify(NOTIF_SUMMARY_ID, summaryNotif)
//    }
//
//    private fun sendBroadcastComplete(chapterId: Long) {
//        Intent(ACTION_CHAPTER_COMPLETE).also { intent ->
//            intent.putExtra(EXTRA_CHAPTER_ID, chapterId)
//            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
//        }
//    }
//
//    private fun sendBroadcastError(chapterId: Long) {
//        Intent(ACTION_CHAPTER_ERROR).also { intent ->
//            intent.putExtra(EXTRA_CHAPTER_ID, chapterId)
//            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
//        }
//    }
//
//}
//
///** Receiver to cancel in-progress work */
//class DownloadCancelReceiver : BroadcastReceiver() {
//    override fun onReceive(context: Context, intent: Intent) {
//        if (intent.action == MangaDownloadWorker.ACTION_CANCEL) {
//            intent.getStringExtra(MangaDownloadWorker.EXTRA_WORK_ID)
//                ?.let(UUID::fromString)
//                ?.let { WorkManager.getInstance(context).cancelWorkById(it) }
//        }
//    }
//}