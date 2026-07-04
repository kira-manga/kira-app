package me.manga.yamiapk.core.util.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import me.manga.yamiapk.R
import me.manga.yamiapk.data.local.dao.NotificationDao
import me.manga.yamiapk.data.local.entity.ChapterNotification
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.presentation.features.library.domain.LibraryRepository
import java.net.URL

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChapterNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationDao: NotificationDao,
    private val libraryRepository: LibraryRepository,

    ) {
    private val notificationManager = context.getSystemService<NotificationManager>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val CHANNEL_ID = "me.manga.yamiapk.new_chapters"
        private const val GROUP_KEY = "me.manga.yamiapk.CHAPTER_UPDATES"
        private const val SUMMARY_ID = 0
        private const val TAG = "ChapterNotifHelper"

    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.new_chapters),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notifications_for_new_manga_chapters)
                enableLights(true)
                enableVibration(true)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    fun addNewChapterNotification(manga: SavedMangaEntity, chapters: List<SavedChapterEntity>) {
        // Defensive: don't start if there's nothing to do
        if (chapters.isEmpty()) {
            Log.w(TAG, "addNewChapterNotification called with empty chapters for mangaId=${manga.id}")
            return
        }

        coroutineScope.launch {
            try {
                // Insert chapters; rawIds should correspond to chapters by index but be defensive.
                val rawIds = libraryRepository.insertChapterList(chapters)

                // Build realIds safely: if rawIds is shorter than chapters, try to resolve via DB lookup.
                val realIds = chapters.mapIndexed { idx, chapter ->
                    val raw = rawIds.getOrNull(idx)
                    if (raw == null) {
                        Log.w(TAG, "insertChapterList returned fewer ids than chapters (idx=$idx). Falling back to lookup for url=${chapter.url}")
                    }
                    if (raw == null || raw == -1L) {
                        // either missing or indicates existing -> lookup by url
                        libraryRepository.getChapterIdByUrl(chapter.url)
                            ?: run {
                                Log.e(TAG, "chapter id not found for url=${chapter.url}; skipping this chapter")
                                -1L
                            }
                    } else raw
                }

                // Build notifications, but only for entries with a valid chapter id (not -1)
                val notifications = chapters.mapIndexedNotNull { idx, chapter ->
                    val chapterId = realIds.getOrNull(idx) ?: -1L
                    if (chapterId <= 0L) {
                        // skip entries where we couldn't obtain a valid id
                        null
                    } else {
                        ChapterNotification(
                            mangaId = manga.id,
                            mangaTitle = manga.title,
                            mangaImageUrl = manga.imageUrl,
                            chapterId = chapterId,
                            chapterNumber = chapter.number,
                            chapterUrl = chapter.url,
                            mangaUrl = manga.url,
                            api = manga.api,
                            language = manga.language
                        )
                    }
                }

                if (notifications.isEmpty()) {
                    Log.w(TAG, "No notifications to insert for mangaId=${manga.id}")
                    return@launch
                }

                // Insert notifications and get their DB row ids
                val notifRowIds = notificationDao.insertNotificationsList(notifications)

                // Pair notifications with returned row IDs safely.
                // Use zip so we only keep pairs that actually have an id.
                val pairs = notifications.zip(notifRowIds)
                if (pairs.isEmpty()) {
                    Log.w(TAG, "notificationDao.insertNotificationsList returned no IDs for mangaId=${manga.id}")
                    return@launch
                }

                // Show up to the last 6 notifications (most recent). Use takeLast to get last entries.
                pairs.takeLast(6).asReversed().forEach { (notif, rowId) ->
                    // showChapterNotification expects a ChapterNotification with id=rowId
                    showChapterNotification(notif.copy(id = rowId))
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to add chapter notification for mangaId=${manga.id}: ${t.message}", t)
            }
        }
    }



    private fun showChapterNotification(notification: ChapterNotification) {

        coroutineScope.launch {
            // Async load cover image
            val bitmap = async(Dispatchers.IO) {
                runCatching {
                    val url = URL(notification.mangaImageUrl)
                    BitmapFactory.decodeStream(url.openConnection().getInputStream())
                }.getOrNull()
            }.await()

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(notification.mangaTitle)
                .setContentText(
                    context.getString(
                        R.string.chapter_is_available,
                        notification.chapterNumber
                    ))
                .setAutoCancel(true)

            bitmap?.let { builder.setLargeIcon(it) }

            notificationManager?.notify(notification.id.toInt(), builder.build())
        }
    }
}