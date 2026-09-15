package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.manga.kira.data.local.entity.SavedChapterEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Native coverage of the public DAO capture wrappers and the actual generated Room transactions. */
class IosRoomCancellationIntegrityTest {
    @Test
    fun discoveryCancellationRollsBackBeforeJoinedRetryAndReopen() =
        runTest(timeout = 30.seconds) {
            val fixture = IosRoomCancellationFixture()
            try {
                val before = fixture.seed()
                val manga = before.manga
                val candidates =
                    listOf("2", "3").map { number ->
                        SavedChapterEntity(
                            mangaId = manga.id,
                            name = "Chapter $number",
                            number = number,
                            url = "${manga.url}/chapter/$number",
                            date = null,
                        )
                    }
                val cancelled =
                    async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        fixture.db.libraryDeo().persistChapterDiscoveries(
                            manga.api,
                            manga.url,
                            candidates,
                            expectedMangaId = manga.id,
                        )
                    }
                fixture.sql.cancelAfterNotificationInsert(cancelled)
                cancelled.start()
                try {
                    assertFailsWith<CancellationException> { cancelled.await() }
                } finally {
                    withContext(NonCancellable) { cancelled.cancelAndJoin() }
                }
                assertTrue(cancelled.isCompleted && cancelled.isCancelled)
                assertEquals(
                    1,
                    fixture.sql.cancellationHits,
                    "a real notification INSERT must finish before cancellation",
                )
                assertEquals(before, fixture.snapshot(manga.id), "chapter and notification writes must both roll back")
                fixture.reopen()
                assertEquals(before, fixture.snapshot(manga.id), "the cancelled transaction must not persist new rows")

                val committed =
                    fixture.db.libraryDeo().persistChapterDiscoveries(
                        manga.api,
                        manga.url,
                        candidates,
                        expectedMangaId = manga.id,
                    )
                assertEquals(candidates.map { it.url }, committed.map { it.chapterUrl })
                val after = fixture.snapshot(manga.id)
                assertEquals(before.manga, after.manga)
                assertEquals(before.history, after.history)
                assertEquals(before.chapters.size + candidates.size, after.chapters.size)
                assertEquals(before.chapters.single(), after.chapters.single { it.id == before.chapters.single().id })
                assertEquals((before.notifications + committed).sortedBy { it.id }, after.notifications)
                committed.forEach { notification ->
                    val chapter = after.chapters.single { it.id == notification.chapterId }
                    assertTrue(notification.id > 0 && chapter.isNew && chapter.fetchedAt > 0)
                    assertEquals(manga.id, notification.mangaId)
                    assertEquals(manga.id, chapter.mangaId)
                    assertEquals(notification.chapterUrl, chapter.url)
                }
                assertTrue(
                    fixture.db.libraryDeo().persistChapterDiscoveries(
                        manga.api,
                        manga.url,
                        candidates,
                        expectedMangaId = manga.id,
                    ).isEmpty(),
                    "joined retry must not create duplicate discoveries",
                )
                assertEquals(after, fixture.snapshot(manga.id))
                fixture.reopen()
                assertEquals(after, fixture.snapshot(manga.id))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun coverCancellationRollsBackBeforeJoinedRetryAndReopen() =
        runTest(timeout = 30.seconds) {
            val fixture = IosRoomCancellationFixture()
            try {
                val before = fixture.seed()
                val cancelled =
                    async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        fixture.db.mangaDao().updateCoverEverywhere(before.manga.id, NEW_COVER)
                    }
                fixture.sql.cancelAfterHistoryUpdate(cancelled)
                cancelled.start()
                try {
                    assertFailsWith<CancellationException> { cancelled.await() }
                } finally {
                    withContext(NonCancellable) { cancelled.cancelAndJoin() }
                }
                assertTrue(cancelled.isCompleted && cancelled.isCancelled)
                assertEquals(1, fixture.sql.cancellationHits, "the actual history UPDATE must precede cancellation")
                assertEquals(before, fixture.snapshot(before.manga.id), "saved/history writes must roll back")
                fixture.reopen()
                assertEquals(before, fixture.snapshot(before.manga.id))

                fixture.db.mangaDao().updateCoverEverywhere(before.manga.id, NEW_COVER)
                val expected = before.withCover(NEW_COVER)
                assertEquals(expected, fixture.snapshot(before.manga.id), "all covers change; user state survives")
                fixture.db.mangaDao().updateCoverEverywhere(before.manga.id, NEW_COVER)
                assertEquals(expected, fixture.snapshot(before.manga.id))
                fixture.reopen()
                assertEquals(expected, fixture.snapshot(before.manga.id))
            } finally {
                fixture.close()
            }
        }

    private companion object {
        const val NEW_COVER = "https://cover.test/native-room-new.webp"
    }
}
