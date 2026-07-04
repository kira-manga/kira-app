package me.manga.yamiapk.presentation.features.download.ui.test2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.NotificationManager.IMPORTANCE_MIN
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.yamiapk.R
import me.manga.yamiapk.core.util.data_classes.HandelDataClasses.toChapterEntity
import me.manga.yamiapk.data.local.dao.ChapterDao
import me.manga.yamiapk.data.local.dao.ChapterDownloadDao
import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
import me.manga.yamiapk.presentation.features.download.data.DownloadState
import me.manga.yamiapk.presentation.features.download.data.DownloadingState
import me.manga.yamiapk.presentation.features.download.domain.ChapterDownloadService
import me.manga.yamiapk.presentation.features.download.domain.DownloadRepository

@HiltWorker
class DownloadWorkerV2 @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val downloadRepo: DownloadRepository,
    private val chapterDownloadDao: ChapterDownloadDao,
    private val chapterDao: ChapterDao,
    private val chapterDownloadService: ChapterDownloadService,
) : CoroutineWorker(context, params) {

    private var lastNotifiedChapterId: Long? = null
    private var completedCount = 0
    private var errorCount = 0
    private val notificationLock = Mutex()
    private val notificationManager by lazy {
        context.getSystemService(NotificationManager::class.java)!!
    }

    companion object {
        private const val TAG = "DownloadWorkerV2"

        private const val CHANNEL_ALL = "download_all_channel"
        private const val CHANNEL_CHAPTER = "download_chapter_channel"
        private const val CHANNEL_SUMMARY = "download_summary_channel"

        private const val NOTIF_ALL_ID = 1
        private const val NOTIF_SUMMARY_ID = 2
        private const val NOTIF_CHAPTER_BASE = 100

        const val ACTION_CANCEL = "me.manga.yamiapk.ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_WORK_ID = "EXTRA_WORK_ID"

        const val ACTION_CANCEL_CHAPTER = "me.manga.yamiapk.ACTION_CANCEL_CHAPTER_DOWNLOAD"
        const val EXTRA_CHAPTER_ID = "EXTRA_CHAPTER_ID"
        const val EXTRA_MANGA_ID = "EXTRA_MANGA_ID"

        @Volatile
        private var channelsCreated = false
        private val channelLock = Mutex()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return buildForegroundInfo()
    }

    override suspend fun doWork(): Result = coroutineScope {
        setupChannelsSafely()

        var currentChapter: ChapterDownloadEntity? = null

        try {
            while (true) {
                val chapter = chapterDownloadDao.getNextQueuedChapter() ?: break

                currentChapter = chapter

                chapterDownloadDao.updateStateChId(chapter.chapterId, DownloadingState.RUNNING)

                downloadRepo.downloadChapterFlowv2(chapter.toChapterEntity()).collect { state ->
                    if (isStopped) throw CancellationException()

                    when (state) {
                        is DownloadState.InProgress -> handleInProgressSafely(state, chapter)
                        is DownloadState.Compressing -> handleCompressingSafely(chapter)
                        is DownloadState.Complete -> handleCompleteSafely(chapter)
                        is DownloadState.Error -> handleErrorSafely(chapter, state.exception)
                    }

                    updateOverallNotification()
                }
            }
            return@coroutineScope Result.success()
        } catch (e: Exception) {
            currentChapter?.let {
                chapterDownloadService.deleteChapterFiles(it.mangaId, it.chapterId)
            }
            return@coroutineScope Result.failure()
        } catch (e: Exception) {
            currentChapter?.also {
                chapterDownloadService.deleteChapterFiles(it.mangaId, it.chapterId)
            }
            Result.failure()
        } finally {
            clearAllDownloadNotifications()
        }
    }

    private suspend fun handleCompressingSafely(chapter: ChapterDownloadEntity) {
        chapterDownloadDao.updateStateAndProgress(
            chapter.chapterId,
            DownloadingState.COMPRESSING,
            100
        )

        notificationLock.withLock {
            try {
                notifyChapterCompressing(
                    chapter.chapterId,
                    chapter.mangaId,
                    chapter.number
                )
                updateOverallNotification()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update compressing notification", e)
            }
        }
    }

    private fun notifyChapterCompressing(
        chapterId: Long,
        mangaId: Long,
        chapterNumber: String
    ) {
        val notifManager = context.getSystemService(NotificationManager::class.java)!!
        val compressingNotification = NotificationCompat.Builder(context, CHANNEL_CHAPTER)
            .setContentTitle(context.getString(R.string.notification_chapter_title, chapterNumber))
            .setContentText(context.getString(R.string.notification_compressing_images))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setProgress(0, 0, true)
            .build()

        notifManager.notify(NOTIF_CHAPTER_BASE + chapterId.toInt(), compressingNotification)
        lastNotifiedChapterId = chapterId
    }

    private suspend fun handleInProgressSafely(
        state: DownloadState.InProgress,
        chapter: ChapterDownloadEntity,
    ) {
        val downloaded = state.downloadedImages
        val total = state.totalImages

        if (total > 0) {
            val percent = ((downloaded.toFloat() / total.toFloat()) * 100).toInt()
            chapterDownloadDao.updateProgress(chapter.chapterId, percent)
        }

        notificationLock.withLock {
            try {
                notifyChapterProgress(chapter.chapterId, chapter.mangaId, chapter.number, downloaded, total)
                updateOverallNotification()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update progress notifications", e)
            }
        }
    }

    private suspend fun handleCompleteSafely(chapter: ChapterDownloadEntity) {
        chapterDownloadDao.updateStateAndProgress(chapter.chapterId, DownloadingState.SUCCESS, 100)
        chapterDao.markChapterDownloaded(chapter.chapterId)
        completedCount++

        notificationLock.withLock {
            try {
                notificationManager.cancel(NOTIF_CHAPTER_BASE + chapter.chapterId.toInt())
                updateOverallNotification()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel chapter notification", e)
            }
        }
    }

    private suspend fun handleErrorSafely(chapter: ChapterDownloadEntity, exception: Throwable) {
        errorCount++
        Log.i("asdasdasdasdsdasdsa","erroasdsadadasreff")
        chapterDownloadDao.updateStateAndProgress(
            chapter.chapterId,
            DownloadingState.FAILED,
            0,
            exception.message
        )
        chapterDownloadService.deleteChapterFiles(chapter.mangaId, chapter.chapterId)

        notificationLock.withLock {
            try {
                notificationManager.cancel(NOTIF_CHAPTER_BASE + chapter.chapterId.toInt())
                updateOverallNotification()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel error notification", e)
            }
        }
    }

    private fun updateOverallNotification() {
        try {
            setForegroundAsync(buildForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update foreground notification", e)
        }
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        val cancelAllIntent = Intent(context, DownloadCancelReceiver::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_WORK_ID, params.id.toString())
        }

        val cancelAllPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            cancelAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_downloading_chapters))
            .setContentText(context.getString(R.string.notification_downloading_background))
            .addAction(
                R.drawable.ic_cancel,
                context.getString(R.string.action_cancel_all),
                cancelAllPendingIntent
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ALL_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ALL_ID, notification)
        }
    }

    private fun notifyChapterProgress(
        chapterId: Long,
        mangaId: Long,
        chapterNumber: String,
        downloaded: Int,
        total: Int
    ) {
        val cancelChapterIntent = Intent(context, DownloadCancelReceiver::class.java).apply {
            action = ACTION_CANCEL_CHAPTER
            putExtra(EXTRA_WORK_ID, params.id.toString())
            putExtra(EXTRA_CHAPTER_ID, chapterId)
            putExtra(EXTRA_MANGA_ID, mangaId)
        }

        val cancelChapterPendingIntent = PendingIntent.getBroadcast(
            context,
            (chapterId and 0xFFFF).toInt(),
            cancelChapterIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notifManager = context.getSystemService(NotificationManager::class.java)!!
        val progressNotification = NotificationCompat.Builder(context, CHANNEL_CHAPTER)
            .setContentTitle(context.getString(R.string.notification_chapter_title, chapterNumber))
            .setContentText(context.getString(R.string.notification_images_progress, downloaded, total))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(
                R.drawable.ic_cancel,
                context.getString(R.string.action_cancel_chapter),
                cancelChapterPendingIntent
            )
            .setProgress(total, downloaded, false)
            .build()

        notifManager.notify(NOTIF_CHAPTER_BASE + chapterId.toInt(), progressNotification)
        lastNotifiedChapterId = chapterId
    }

    private fun clearAllDownloadNotifications() {
        context.getSystemService(NotificationManager::class.java)?.apply {
            cancel(NOTIF_ALL_ID)
            lastNotifiedChapterId?.let { cancel(NOTIF_CHAPTER_BASE + it.toInt()) }
        }
    }

    private suspend fun setupChannelsSafely() {
        channelLock.withLock {
            if (!channelsCreated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    notificationManager.createChannel(
                        CHANNEL_ALL,
                        context.getString(R.string.notification_channel_all_downloads),
                        NotificationManager.IMPORTANCE_DEFAULT,
                        context.getString(R.string.notification_channel_all_downloads_desc)
                    )
                    notificationManager.createChannel(
                        CHANNEL_CHAPTER,
                        context.getString(R.string.notification_channel_chapter_download),
                        NotificationManager.IMPORTANCE_LOW,
                        context.getString(R.string.notification_channel_chapter_download_desc)
                    )
                    notificationManager.createChannel(
                        CHANNEL_SUMMARY,
                        context.getString(R.string.notification_channel_download_summary),
                        NotificationManager.IMPORTANCE_MIN,
                        context.getString(R.string.notification_channel_download_summary_desc)
                    )
                    channelsCreated = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create notification channels", e)
                }
            }
        }
    }

    private fun NotificationManager.createChannel(
        id: String,
        name: String,
        importance: Int,
        description: String
    ) {
        createNotificationChannel(
            NotificationChannel(id, name, importance).apply { this.description = description }
        )
    }
}