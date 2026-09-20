package me.manga.kira.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.mapper.toSavedChapterEntity
import me.manga.kira.data.local.RoomMangaWriteTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises the shipping repository and generated Room transaction, with no in-memory DAO model. */
class RefreshDiscoveryConcurrencyTest {
    @Test
    fun overlappingRepositoryRefreshesCommitExactlyOneDiscoveryOutcome() = runBlocking {
        RefreshDiscoveryFixture().use { fixture ->
            val parent = fixture.parent()
            val existing = refreshChapter("1").toSavedChapterEntity(parent.id).copy(isRead = true, fetchedAt = 17)
            val existingId = fixture.db.chapterDao().insertChapters(listOf(existing)).single()
            val barrier = LibraryConcurrentWriteBarrier(RoomMangaWriteTransaction(fixture.db))
            val fetched = listOf("3", "2", "2", "1").map(::refreshChapter)
            val results = withTimeout(10_000) {
                List(2) {
                    async(Dispatchers.Default) {
                        fixture.repository(barrier).discover(parent, fetched)
                    }
                }.awaitAll()
            }
            assertEquals(listOf(0, 2), results.map { (it as AppResult.Success).value }.sorted())
            val rows = fixture.updates()
            assertEquals(listOf("2", "3"), rows.map { it.chapterNumber })
            rows.forEach { row ->
                val chapter = assertNotNull(fixture.db.chapterDao().getChapterByIdSuspend(row.chapterId))
                assertEquals(parent.id, row.mangaId)
                assertEquals(parent.id, chapter.mangaId)
                assertEquals(row.chapterUrl, chapter.url)
                assertTrue(row.id > 0 && chapter.isNew && chapter.fetchedAt > 0)
            }
            assertEquals(existing.copy(id = existingId), fixture.db.chapterDao().getChapterByIdSuspend(existingId))
            assertEquals(AppResult.Success(0), fixture.repository().discover(parent, fetched))
            fixture.reopen()
            assertEquals(rows, fixture.updates())
            assertEquals(3, fixture.db.chapterDao().getChaptersByMangaIdR(parent.id).size)
        }
    }

    @Test
    fun ignoredChapterInsertCannotCreateAnUpdateOrInflateTheDiscoveryCount() = runBlocking {
        RefreshDiscoveryFixture().use { fixture ->
            val parent = fixture.parent()
            fixture.executeWhileClosed(
                """
                CREATE TRIGGER ignore_second_discovery BEFORE INSERT ON saved_chapters
                WHEN NEW.url = 'chapter/2' BEGIN SELECT RAISE(IGNORE); END
                """.trimIndent(),
            )
            val fetched = listOf(refreshChapter("2"), refreshChapter("1"))
            assertEquals(AppResult.Success(1), fixture.repository().discover(parent, fetched))
            assertEquals(listOf("1"), fixture.updates().map { it.chapterNumber })
            assertEquals(listOf("1"), fixture.db.chapterDao().getChaptersByMangaIdR(parent.id).map { it.number })
            fixture.executeWhileClosed("DROP TRIGGER ignore_second_discovery")
            assertEquals(AppResult.Success(1), fixture.repository().discover(parent, fetched))
            assertEquals(listOf("1", "2"), fixture.updates().map { it.chapterNumber })
        }
    }

    @Test
    fun notificationInsertFailureRollsBackEarlierChapterAndNotificationWrites() = runBlocking {
        RefreshDiscoveryFixture().use { fixture ->
            val parent = fixture.parent()
            val repo = fixture.repository()
            assertEquals(AppResult.Success(1), repo.discover(parent, listOf(refreshChapter())))
            val before = fixture.updates()
            val beforeChapters = fixture.db.chapterDao().getChaptersByMangaIdR(parent.id)
            fixture.sql.beforeNotificationInsert = { ordinal ->
                if (ordinal == 3) error("second_new_notification_rejected")
            }
            val fetched = listOf("3", "2", "1").map(::refreshChapter)

            assertTrue(repo.discover(parent, fetched) is AppResult.Failure)
            assertEquals(3, fixture.sql.notificationInserts.get(), "fault follows one earlier notification in the same transaction")
            assertEquals(before, fixture.updates())
            assertEquals(beforeChapters, fixture.db.chapterDao().getChaptersByMangaIdR(parent.id))

            fixture.sql.beforeNotificationInsert = {}
            assertEquals(AppResult.Success(2), repo.discover(parent, fetched))
            assertEquals(3, fixture.updates().size)
            assertEquals(AppResult.Success(0), repo.discover(parent, fetched))
        }
    }

    @Test
    fun cancellationInsideDiscoveryRollsBackBeforeReplacementCanCommit() = runBlocking {
        RefreshDiscoveryFixture().use { fixture ->
            val parent = fixture.parent()
            val repo = fixture.repository()
            val fetched = listOf(refreshChapter("2"), refreshChapter("1"))
            val cancelled = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                repo.discover(parent, fetched)
            }
            // Runs at real notification SQL, after chapter insertion, before transaction commit.
            fixture.sql.beforeNotificationInsert = { cancelled.cancel(CancellationException("worker_replaced")) }
            cancelled.start()
            assertFailsWith<CancellationException> { cancelled.await() }
            cancelled.join()
            assertTrue(cancelled.isCompleted && cancelled.isCancelled)
            assertTrue(fixture.sql.notificationInserts.get() in 1..fetched.size)
            assertTrue(fixture.db.chapterDao().getChaptersByMangaIdR(parent.id).isEmpty())
            assertTrue(fixture.updates().isEmpty())

            fixture.sql.beforeNotificationInsert = {}
            assertEquals(AppResult.Success(2), repo.discover(parent, fetched))
            val committed = fixture.updates()
            assertEquals(AppResult.Success(0), repo.discover(parent, fetched))
            fixture.reopen()
            assertEquals(committed, fixture.updates())
        }
    }
}
