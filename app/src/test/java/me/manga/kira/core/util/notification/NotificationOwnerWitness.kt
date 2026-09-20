package me.manga.kira.core.util.notification

import android.app.Notification
import android.graphics.Bitmap
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.library.LibraryChapterNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Real Room cross-parent discovery and delayed-display witness; no URL-keyed DAO double. */
internal class NotificationOwnerWitness(
    private val room: NotificationRoomFixture,
) {
    private val realChapters = room.db.chapterDao()
    private val coverUrls = mutableListOf<String>()
    private val cover = NotificationNativeCoverWitness()
    private val observedCovers =
        object : NotificationCovers {
            override suspend fun withCover(
                url: String,
                canPost: () -> Boolean,
                post: (Bitmap?) -> Unit,
            ) {
                coverUrls.add(url)
                cover.loader.withCover(url, canPost, post)
            }
        }

    suspend fun assertDelayedDisplay(
        posting: NotificationPostingShadow,
        assertContent: (Notification, LibraryChapterNotification) -> Unit,
    ) {
        // B is the first row for the shared URL; it must never supply A's ID or display metadata.
        val b = seed("b")
        val a = seed("a")
        assertTrue(b.chapterId > 0L && a.chapterId > b.chapterId)
        val helper = room.helper(observedCovers)
        val capturedA = listOf(a.notification)
        assertStoredOwner(a, capturedA.single())
        assertTrue(posting.posted.isEmpty())
        cover.assertIdle()

        val capturedB = listOf(b.notification)
        assertStoredOwner(b, capturedB.single())
        assertEquals(0, helper.persistFixtureNotifications(a.manga, listOf(a.chapter)).addedChapters)
        assertEquals(0, helper.persistFixtureNotifications(b.manga, listOf(b.chapter)).addedChapters)
        val writesBeforeDisplay = room.sql.notificationInserts.get()
        val stored = listOf(a.stored, b.stored).sortedBy { it.id }
        assertEquals(stored, room.updates())

        helper.displayNotifications(capturedA)

        assertEquals(writesBeforeDisplay, room.sql.notificationInserts.get())
        assertDisplayedOwner(capturedA.single(), posting, assertContent)
        assertEquals(stored, room.updates())
    }

    private fun assertDisplayedOwner(
        row: LibraryChapterNotification,
        posting: NotificationPostingShadow,
        assertContent: (Notification, LibraryChapterNotification) -> Unit,
    ) {
        assertEquals(listOf(row.manga.coverUrl), coverUrls)
        assertEquals(listOf(row.notificationId.toInt()), posting.posted.map { it.first })
        assertContent(posting.posted.single().second, row)
        cover.assertSingleDecode()
    }

    private suspend fun seed(label: String): Owner {
        val manga = room.manga("owner-$label", "https://cover.example/$label.png", title = "Same display title")
        val chapter = room.chapters(manga, 1).single().copy(url = SHARED_URL)
        val row = room.helper(observedCovers).persistFixtureNotifications(manga, listOf(chapter)).notifications.single()
        val saved = checkNotNull(realChapters.getChapterByIdSuspend(row.chapterId))
        return Owner(manga, saved, row, room.updates().single { it.id == row.notificationId })
    }

    private suspend fun assertStoredOwner(
        owner: Owner,
        row: LibraryChapterNotification,
    ) {
        assertTrue(row.notificationId > 0L)
        assertEquals(owner.manga.url, row.manga.url)
        assertEquals(owner.manga.title, row.manga.title)
        assertEquals(owner.manga.imageUrl, row.manga.coverUrl)
        assertEquals(owner.manga.api, row.manga.api)
        assertEquals(owner.manga.language, row.manga.language)
        assertEquals(owner.chapterId, row.chapterId)
        assertEquals(owner.chapter.url, row.chapter.url)
        assertEquals(owner.chapter.number, row.chapter.number)
        assertEquals(owner.chapter.copy(id = row.chapterId), realChapters.getChapterByIdSuspend(row.chapterId))
        val stored = room.updates().single { it.mangaId == owner.manga.id }
        assertEquals(owner.stored, stored)
        assertNotificationPayload(owner.manga.id, row, stored)
    }

    private data class Owner(
        val manga: SavedMangaEntity,
        val chapter: SavedChapterEntity,
        val notification: LibraryChapterNotification,
        val stored: ChapterNotification,
    ) {
        val chapterId: Long get() = notification.chapterId
    }

    private companion object {
        const val SHARED_URL = "https://chapter.example/shared-chapter"
    }
}
