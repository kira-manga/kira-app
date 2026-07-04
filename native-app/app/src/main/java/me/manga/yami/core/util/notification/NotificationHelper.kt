package me.manga.yamiapk.core.util.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

import me.manga.yamiapk.R
import me.manga.yamiapk.data.local.entity.SavedChapterEntity

object NotificationHelper {
    private const val CHANNEL_ID = "manga_downloads"
    private const val CHANNEL_NAME = "Manga Downloads"
    private const val NOTIF_ID_BASE = 1000
    private const val CHANNEL_ID_MESSAGES = "firebase_messages"
    private const val CHANNEL_NAME_MESSAGES = "App Messages"
    // Hold the application context
    private lateinit var appContext: Context

    /**
     * Must be called once in Application.onCreate()
     */
    fun init(context: Context) {
        appContext = context.applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }



            val messagesChannel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                CHANNEL_NAME_MESSAGES,
                NotificationManager.IMPORTANCE_HIGH
            )



            val nm = appContext.getSystemService<NotificationManager>()
            nm?.createNotificationChannel(appChannel)
            nm?.createNotificationChannel(messagesChannel)
        }


    }

    /**
     * Build a base notification for the chapter
     */
    private fun builder(chapter: SavedChapterEntity): NotificationCompat.Builder {
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Downloading “${chapter.number}”")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
    }


    fun buildProgressNotification(
        context: Context,
        title: String,
        progress: Int,
        max: Int
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(max, progress, false)
            .build()
    }
    /**
     * Show initial progress notification
     */
    fun show(chapter: SavedChapterEntity, initialProgress: Int) {
        val notif = builder(chapter)
            .setProgress(100, initialProgress, false)
        appContext.getSystemService<NotificationManager>()
            ?.notify(NOTIF_ID_BASE + chapter.id.toInt(), notif.build())
    }

    /**
     * Update progress
     */
    fun update(chapter: SavedChapterEntity,totalImages: Int, percent: Int) {
        val notif = builder(chapter)
            .setProgress(totalImages, percent, false)
        appContext.getSystemService<NotificationManager>()
            ?.notify(NOTIF_ID_BASE + chapter.id.toInt(), notif.build())
    }

    /**
     * Mark as complete
     */
    fun complete(chapter: SavedChapterEntity) {
        val notif = builder(chapter)
            .setContentText("Download complete")
            .setProgress(0, 0, false)
            .setOngoing(false)
        appContext.getSystemService<NotificationManager>()
            ?.notify(NOTIF_ID_BASE + chapter.id.toInt(), notif.build())
    }

    /**
     * Report an error
     */
    fun error(chapter: SavedChapterEntity, message: String) {
        val notif = builder(chapter)
            .setContentText("Failed: $message")
            .setProgress(0, 0, false)
            .setOngoing(false)
        appContext.getSystemService<NotificationManager>()
            ?.notify(NOTIF_ID_BASE + chapter.id.toInt(), notif.build())
    }


    fun showNotification(context: Context, title: String, message: String, notifId: Int = NOTIF_ID_BASE) {
        NotificationCompat.Builder(context, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()
            .also { notif ->
                context.getSystemService<NotificationManager>()
                    ?.notify(notifId, notif)
            }




    }
}
