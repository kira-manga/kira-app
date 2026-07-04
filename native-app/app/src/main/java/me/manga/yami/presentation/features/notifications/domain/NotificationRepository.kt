package me.manga.yamiapk.presentation.features.notifications.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.manga.yamiapk.R
import me.manga.yamiapk.data.local.dao.LibraryDeo
import me.manga.yamiapk.data.local.entity.ChapterNotification
import me.manga.yamiapk.data.local.dao.NotificationDao
import java.time.LocalDate
import javax.inject.Inject

class NotificationRepository @Inject constructor(
    private val dao: NotificationDao,
    private val libraryDeo: LibraryDeo,


    ) {
    /**
     * Emits a sorted & grouped list whenever the DB changes.
     * Let exceptions bubble up to be caught by the collector.
     */
    fun getGroupedNotifications(): Flow<List<Pair<Int, List<ChapterNotification>>>> =
        dao.getAllNotifications()
            .map(::groupByDate)  // transform raw list → buckets

    suspend fun markAsRead(id: Long) =
        libraryDeo.markChapterAndNotificationRead(id)

    suspend fun markAllAsRead() =
        dao.markAllAsRead()

    suspend fun deleteAll() =
        dao.deleteAllNotifications()

    suspend fun delete(notification: ChapterNotification) =
        dao.deleteNotification(notification)

    /**
     * Restores a deleted notification back to the database.
     * Used for undo functionality.
     */
    suspend fun restore(notification: ChapterNotification) =
        dao.insertNotification(notification)

    private fun groupByDate(
        notifications: List<ChapterNotification>
    ): List<Pair<Int, List<ChapterNotification>>> {
        val today     = LocalDate.now()
        val yesterday = today.minusDays(1)
        val lastWeek  = today.minusDays(7)

        fun List<ChapterNotification>.sortDesc() =
            sortedByDescending { it.chapterNumber.toDoubleOrNull() ?: 0.0 }

        val todayList     = notifications.filter  { it.notificationDate == today }.sortDesc()
        val yesterdayList = notifications.filter  { it.notificationDate == yesterday }.sortDesc()
        val lastWeekList  = notifications
            .filter  { it.notificationDate.isAfter(lastWeek) && it.notificationDate.isBefore(yesterday) }
            .sortDesc()
        val olderList     = notifications.filter  { it.notificationDate.isBefore(lastWeek) }.sortDesc()

        return buildList {
            if (todayList.isNotEmpty())
                add(R.string.notifications_group_today     to todayList)
            if (yesterdayList.isNotEmpty())
                add(R.string.notifications_group_yesterday to yesterdayList)
            if (lastWeekList.isNotEmpty())
                add(R.string.notifications_group_last_week to lastWeekList)
            if (olderList.isNotEmpty())
                add(R.string.notifications_group_older     to olderList)
        }
    }
}