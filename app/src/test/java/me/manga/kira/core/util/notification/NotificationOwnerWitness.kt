package me.manga.kira.core.util.notification

import android.app.Notification
import android.graphics.Bitmap
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
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
        assertContent: (Notification, ChapterNotification) -> Unit,
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
        assertTrue(helper.persistNewChapterNotifications(a.manga, listOf(a.chapter)).isEmpty())
        assertTrue(helper.persistNewChapterNotifications(b.manga, listOf(b.chapter)).isEmpty())
        val writesBeforeDisplay = room.sql.notificationInserts.get()
        val stored = (capturedA + capturedB).sortedBy { it.id }
        assertEquals(stored, room.updates())

        helper.displayNotifications(capturedA)

        assertEquals(writesBeforeDisplay, room.sql.notificationInserts.get())
        assertDisplayedOwner(capturedA.single(), posting, assertContent)
        assertEquals(stored, room.updates())
    }

    private fun assertDisplayedOwner(
        row: ChapterNotification,
        posting: NotificationPostingShadow,
        assertContent: (Notification, ChapterNotification) -> Unit,
    ) {
        assertEquals(listOf(row.mangaImageUrl), coverUrls)
        assertEquals(listOf(row.id.toInt()), posting.posted.map { it.first })
        assertContent(posting.posted.single().second, row)
        cover.assertSingleDecode()
    }

    private suspend fun seed(label: String): Owner {
        val manga = room.manga("owner-$label", "https://cover.example/$label.png", title = "Same display title")
        val chapter = room.chapters(manga, 1).single().copy(url = SHARED_URL)
        val row = room.helper(observedCovers).persistNewChapterNotifications(manga, listOf(chapter)).single()
        val saved = checkNotNull(realChapters.getChapterByIdSuspend(row.chapterId))
        return Owner(manga, saved, row)
    }

    private suspend fun assertStoredOwner(
        owner: Owner,
        row: ChapterNotification,
    ) {
        assertTrue(row.id > 0L)
        assertEquals(owner.manga.id, row.mangaId)
        assertEquals(owner.manga.url, row.mangaUrl)
        assertEquals(owner.manga.title, row.mangaTitle)
        assertEquals(owner.manga.imageUrl, row.mangaImageUrl)
        assertEquals(owner.manga.api, row.api)
        assertEquals(owner.manga.language, row.language)
        assertEquals(owner.chapterId, row.chapterId)
        assertEquals(owner.chapter.url, row.chapterUrl)
        assertEquals(owner.chapter.number, row.chapterNumber)
        assertEquals(owner.chapter.copy(id = row.chapterId), realChapters.getChapterByIdSuspend(row.chapterId))
        assertEquals(row, room.updates().single { it.mangaId == owner.manga.id })
    }

    private data class Owner(
        val manga: SavedMangaEntity,
        val chapter: SavedChapterEntity,
        val notification: ChapterNotification,
    ) {
        val chapterId: Long get() = notification.chapterId
    }

    private companion object {
        const val SHARED_URL = "https://chapter.example/shared-chapter"
    }
}
