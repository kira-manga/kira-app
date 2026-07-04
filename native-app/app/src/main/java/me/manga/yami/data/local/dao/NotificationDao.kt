package me.manga.yamiapk.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.data.local.entity.ChapterNotification

@Dao
interface NotificationDao {

    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun getNotificationById(id: Long): ChapterNotification

    @Query("UPDATE notifications SET mangaImageUrl = :newImageUrl WHERE mangaId = :mangaId")
    suspend fun updateMangaImageUrl(mangaId: Long, newImageUrl: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: ChapterNotification): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotifications(notifications: List<ChapterNotification>)


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotificationsList(notifications: List<ChapterNotification>): List<Long>
    @Update
    suspend fun updateNotification(notification: ChapterNotification)

    @Query("SELECT * FROM notifications ORDER BY notificationDate DESC")
    fun getAllNotifications(): Flow<List<ChapterNotification>>

    @Query("SELECT * FROM notifications WHERE isRead = 0 ORDER BY notificationDate DESC")
    fun getUnreadNotifications(): Flow<List<ChapterNotification>>

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :notificationId")
    suspend fun markAsRead(notificationId: Long)

    @Query("UPDATE notifications SET isRead = 1")
    suspend fun markAllAsRead()

    @Delete
    suspend fun deleteNotification(notification: ChapterNotification)

    @Query("DELETE FROM notifications")
    suspend fun deleteAllNotifications()

    /** Returns count of unread notifications **/
    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0")
    suspend fun countAll(): Int

    /** Returns the latest [limit] notifications, newest first **/
    @Query("SELECT * FROM notifications ORDER BY notificationDate DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<ChapterNotification>





    @Query("UPDATE notifications SET isDownloaded = 1 WHERE id = :notificationId")
    suspend fun markAsDownloaded(notificationId: Long)


     /*
     * Note: Room needs a TypeConverter to persist List<String>.
     */
    @Query("UPDATE notifications SET localImagePaths = :paths WHERE id = :notificationId")
    suspend fun updateLocalImagePaths(
        notificationId: Long,
        paths: List<String>
    )

    @Transaction
    suspend fun addLocalImagePath(notificationId: Long, newPath: List<String>) {
        val notif = getNotificationById(notificationId)
        // build a new list with the extra path
        val updated = notif.copy(
            localImagePaths =  newPath,
            isDownloaded = true
        )
        updateNotification(updated)
    }

    @Query("SELECT * FROM notifications WHERE chapterUrl = :chapterUrl LIMIT 1")
    suspend fun findOneByChapterUrl(chapterUrl: String): ChapterNotification?
    @Transaction
    suspend fun addLocalImagePathForChapter(
        chapterUrl: String,
        newPaths: List<String>
    ) {
        // fetch one (or exit early)
        val notif = findOneByChapterUrl(chapterUrl) ?: return

        // build updated copy
        val updated = notif.copy(
            localImagePaths = newPaths,
            isDownloaded    = true
        )

        // save & return
        updateNotification(updated)

    }


    @Query("SELECT * FROM notifications WHERE chapterId = :chapterId LIMIT 1")
    suspend fun getNotificationByChapterId(chapterId: Long): ChapterNotification?


    @Transaction
    suspend fun addLocalImagePathByChapterId(
        chapterId: Long,
        newPaths: List<String>
    ) {
        // fetch one (or exit early)
        val notif = getNotificationByChapterId(chapterId) ?: return

        // build updated copy
        val updated = notif.copy(
            localImagePaths = newPaths,
            isDownloaded    = true
        )

        // save & return
        updateNotification(updated)

    }

    // update url
    @Query("SELECT * FROM notifications WHERE api = :apiName")
    suspend fun getNotificationsByApi(apiName: String): List<ChapterNotification>

    @Update
    suspend fun update(notification: ChapterNotification)

    @Transaction
    suspend fun markChapterAndNotificationRead(chapterId: Long) {
        markChapterAsReadInternal(chapterId)
        markNotificationReadInternal(chapterId)
    }

    @Query("UPDATE saved_chapters SET isRead = NOT isRead WHERE id = :chapterId")
    suspend fun markChapterAsReadInternal(chapterId: Long)

    @Query("UPDATE notifications SET isRead = NOT isRead WHERE chapterId = :chapterId")
    suspend fun markNotificationReadInternal(chapterId: Long)
} 