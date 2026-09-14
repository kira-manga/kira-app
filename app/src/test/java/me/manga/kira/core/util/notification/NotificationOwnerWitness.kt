package me.manga.kira.core.util.notification

import android.app.Notification
import android.graphics.Bitmap
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** One real-DB cross-parent witness; delegates observe actual INSERT IGNORE and scoped lookup results. */
internal class NotificationOwnerWitness(
    private val room: NotificationRoomFixture,
) {
    private val realChapters = room.db.chapterDao()
    private val inserts = mutableListOf<List<Long>>()
    private val lookups = mutableListOf<Pair<Long, List<String>>>()
    private val coverUrls = mutableListOf<String>()
    private val cover = NotificationNativeCoverWitness()
    private val observedChapters =
        object : ChapterDao by realChapters {
            override suspend fun insertChaptersSafely(chapters: List<SavedChapterEntity>): List<Long> =
                realChapters.insertChaptersSafely(chapters).also { inserts.add(it) }

            override suspend fun getChapterIdsByUrlForManga(
                mangaId: Long,
                urls: List<String>,
            ): Map<String, Long> {
                val ids = realChapters.getChapterIdsByUrlForManga(mangaId, urls)
                return ids.also { lookups.add(mangaId to urls.toList()) }
            }
        }
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
        // B is the first row for the shared URL; an ownerless fallback must not supply A's ID.
        val b = seed("b")
        val a = seed("a")
        assertTrue(b.chapterId > 0L && a.chapterId > b.chapterId)
        val helper = room.helper(observedCovers, room.repository(observedChapters))
        val capturedA = helper.persistNewChapterNotifications(a.manga, listOf(a.chapter))
        assertEquals(listOf(listOf(-1L)), inserts)
        assertStoredOwner(a, capturedA.single())
        assertTrue(posting.posted.isEmpty())
        cover.assertIdle()

        val capturedB = helper.persistNewChapterNotifications(b.manga, listOf(b.chapter))
        assertStoredOwner(b, capturedB.single())
        assertEquals(listOf(listOf(-1L), listOf(-1L)), inserts)
        assertEquals(listOf(a.manga.id to listOf(SHARED_URL), b.manga.id to listOf(SHARED_URL)), lookups)
        val lookupsBeforeDisplay = lookups.toList()
        val stored = (capturedA + capturedB).sortedBy { it.id }
        assertEquals(stored, room.updates())

        helper.displayNotifications(capturedA)

        assertEquals(lookupsBeforeDisplay, lookups)
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
        val chapterId = realChapters.insertChapters(listOf(chapter)).single()
        assertEquals(chapter.copy(id = chapterId), realChapters.getChaptersByMangaIdR(manga.id).single())
        return Owner(manga, chapter, chapterId)
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
        val chapterId: Long,
    )

    private companion object {
        const val SHARED_URL = "https://chapter.example/shared-chapter"
    }
}
